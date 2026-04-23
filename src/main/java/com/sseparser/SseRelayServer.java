package com.sseparser;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpHeader;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The embedded HTTP relay.
 *
 * The proxy handler rewrites SSE requests to GET /relay/{token} on this server.
 * The relay looks up the token, opens a real connection to the original SSE
 * endpoint (with the original cookies/auth), and tees the byte stream back to
 * Burp while parsing events for the UI. Intercept mode holds each event at the
 * boundary until the UI decides to forward or drop it.
 */
public class SseRelayServer {

    // Hop-by-hop and proxy-specific headers that don't belong on the upstream request.
    private static final Set<String> SKIP_HEADERS = Set.of(
        "connection", "transfer-encoding", "upgrade", "proxy-authorization",
        "proxy-authenticate", "te", "trailers", "keep-alive", "host"
    );

    /** token → original request, consumed on first use. */
    private final Map<String, PendingRequest>  pending      = new ConcurrentHashMap<>();

    /** token → registration time (ms) for TTL cleanup. */
    private final Map<String, Long>            pendingTimes = new ConcurrentHashMap<>();

    /** url → currently open stream (last reconnect wins for injection). */
    private final Map<String, SseActiveStream> activeStreams = new ConcurrentHashMap<>();

    private final SseEventStore      store;
    private final MontoyaApi         api;
    private final SseInterceptQueue  interceptQueue;

    /** Shared HttpClient, one thread pool for all connections. Volatile so the proxy setter can swap it. */
    private volatile HttpClient      httpClient;

    /** Held so stop() can close it. */
    private ServerSocket             serverSocket;

    /** Runs pending-token pruning every 30s. */
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-relay-scheduler");
            t.setDaemon(true);
            return t;
        });

    private int port;

    public SseRelayServer(SseEventStore store, MontoyaApi api, SseInterceptQueue interceptQueue) {
        this.store          = store;
        this.api            = api;
        this.interceptQueue = interceptQueue;
    }


    /** Binds to an ephemeral port and starts the accept loop. Returns the port. */
    public int start() throws IOException {
        httpClient   = buildHttpClient(null);
        serverSocket = new ServerSocket(0);
        port         = serverSocket.getLocalPort();

        scheduler.scheduleAtFixedRate(this::pruneStaleTokens, 30, 30, TimeUnit.SECONDS);

        Thread acceptThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    Thread t = new Thread(() -> handleConnection(client));
                    t.setDaemon(true);
                    t.setName("sse-relay-handler-" + client.getPort());
                    t.start();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        api.logging().logToOutput("[SSE Relay] accept error: " + e.getMessage());
                    }
                }
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.setName("sse-relay-accept");
        acceptThread.start();

        api.logging().logToOutput("[SSE Relay] listening on localhost:" + port);
        return port;
    }

    /** Closes the server socket and releases any blocked intercepts. */
    public void stop() {
        scheduler.shutdownNow();
        if (serverSocket != null && !serverSocket.isClosed()) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        interceptQueue.setEnabled(false);   // unblocks any relay threads waiting on the UI
    }


    /**
     * Rebuilds the shared HttpClient to go through the given upstream proxy.
     * Pass null or blank to go direct. Already-open streams keep the old client.
     */
    public void setUpstreamProxy(String hostPort) throws IOException {
        if (hostPort == null || hostPort.isBlank()) {
            httpClient = buildHttpClient(null);
            api.logging().logToOutput("[SSE Relay] upstream proxy cleared.");
            return;
        }
        String[] parts = hostPort.trim().split(":", 2);
        if (parts.length != 2) throw new IOException("Expected host:port, got: " + hostPort);
        try {
            int p = Integer.parseInt(parts[1].trim());
            httpClient = buildHttpClient(
                ProxySelector.of(new InetSocketAddress(parts[0].trim(), p)));
            api.logging().logToOutput("[SSE Relay] upstream proxy → " + hostPort);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid port: " + parts[1]);
        }
    }

    private HttpClient buildHttpClient(ProxySelector proxy) throws IOException {
        try {
            HttpClient.Builder b = HttpClient.newBuilder()
                .sslContext(trustAll())
                .followRedirects(HttpClient.Redirect.NORMAL);
            if (proxy != null) b.proxy(proxy);
            return b.build();
        } catch (Exception e) {
            throw new IOException("Failed to build HttpClient: " + e.getMessage(), e);
        }
    }


    /** Called by the proxy handler. Returns a single-use token for the relay path. */
    public String register(String originalUrl, List<HttpHeader> headers, boolean isReconnect) {
        String token = UUID.randomUUID().toString().replace("-", "");
        pending.put(token, new PendingRequest(originalUrl, headers, isReconnect));
        pendingTimes.put(token, System.currentTimeMillis());
        return token;
    }

    /** Drop tokens registered more than 30s ago that were never consumed. */
    private void pruneStaleTokens() {
        long cutoff = System.currentTimeMillis() - 30_000L;
        Iterator<Map.Entry<String, Long>> it = pendingTimes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() < cutoff) {
                pending.remove(entry.getKey());
                it.remove();
            }
        }
    }

    public void registerStream(String url, SseActiveStream stream) {
        activeStreams.put(url, stream);
    }

    public void unregisterStream(String url) {
        SseActiveStream s = activeStreams.remove(url);
        if (s != null) s.markClosed();
    }

    public SseActiveStream getActiveStream(String url) {
        return activeStreams.get(url);
    }

    public Map<String, SseActiveStream> getAllActiveStreams() {
        return activeStreams;
    }


    private void handleConnection(Socket client) {
        try (client) {
            InputStream  in  = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isBlank()) return;

            String token = extractToken(requestLine);
            skipHeaders(in);

            if (token == null || token.isEmpty()) {
                writeError(out, 400, "Missing relay token");
                return;
            }

            PendingRequest req = pending.remove(token);
            pendingTimes.remove(token);
            if (req == null) {
                writeError(out, 404, "Unknown or expired token: " + token);
                return;
            }

            relay(req, out);

        } catch (Exception e) {
            api.logging().logToOutput("[SSE Relay] handler error: " + e.getMessage());
        }
    }

    private void relay(PendingRequest req, OutputStream out) throws Exception {
        HttpClient client = this.httpClient;   // read once; existing streams keep the old instance

        HttpRequest.Builder rb = HttpRequest.newBuilder()
            .uri(URI.create(req.originalUrl))
            .GET();

        for (HttpHeader h : req.headers) {
            String name = h.name().toLowerCase();
            if (!SKIP_HEADERS.contains(name) && !name.startsWith(":")) {
                try {
                    rb.header(h.name(), h.value());
                } catch (IllegalArgumentException ignored) {
                    // Java HttpClient rejects some headers Burp hands us, skip them.
                }
            }
        }

        HttpResponse<InputStream> response =
            client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());

        String ct = response.headers().firstValue("Content-Type").orElse("");
        if (!ct.contains("text/event-stream")) {
            api.logging().logToOutput(
                "[SSE Relay] not SSE: Content-Type='" + ct + "'  url=" + req.originalUrl);
            writeError(out, 502, "Target did not return text/event-stream");
            return;
        }

        // Echo SSE response headers toward Burp → browser.
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: text/event-stream\r\n");
        pw.print("Cache-Control: no-cache\r\n");
        pw.print("Connection: keep-alive\r\n");
        pw.print("X-Accel-Buffering: no\r\n");
        pw.print("\r\n");
        pw.flush();

        SseActiveStream activeStream = new SseActiveStream(req.originalUrl, out);
        registerStream(req.originalUrl, activeStream);

        api.logging().logToOutput("[SSE Relay] streaming → " + req.originalUrl +
            (req.isReconnect ? " (reconnect)" : ""));
        try {
            teeAndParse(response.body(), out, req.originalUrl, req.isReconnect, activeStream);
        } finally {
            // Only unregister if we still own the slot, a newer reconnect may have replaced us.
            activeStreams.remove(req.originalUrl, activeStream);
            activeStream.markClosed();
            api.logging().logToOutput("[SSE Relay] stream ended → " + req.originalUrl);
        }
    }

    /**
     * Reads SSE lines from upstream, buffers each event, passes it through the
     * intercept queue if active, and writes the (possibly modified) bytes back
     * to Burp. Comments and keepalives are forwarded immediately.
     */
    private void teeAndParse(InputStream body, OutputStream out, String url,
                              boolean isReconnect, SseActiveStream activeStream)
            throws IOException {

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(body, StandardCharsets.UTF_8));

        List<String>  rawLines  = new ArrayList<>();   // the current event's raw bytes
        StringBuilder dataAccum = new StringBuilder();
        String        eventType = "";
        String        eventId   = "";

        String line;
        while ((line = reader.readLine()) != null) {

            // Comment or keepalive: forward as-is, no buffering, no intercept.
            if (line.startsWith(":") || (line.isBlank() && dataAccum.isEmpty())) {
                try { activeStream.writeRaw(line); } catch (IOException ioEx) { break; }
                continue;
            }

            // Blank line after data → event boundary.
            if (line.isBlank()) {
                String finalType = eventType.isEmpty() ? "message" : eventType;
                String finalData = dataAccum.toString();

                SseEvent event = new SseEvent(url, finalType, eventId, finalData, LocalDateTime.now());
                event.isReconnect = isReconnect;

                if (interceptQueue.isEnabled()) {
                    String resolved;
                    try {
                        resolved = interceptQueue.interceptAndWait(event);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (resolved == null) {
                        // Dropped, nothing on the wire, but still record it for the UI.
                        rawLines.clear();
                        dataAccum.setLength(0);
                        eventType = "";
                        eventId   = "";
                        store.addEvent(event);
                        continue;
                    }

                    try {
                        activeStream.writeEvent(finalType, eventId, resolved);
                    } catch (IOException ioEx) {
                        api.logging().logToOutput(
                            "[SSE Relay] write failed during intercept forward: " + ioEx.getMessage());
                        break;
                    }

                    // If the data was edited, swap in a new event with the modified data.
                    if (!resolved.equals(finalData)) {
                        event = new SseEvent(url, finalType, eventId, resolved, event.timestamp);
                        event.isReconnect = isReconnect;
                    }

                } else {
                    // No intercept, pass the original bytes through.
                    try {
                        for (String raw : rawLines) activeStream.writeRaw(raw);
                        activeStream.writeRaw("");   // event boundary
                    } catch (IOException ioEx) {
                        api.logging().logToOutput("[SSE Relay] write failed: " + ioEx.getMessage());
                        break;
                    }
                }

                store.addEvent(event);

                rawLines.clear();
                dataAccum.setLength(0);
                eventType = "";
                eventId   = "";

            } else {
                // A regular SSE field line, add to the current event.
                rawLines.add(line);

                if (line.startsWith("data:")) {
                    String piece = line.substring(5).stripLeading();
                    if (dataAccum.length() > 0) dataAccum.append("\n");
                    dataAccum.append(piece);
                } else if (line.startsWith("event:")) {
                    eventType = line.substring(6).strip();
                } else if (line.startsWith("id:")) {
                    eventId = line.substring(3).strip();
                }
                // retry: is buffered and forwarded but not surfaced to the UI.
            }
        }
    }


    private String extractToken(String requestLine) {
        // "GET /relay/{token} HTTP/1.1"
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        String path  = parts[1];
        int    slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : null;
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') { in.read(); break; }   // eat the LF after CR
            if (c == '\n') break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private void skipHeaders(InputStream in) throws IOException {
        String line;
        while (!(line = readLine(in)).isBlank()) { /* skip */ }
    }

    private void writeError(OutputStream out, int code, String msg) throws IOException {
        String body     = "{\"error\":\"" + msg.replace("\"", "'") + "\"}";
        String response = "HTTP/1.1 " + code + " Error\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + body.length() + "\r\n" +
            "Connection: close\r\n\r\n" + body;
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Trusts any upstream TLS cert, needed for self-signed or Burp-reissued certs on targets. */
    private static SSLContext trustAll() throws Exception {
        TrustManager[] trust = { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers()                 { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String t) {}
            public void checkServerTrusted(X509Certificate[] c, String t) {}
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trust, new SecureRandom());
        return ctx;
    }
}
