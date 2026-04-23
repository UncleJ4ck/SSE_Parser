package com.sseparser;

import burp.api.montoya.http.message.HttpHeader;

import java.util.List;

/**
 * Passes the original SSE request details from the proxy handler to the relay
 * via a single-use token. Headers are defensively copied.
 */
public class PendingRequest {

    public final String originalUrl;
    public final List<HttpHeader> headers;

    /** True if the browser already connected to this URL earlier in the session. */
    public final boolean isReconnect;

    public PendingRequest(String originalUrl, List<HttpHeader> headers, boolean isReconnect) {
        this.originalUrl = originalUrl;
        this.headers     = List.copyOf(headers);
        this.isReconnect = isReconnect;
    }
}
