package com.sseparser.ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Small bar-chart showing the SSE event arrival rate over the last 60 seconds.
 *
 * <ul>
 *   <li>One bar per second, so the chart is 60 bars wide.</li>
 *   <li>The most recent second is on the right; the oldest on the left.</li>
 *   <li>Bar height is normalised to the highest bucket in the window.</li>
 *   <li>Buckets with zero events are drawn as a faint 2-pixel stub so the
 *       grid structure remains visible.</li>
 * </ul>
 *
 * Call {@link #addEvent(long)} from any thread; it dispatches a repaint via
 * {@link SwingUtilities#invokeLater}.
 */
public class SparklinePanel extends JPanel {


    private static final int WINDOW_SECS  = 60;
    private static final int BAR_WIDTH    = 3;
    private static final int GAP          = 1;
    /** Pixel width of the whole panel: one (bar + gap) slot per second. */
    private static final int PANEL_WIDTH  = (BAR_WIDTH + GAP) * WINDOW_SECS;   // 240 px
    private static final int PANEL_HEIGHT = 40;


    private static final Color BG_COLOR    = new Color(245, 245, 250);
    private static final Color BAR_COLOR   = new Color(0, 130, 210);
    private static final Color DIM_COLOR   = new Color(195, 215, 232);
    private static final Color LABEL_COLOR = new Color(150, 150, 162);


    /** All event timestamps (epoch ms) within the last WINDOW_SECS seconds. */
    private final Deque<Long> eventTimestamps = new ArrayDeque<>();


    public SparklinePanel() {
        Dimension fixed = new Dimension(PANEL_WIDTH, PANEL_HEIGHT);
        setPreferredSize(fixed);
        setMinimumSize(fixed);
        setMaximumSize(fixed);
        setBackground(BG_COLOR);
        setToolTipText("Event rate, last 60 seconds (1 bar per second)");
    }


    /**
     * Records an event timestamp and triggers a repaint.
     * Thread-safe: may be called from the relay thread.
     *
     * @param timestampMs epoch milliseconds of the event arrival time
     */
    public void addEvent(long timestampMs) {
        synchronized (eventTimestamps) {
            eventTimestamps.addLast(timestampMs);
            pruneOld(timestampMs);
        }
        SwingUtilities.invokeLater(this::repaint);
    }


    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_OFF);

        long nowMs = System.currentTimeMillis();
        int  h     = getHeight();
        int  w     = getWidth();

        // Build per-second bucket counts (index 0 = oldest, WINDOW_SECS-1 = newest).
        int[] counts = new int[WINDOW_SECS];
        synchronized (eventTimestamps) {
            pruneOld(nowMs);
            for (long ts : eventTimestamps) {
                int secAgo = (int) ((nowMs - ts) / 1000L);
                if (secAgo >= 0 && secAgo < WINDOW_SECS) {
                    counts[WINDOW_SECS - 1 - secAgo]++;
                }
            }
        }

        // Find the maximum bucket count for normalisation (minimum 1 to avoid /0).
        int max = 1;
        for (int c : counts) {
            if (c > max) max = c;
        }

        // Draw bars.
        for (int i = 0; i < WINDOW_SECS; i++) {
            int x    = i * (BAR_WIDTH + GAP);
            int barH = counts[i] == 0
                ? 2
                : Math.max(2, (int) ((double) counts[i] / max * (h - 4)));
            int y    = h - barH;
            g2.setColor(counts[i] == 0 ? DIM_COLOR : BAR_COLOR);
            g2.fillRect(x, y, BAR_WIDTH, barH);
        }

        // "60s" label in the bottom-right corner.
        g2.setColor(LABEL_COLOR);
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
        g2.drawString("60s", w - 20, h - 2);

        g2.dispose();
    }


    /**
     * Removes timestamps older than {@link #WINDOW_SECS} seconds.
     * Must be called while holding the monitor on {@link #eventTimestamps}.
     */
    private void pruneOld(long nowMs) {
        long cutoff = nowMs - ((long) WINDOW_SECS * 1000L);
        while (!eventTimestamps.isEmpty() && eventTimestamps.peekFirst() < cutoff) {
            eventTimestamps.pollFirst();
        }
    }
}
