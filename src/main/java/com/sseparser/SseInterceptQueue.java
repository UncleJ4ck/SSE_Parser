package com.sseparser;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The pause/forward/drop mechanism. Relay threads call interceptAndWait() and
 * block until the UI resolves the held event. The EDT calls forward/drop on
 * the current PendingIntercept to unblock the relay.
 */
public class SseInterceptQueue {

    /** One event held by the intercept mechanism waiting for a UI decision. */
    public static final class PendingIntercept {

        public final SseEvent event;

        /** Completes with the data to forward, or null if the event is dropped. */
        private final CompletableFuture<String> future = new CompletableFuture<>();

        public PendingIntercept(SseEvent event) {
            this.event = event;
        }

        /** Forward the event unchanged. */
        public void forward() {
            future.complete(event.data);
        }

        /** Forward with edited data. If modifiedData is null, sends the original. */
        public void forward(String modifiedData) {
            future.complete(modifiedData != null ? modifiedData : event.data);
        }

        /** Drop the event, the relay will not write it to the browser. */
        public void drop() {
            future.complete(null);
        }

        String await() throws InterruptedException {
            try {
                return future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                return null;   // we never complete exceptionally
            }
        }

        public boolean isDone() {
            return future.isDone();
        }
    }

    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /**
     * Usually holds at most one item, each relay thread blocks until its event
     * is resolved. Multiple concurrent streams can add more than one.
     */
    private final LinkedBlockingQueue<PendingIntercept> queue = new LinkedBlockingQueue<>();

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public boolean isEnabled() {
        return enabled.get();
    }

    /** When disabled, any pending events are auto-forwarded so relay threads unblock. */
    public void setEnabled(boolean on) {
        enabled.set(on);
        if (!on) {
            PendingIntercept p;
            while ((p = queue.poll()) != null) p.forward();
            fireListeners();
        }
    }

    /** Called by relay threads. Blocks until the UI resolves the event. */
    public String interceptAndWait(SseEvent event) throws InterruptedException {
        if (!enabled.get()) return event.data;

        PendingIntercept pi = new PendingIntercept(event);
        queue.put(pi);

        // Intercept may have been toggled off between our check and the put.
        // Auto-forward in that case so we don't block forever.
        if (!enabled.get()) pi.forward();

        fireListeners();
        String result = pi.await();
        queue.remove(pi);
        fireListeners();
        return result;
    }

    /** Oldest unresolved intercept, or null if none. */
    public PendingIntercept current() {
        return queue.peek();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void fireListeners() {
        listeners.forEach(Runnable::run);
    }
}
