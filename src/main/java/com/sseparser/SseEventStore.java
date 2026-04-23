package com.sseparser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Holds every captured event. Enriches each one with a sequence number,
 * delta-ms since the previous event on the same URL, and a reference to the
 * predecessor event of the same (url, type). Fires listeners on every change.
 */
public class SseEventStore {

    private final CopyOnWriteArrayList<SseEvent> allEvents = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEvent>> byUrl =
            new ConcurrentHashMap<>();

    /** key = url + "::" + type → last event seen for that combination. */
    private final ConcurrentHashMap<String, SseEvent> lastEventByUrlType =
            new ConcurrentHashMap<>();

    /** key = url → epoch ms of the most recent event on that URL. */
    private final ConcurrentHashMap<String, Long> lastEventTimestampByUrl =
            new ConcurrentHashMap<>();

    private final AtomicInteger sequence = new AtomicInteger(0);

    private final CopyOnWriteArrayList<Runnable>          changeListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<SseEvent>> eventListeners = new CopyOnWriteArrayList<>();

    /**
     * Enriches the event and stores it. Thread-safe. The enrichment fields are
     * written before the event becomes visible through the lists, so readers
     * always see fully-enriched events.
     */
    public void addEvent(SseEvent raw) {
        raw.sequenceNum = sequence.incrementAndGet();

        long nowMs  = System.currentTimeMillis();
        Long prevMs = lastEventTimestampByUrl.put(raw.url, nowMs);
        raw.deltaMs = (prevMs == null) ? -1L : (nowMs - prevMs);

        // put() returns the old value, that's our predecessor for this (url, type).
        raw.predecessor = lastEventByUrlType.put(raw.urlTypeKey(), raw);

        allEvents.add(raw);
        byUrl.computeIfAbsent(raw.url, u -> new CopyOnWriteArrayList<>()).add(raw);

        fireChange();
        eventListeners.forEach(l -> l.accept(raw));
    }

    public List<SseEvent> getEvents() {
        return new ArrayList<>(allEvents);
    }

    public List<SseEvent> getEventsForUrl(String url) {
        CopyOnWriteArrayList<SseEvent> list = byUrl.get(url);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    public Set<String> getUrls() {
        return byUrl.keySet();
    }

    /** The event that the next (url, type) event will see as its predecessor. */
    public SseEvent getPredecessor(String url, String type) {
        return lastEventByUrlType.get(url + "::" + type);
    }

    public void clear() {
        allEvents.clear();
        byUrl.clear();
        lastEventByUrlType.clear();
        lastEventTimestampByUrl.clear();
        sequence.set(0);
        fireChange();
    }

    public void addListener(Runnable listener)    { changeListeners.add(listener); }
    public void removeListener(Runnable listener) { changeListeners.remove(listener); }

    public void addEventListener(Consumer<SseEvent> listener)    { eventListeners.add(listener); }
    public void removeEventListener(Consumer<SseEvent> listener) { eventListeners.remove(listener); }

    private void fireChange() {
        changeListeners.forEach(Runnable::run);
    }
}
