package com.sseparser;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.ProxyRequestHandler;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catches requests with Accept: text/event-stream and redirects them through
 * the local relay. If the same URL shows up twice in one session, it's flagged
 * as a reconnect so the UI can highlight it.
 */
public class SseProxyRequestHandler implements ProxyRequestHandler {

    private final MontoyaApi     api;
    private final SseRelayServer relay;
    private final int            relayPort;

    private final Set<String> seenUrls = ConcurrentHashMap.newKeySet();

    public SseProxyRequestHandler(MontoyaApi api, SseRelayServer relay, int relayPort) {
        this.api       = api;
        this.relay     = relay;
        this.relayPort = relayPort;
    }

    @Override
    public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest request) {
        String accept = request.headerValue("Accept");
        if (accept == null || !accept.toLowerCase().contains("text/event-stream")) {
            return ProxyRequestReceivedAction.continueWith(request);
        }

        String  originalUrl = request.url();
        boolean isReconnect = !seenUrls.add(originalUrl);   // add() returns false if already present

        String token = relay.register(originalUrl, request.headers(), isReconnect);

        api.logging().logToOutput(
            "[SSE Parser] intercepting → " + originalUrl +
            "  token=" + token + "  reconnect=" + isReconnect);

        HttpRequest redirected = request
            .withService(HttpService.httpService("localhost", relayPort, false))
            .withPath("/relay/" + token)
            .withHeader("Host", "localhost:" + relayPort);

        // Put the original URL in Burp's HTTP history Notes column so the
        // localhost:PORT redirect isn't mysterious to the user.
        Annotations ann = request.annotations().withNotes("SSE → " + originalUrl);
        return ProxyRequestReceivedAction.continueWith(redirected, ann);
    }

    @Override
    public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest request) {
        return ProxyRequestToBeSentAction.continueWith(request);
    }
}
