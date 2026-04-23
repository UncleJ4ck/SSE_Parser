package com.sseparser.ui;

import com.sseparser.SseEvent;

import javax.swing.table.AbstractTableModel;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Table model for the events table.
 *
 * <h3>Features</h3>
 * <ul>
 *   <li>Columns: Time, Δt (ms), URL, Event type, ID, Data preview</li>
 *   <li>Optional URL scoping, passing a non-null {@code scopeUrl} to the
 *       constructor means only events for that URL are shown.</li>
 *   <li>Real-time filtering via {@link #setFilter(Predicate)}, rebuilds the
 *       visible list and fires {@code fireTableDataChanged()} immediately.</li>
 *   <li>Safe random access via {@link #getEventAt(int)} to retrieve the actual
 *       {@link SseEvent} for a given view row.</li>
 * </ul>
 *
 * All mutation methods ({@link #setEvents}, {@link #setFilter}) must be called
 * on the EDT.
 */
public class SseTableModel extends AbstractTableModel {

    private static final String[] COLUMNS =
        {"Time", "Δt (ms)", "URL", "Event", "ID", "Data"};

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");


    /** Full (unfiltered) snapshot for this model's scope. */
    private List<SseEvent> allEvents     = new ArrayList<>();

    /** The subset of allEvents that passes the current filter predicate. */
    private List<SseEvent> visibleEvents = new ArrayList<>();

    /**
     * Optional URL scope.  {@code null} = show all URLs.
     * Non-null = show only events whose {@link SseEvent#url} equals this value.
     */
    private final String scopeUrl;

    private Predicate<SseEvent> filter = e -> true;


    /**
     * Creates a model that shows all URLs.
     * Equivalent to {@code new SseTableModel(null)}.
     */
    public SseTableModel() {
        this.scopeUrl = null;
    }

    /**
     * Creates a model scoped to a single URL.
     *
     * @param scopeUrl the URL to show; {@code null} for all-URLs mode
     */
    public SseTableModel(String scopeUrl) {
        this.scopeUrl = scopeUrl;
    }


    /**
     * Replaces the backing list with a new snapshot and reapplies the current
     * filter.  Must be called on the EDT.
     */
    public void setEvents(List<SseEvent> events) {
        if (scopeUrl != null) {
            allEvents = new ArrayList<>();
            for (SseEvent e : events) {
                if (scopeUrl.equals(e.url)) allEvents.add(e);
            }
        } else {
            allEvents = new ArrayList<>(events);
        }
        refilter();
    }

    /**
     * Replaces the active filter predicate and refilters immediately.
     * A {@code null} predicate is treated as a pass-through (show everything).
     */
    public void setFilter(Predicate<SseEvent> pred) {
        this.filter = pred != null ? pred : e -> true;
        refilter();
    }

    private void refilter() {
        visibleEvents = new ArrayList<>();
        for (SseEvent e : allEvents) {
            if (filter.test(e)) visibleEvents.add(e);
        }
        fireTableDataChanged();
    }


    @Override
    public int     getRowCount()                      { return visibleEvents.size(); }
    @Override
    public int     getColumnCount()                   { return COLUMNS.length; }
    @Override
    public String  getColumnName(int col)             { return COLUMNS[col]; }
    @Override
    public boolean isCellEditable(int row, int col)  { return false; }

    @Override
    public Object getValueAt(int row, int col) {
        if (row < 0 || row >= visibleEvents.size()) return "";
        SseEvent e = visibleEvents.get(row);
        return switch (col) {
            case 0 -> e.timestamp.format(FMT);
            case 1 -> e.deltaMs < 0 ? "" : String.valueOf(e.deltaMs);
            case 2 -> shortenUrl(e.url);
            case 3 -> e.event;
            case 4 -> e.id.isEmpty() ? "" : e.id;
            case 5 -> {
                String d = e.data.replace("\n", " ↵ ");
                yield d.length() > 120 ? d.substring(0, 120) + "…" : d;
            }
            default -> "";
        };
    }


    /**
     * Returns the {@link SseEvent} backing the given view row, or {@code null}
     * if the row index is out of bounds.
     */
    public SseEvent getEventAt(int viewRow) {
        if (viewRow < 0 || viewRow >= visibleEvents.size()) return null;
        return visibleEvents.get(viewRow);
    }

    /** Returns the number of events currently passing the filter. */
    public int getVisibleCount() {
        return visibleEvents.size();
    }

    /** Returns the total unfiltered count for this model's scope. */
    public int getTotalCount() {
        return allEvents.size();
    }


    private static String shortenUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            String base = (host != null ? host : url);
            String p    = (path == null || path.isEmpty()) ? "/" : path;
            String combined = base + p;
            return combined.length() > 70 ? combined.substring(0, 70) + "…" : combined;
        } catch (Exception ex) {
            return url.length() > 70 ? url.substring(0, 70) + "…" : url;
        }
    }
}
