package com.sseparser;

import java.time.LocalDateTime;

/**
 * One parsed SSE event. Core fields are set at construction. Enrichment fields
 * (deltaMs, sequenceNum, predecessor, isReconnect) are written once by
 * SseEventStore right after construction and are volatile so later reads are safe.
 */
public class SseEvent {

    public final String        url;
    public final String        event;       // defaults to "message" per the SSE spec
    public final String        id;          // empty when no id: field was sent
    public final String        data;        // multi-line data joined with '\n'
    public final LocalDateTime timestamp;

    /** ms since the previous event on the same URL. -1 for the first event. */
    public volatile long     deltaMs     = -1L;

    /** Global counter, starts at 1. */
    public volatile int      sequenceNum = 0;

    /** True if the browser reconnected, renders with a warm row tint. */
    public volatile boolean  isReconnect = false;

    /** The previous event with the same (url, type). Used by the diff view. */
    public volatile SseEvent predecessor  = null;

    public SseEvent(String url, String event, String id, String data, LocalDateTime timestamp) {
        this.url       = url       != null ? url       : "";
        this.event     = event     != null ? event     : "message";
        this.id        = id        != null ? id        : "";
        this.data      = data      != null ? data      : "";
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    /** Key for per-(url, type) lookups in SseEventStore. */
    public String urlTypeKey() {
        return url + "::" + event;
    }

    @Override
    public String toString() {
        return "SseEvent{seq=" + sequenceNum +
               ", url='"       + url           + '\'' +
               ", event='"     + event         + '\'' +
               ", id='"        + id            + '\'' +
               ", deltaMs="    + deltaMs       +
               ", isReconnect="+ isReconnect   + '}';
    }
}
