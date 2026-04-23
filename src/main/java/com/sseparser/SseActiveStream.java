package com.sseparser;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps an open SSE stream so the UI can write to it (inject / replay) safely.
 * All writes go through the same instance monitor, so the relay's own forwarded
 * bytes cannot interleave with injected events on the wire.
 */
public class SseActiveStream {

    public final String url;

    private final OutputStream  out;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseActiveStream(String url, OutputStream out) {
        this.url = url;
        this.out = out;
    }

    /**
     * Writes one SSE event. Blank type/id are omitted; embedded newlines in
     * data produce multiple data: lines per the SSE spec.
     */
    public synchronized void writeEvent(String type, String id, String data) throws IOException {
        if (closed.get()) throw new IOException("SSE stream is closed: " + url);

        StringBuilder sb = new StringBuilder();
        if (type != null && !type.isBlank()) sb.append("event: ").append(type.strip()).append('\n');
        if (id   != null && !id.isBlank())   sb.append("id: ").append(id.strip()).append('\n');

        String safe = data != null ? data : "";
        for (String line : safe.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');   // event boundary

        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Writes one raw SSE line followed by a newline. Shares the instance monitor
     * with writeEvent() so relay forwarding and UI injection cannot interleave.
     * Pass "" to write the blank event-boundary line.
     */
    public synchronized void writeRaw(String line) throws IOException {
        if (closed.get()) throw new IOException("SSE stream is closed: " + url);
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Marks the stream closed. Further writes throw. Does not close the underlying stream. */
    public void markClosed() {
        closed.set(true);
    }

    public boolean isOpen() {
        return !closed.get();
    }
}
