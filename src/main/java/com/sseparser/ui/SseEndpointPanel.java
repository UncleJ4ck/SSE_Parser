package com.sseparser.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.sseparser.SseActiveStream;
import com.sseparser.SseEvent;
import com.sseparser.SseEventStore;
import com.sseparser.SseInterceptQueue;
import com.sseparser.SseRelayServer;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Per-endpoint panel: search, events table, and a tabbed detail pane
 * (Data, Diff, Inject, Timeline). Right-clicking a row offers Replay,
 * Send to Repeater, and Copy actions.
 */
public class SseEndpointPanel extends JPanel {

    // Palette
    private static final Color BG           = Color.WHITE;
    private static final Color ROW_ALT      = new Color(248, 248, 252);
    private static final Color ROW_RECON    = new Color(255, 245, 220);
    private static final Color HEADER       = new Color(238, 238, 242);
    private static final Color TEXT         = new Color(20, 20, 20);
    private static final Color DIM          = new Color(120, 120, 130);
    private static final Color DATA         = new Color(0, 110, 0);
    private static final Color GRID         = new Color(228, 228, 234);
    private static final Color BORDER       = new Color(216, 216, 222);
    private static final Color ACCENT       = new Color(235, 70, 12);

    private final SseEventStore     store;
    private final String            scopeUrl;      // null for the "All" tab
    private final SseInterceptQueue interceptQueue;
    private final SseRelayServer    relay;
    private final MontoyaApi        api;

    private final SseFilterBar  filterBar;
    private final SseTableModel tableModel;
    private final JTable        table;

    // Detail tabs
    private final JLabel     dataMeta;
    private final JTextArea  dataArea;
    private final JLabel     diffMeta;
    private final JTextArea  diffArea;
    private final JLabel     injectTarget;
    private final JTextField injectType;
    private final JTextField injectId;
    private final JTextArea  injectDataArea;
    private final JLabel     injectStatus;
    private final SparklinePanel sparkline;

    private volatile SseEvent selectedEvent = null;

    private Runnable                                changeListener;
    private java.util.function.Consumer<SseEvent>   eventListener;

    public SseEndpointPanel(SseEventStore store,
                            String scopeUrl,
                            SseInterceptQueue interceptQueue,
                            MontoyaApi api,
                            SseRelayServer relay) {
        super(new BorderLayout());
        setBackground(BG);

        this.store          = store;
        this.scopeUrl       = scopeUrl;
        this.interceptQueue = interceptQueue;
        this.relay          = relay;
        this.api            = api;

        filterBar  = new SseFilterBar();
        tableModel = new SseTableModel(scopeUrl);
        sparkline  = new SparklinePanel();

        // Detail widgets
        dataMeta = metaLabel();
        dataArea = readOnlyArea();

        diffMeta = metaLabel();
        diffArea = readOnlyArea();

        injectTarget = metaLabel();
        injectType   = plainField("message", 10);
        injectId     = plainField("", 8);
        injectDataArea = new JTextArea(4, 40);
        injectDataArea.setBackground(Color.WHITE);
        injectDataArea.setForeground(DATA);
        injectDataArea.setCaretColor(ACCENT);
        injectDataArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        injectDataArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        injectStatus = new JLabel(" ");
        injectStatus.setForeground(DIM);
        injectStatus.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));

        table = buildTable();

        filterBar.addChangeListener(() -> tableModel.setFilter(filterBar.getPredicate()));

        // Store listeners
        this.changeListener = () -> SwingUtilities.invokeLater(() -> {
            tableModel.setEvents(store.getEvents());
            if (selectedEvent != null) refreshDetail();
        });
        this.eventListener = ev -> {
            if (scopeUrl == null || scopeUrl.equals(ev.url)) {
                sparkline.addEvent(System.currentTimeMillis());
            }
        };
        store.addListener(changeListener);
        store.addEventListener(eventListener);

        tableModel.setEvents(store.getEvents());

        // Layout
        JScrollPane tableScroll = wrapScroll(table);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, buildDetailTabs());
        split.setResizeWeight(0.6);
        split.setDividerSize(4);
        split.setBackground(BG);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);

        add(filterBar, BorderLayout.NORTH);
        add(split,     BorderLayout.CENTER);

        clearDetail();
    }

    public void dispose() {
        if (changeListener != null) store.removeListener(changeListener);
        if (eventListener  != null) store.removeEventListener(eventListener);
    }

    // Table -----------------------------------------------------------------

    private JTable buildTable() {
        JTable t = new JTable(tableModel);
        t.setAutoCreateRowSorter(true);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setBackground(BG);
        t.setForeground(TEXT);
        t.setGridColor(GRID);
        t.setRowHeight(22);
        t.setShowGrid(true);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        t.setFillsViewportHeight(true);

        JTableHeader h = t.getTableHeader();
        h.setBackground(HEADER);
        h.setForeground(DIM);
        h.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        h.setReorderingAllowed(false);
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));

        // Column widths: Time, Δt, URL, Event, ID, Data
        int[] widths = {82, 55, 210, 90, 55, 0};
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] > 0) t.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object val,
                    boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(tbl, val, sel, focus, row, col);
                if (!sel) {
                    SseEvent ev = tableModel.getEventAt(row);
                    boolean reconnect = ev != null && ev.isReconnect;
                    Color rowBg = reconnect ? ROW_RECON : (row % 2 == 0 ? BG : ROW_ALT);
                    if (!reconnect && col == 3 && ev != null) {
                        Color typeColor = eventTypeColor(ev.event);
                        setBackground(typeColor != null ? typeColor : rowBg);
                    } else {
                        setBackground(rowBg);
                    }
                    setForeground(col == 5 ? DATA : TEXT);
                }
                setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                return this;
            }
        };
        for (int i = 0; i < t.getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }

        t.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int vr = t.getSelectedRow();
            if (vr < 0) { selectedEvent = null; clearDetail(); return; }
            selectedEvent = tableModel.getEventAt(t.convertRowIndexToModel(vr));
            refreshDetail();
        });

        // Right-click menu
        JPopupMenu popup = new JPopupMenu();
        JMenuItem replay   = new JMenuItem("Replay to browser");
        JMenuItem repeater = new JMenuItem("Send to Repeater");
        JMenuItem copyData = new JMenuItem("Copy data");
        JMenuItem copyRaw  = new JMenuItem("Copy as raw SSE");
        replay.addActionListener(e   -> replaySelected());
        repeater.addActionListener(e -> sendToRepeater());
        copyData.addActionListener(e -> {
            if (selectedEvent != null) copyToClipboard(selectedEvent.data);
        });
        copyRaw.addActionListener(e -> {
            if (selectedEvent != null) copyToClipboard(toRawSse(selectedEvent));
        });
        popup.add(replay);
        popup.add(repeater);
        popup.addSeparator();
        popup.add(copyData);
        popup.add(copyRaw);

        t.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = t.rowAtPoint(e.getPoint());
                if (row >= 0) t.setRowSelectionInterval(row, row);
                popup.show(t, e.getX(), e.getY());
            }
        });

        return t;
    }

    // Detail tabs -----------------------------------------------------------

    private JTabbedPane buildDetailTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(BG);
        tabs.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        tabs.setBorder(BorderFactory.createEmptyBorder());
        tabs.addTab("Data",     buildDataPane());
        tabs.addTab("Diff",     buildDiffPane());
        tabs.addTab("Inject",   buildInjectPane());
        tabs.addTab("Timeline", buildTimelinePane());
        return tabs;
    }

    private JPanel buildDataPane() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        p.add(dataMeta, BorderLayout.NORTH);
        p.add(wrapScroll(dataArea), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildDiffPane() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        p.add(diffMeta, BorderLayout.NORTH);
        p.add(wrapScroll(diffArea), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildInjectPane() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.setBackground(BG);
        header.add(injectTarget, BorderLayout.NORTH);

        JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fields.setBackground(BG);
        fields.add(smallLabel("event")); fields.add(injectType);
        fields.add(smallLabel("id"));    fields.add(injectId);
        header.add(fields, BorderLayout.CENTER);

        JButton go = new JButton("Inject");
        go.setBackground(ACCENT);
        go.setForeground(Color.WHITE);
        go.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        go.setFocusPainted(false);
        go.setBorderPainted(false);
        go.setOpaque(true);
        go.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        go.addActionListener(e -> performInject());

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBackground(BG);
        bottom.add(go,          BorderLayout.WEST);
        bottom.add(injectStatus, BorderLayout.CENTER);

        p.add(header,                    BorderLayout.NORTH);
        p.add(wrapScroll(injectDataArea), BorderLayout.CENTER);
        p.add(bottom,                     BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildTimelinePane() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel sparkWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sparkWrap.setBackground(BG);
        sparkWrap.add(sparkline);

        JLabel caption = new JLabel("Events per second, last 60 seconds");
        caption.setForeground(DIM);
        caption.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));

        p.add(sparkWrap, BorderLayout.NORTH);
        p.add(caption,   BorderLayout.CENTER);
        return p;
    }

    // Detail state sync -----------------------------------------------------

    private void refreshDetail() {
        SseEvent ev = selectedEvent;
        if (ev == null) { clearDetail(); return; }

        String meta = String.format("seq #%d · %s · %s · Δ %s%s",
            ev.sequenceNum,
            ev.event,
            shortenUrl(ev.url),
            ev.deltaMs < 0 ? "first" : ev.deltaMs + " ms",
            ev.isReconnect ? " · reconnect" : "");

        dataMeta.setText(meta);
        dataArea.setForeground(DATA);
        dataArea.setText(formatData(ev.data));
        dataArea.setCaretPosition(0);

        if (ev.predecessor != null) {
            diffMeta.setText(String.format("diff vs seq #%d (same %s)",
                ev.predecessor.sequenceNum, ev.event));
            diffArea.setForeground(new Color(50, 50, 50));
            diffArea.setText(buildDiff(ev.predecessor.data, ev.data));
        } else {
            diffMeta.setText("No previous " + ev.event + " event to diff against.");
            diffArea.setText("");
        }
        diffArea.setCaretPosition(0);

        injectTarget.setText("Inject into: " + shortenUrl(scopeUrl != null ? scopeUrl : ev.url));
        injectType.setText(ev.event);
        injectId.setText("");
        injectDataArea.setText(ev.data);
    }

    private void clearDetail() {
        dataMeta.setText("Select a row to view event details.");
        dataArea.setForeground(DIM);
        dataArea.setText("");

        diffMeta.setText(" ");
        diffArea.setText("");

        injectTarget.setText(scopeUrl != null
            ? "Inject into: " + shortenUrl(scopeUrl)
            : "Select an event to choose a target stream.");
        injectType.setText("message");
        injectId.setText("");
        injectDataArea.setText("");
    }

    // Actions ---------------------------------------------------------------

    private void replaySelected() {
        SseEvent ev = selectedEvent;
        if (ev == null) return;
        SseActiveStream stream = relay.getActiveStream(ev.url);
        if (stream == null || !stream.isOpen()) {
            flash("No active stream for this URL.", true);
            return;
        }
        try {
            stream.writeEvent(ev.event, ev.id, ev.data);
            flash("Replayed seq #" + ev.sequenceNum, false);
        } catch (Exception ex) {
            flash("Replay failed: " + ex.getMessage(), true);
        }
    }

    private void sendToRepeater() {
        SseEvent ev = selectedEvent;
        if (ev == null) return;
        try {
            String body    = toRawSse(ev);
            int    bodyLen = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            String raw =
                "POST /sse-event HTTP/1.1\r\n" +
                "Host: sse-parser.internal\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "X-SSE-Event: " + ev.event + "\r\n" +
                "X-SSE-ID: "    + ev.id + "\r\n" +
                "X-SSE-URL: "   + ev.url + "\r\n" +
                "X-SSE-Seq: "   + ev.sequenceNum + "\r\n" +
                "Content-Length: " + bodyLen + "\r\n" +
                "\r\n" + body;

            HttpRequest req = HttpRequest.httpRequest(
                HttpService.httpService("sse-parser.internal", 80, false), raw);
            api.repeater().sendToRepeater(req, "SSE: " + ev.event + " #" + ev.sequenceNum);
            api.logging().logToOutput("[SSE Parser] sent event #" + ev.sequenceNum + " to Repeater.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "Could not send to Repeater:\n" + ex.getMessage(),
                "Repeater error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void performInject() {
        String targetUrl = scopeUrl;
        if (targetUrl == null) {
            SseEvent ev = selectedEvent;
            if (ev == null) {
                flash("Select an event first so we know which stream to inject to.", true);
                return;
            }
            targetUrl = ev.url;
        }
        SseActiveStream stream = relay.getActiveStream(targetUrl);
        if (stream == null || !stream.isOpen()) {
            flash("No active stream for " + shortenUrl(targetUrl), true);
            return;
        }
        try {
            String type = injectType.getText().strip();
            if (type.isEmpty()) type = "message";
            stream.writeEvent(type, injectId.getText().strip(), injectDataArea.getText());
            flash("Injected into " + shortenUrl(targetUrl), false);
        } catch (Exception ex) {
            flash("Inject failed: " + ex.getMessage(), true);
        }
    }

    private void flash(String msg, boolean error) {
        injectStatus.setText(msg);
        injectStatus.setForeground(error ? new Color(180, 25, 25) : new Color(0, 120, 0));
        Timer t = new Timer(4000, e -> injectStatus.setText(" "));
        t.setRepeats(false);
        t.start();
    }

    private void copyToClipboard(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(s), null);
    }

    // Formatting helpers ----------------------------------------------------

    /** Pretty-prints JSON objects/arrays; otherwise returns data unchanged. */
    static String formatData(String data) {
        if (data == null) return "";
        String trimmed = data.strip();
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && trimmed.length() > 2) {
            String pretty = prettyJson(trimmed);
            if (pretty != null) return pretty;
        }
        return data;
    }

    static String prettyJson(String src) {
        try {
            StringBuilder sb    = new StringBuilder(src.length() + 128);
            int           depth = 0;
            boolean       inStr = false;
            char          prev  = 0;
            for (int i = 0; i < src.length(); i++) {
                char c = src.charAt(i);
                if (c == '"' && prev != '\\') {
                    inStr = !inStr;
                    sb.append(c);
                    prev = c;
                    continue;
                }
                if (inStr) {
                    sb.append(c);
                    prev = (c == '\\' && prev == '\\') ? 0 : c;
                    continue;
                }
                switch (c) {
                    case '{', '[' -> { sb.append(c).append('\n'); depth++; sb.append("  ".repeat(depth)); }
                    case '}', ']' -> { sb.append('\n'); depth = Math.max(0, depth - 1); sb.append("  ".repeat(depth)); sb.append(c); }
                    case ','      -> { sb.append(c).append('\n'); sb.append("  ".repeat(depth)); }
                    case ':'      -> sb.append(": ");
                    case ' ', '\t', '\r', '\n' -> { /* drop */ }
                    default       -> sb.append(c);
                }
                prev = c;
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Line-by-line diff using LCS, prefixed with +, -, and space. */
    static String buildDiff(String before, String after) {
        if (before == null) before = "";
        if (after  == null) after  = "";
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n",  -1);
        int m = a.length, n = b.length;

        int[][] lcs = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j])
                    ? lcs[i + 1][j + 1] + 1
                    : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        StringBuilder sb = new StringBuilder();
        int i = 0, j = 0;
        while (i < m || j < n) {
            if (i < m && j < n && a[i].equals(b[j])) {
                sb.append("  ").append(a[i]).append('\n'); i++; j++;
            } else if (j < n && (i >= m || lcs[i + 1][j] <= lcs[i][j + 1])) {
                sb.append("+ ").append(b[j]).append('\n'); j++;
            } else {
                sb.append("- ").append(a[i]).append('\n'); i++;
            }
        }
        return sb.toString().stripTrailing();
    }

    private static String toRawSse(SseEvent ev) {
        StringBuilder sb = new StringBuilder();
        if (!ev.event.equals("message")) sb.append("event: ").append(ev.event).append('\n');
        if (!ev.id.isEmpty())            sb.append("id: ").append(ev.id).append('\n');
        for (String line : ev.data.split("\n", -1)) sb.append("data: ").append(line).append('\n');
        sb.append('\n');
        return sb.toString();
    }

    private static String shortenUrl(String url) {
        if (url == null) return "";
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            String result = (host != null ? host : "") + (path != null ? path : "");
            return result.length() > 60 ? result.substring(0, 60) + "…" : result;
        } catch (Exception e) {
            return url.length() > 60 ? url.substring(0, 60) + "…" : url;
        }
    }

    private static Color eventTypeColor(String type) {
        if (type == null) return null;
        return switch (type.toLowerCase()) {
            case "message"   -> new Color(221, 237, 255);
            case "heartbeat" -> new Color(221, 245, 221);
            case "alert"     -> new Color(255, 239, 218);
            case "metrics"   -> new Color(238, 228, 255);
            default          -> null;
        };
    }

    // Swing helpers ---------------------------------------------------------

    private static JScrollPane wrapScroll(JComponent c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(BorderFactory.createLineBorder(BORDER));
        sp.setBackground(BG);
        sp.getViewport().setBackground(BG);
        return sp;
    }

    private static JTextArea readOnlyArea() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setBackground(BG);
        a.setForeground(DATA);
        a.setCaretColor(DATA);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        a.setLineWrap(true);
        a.setWrapStyleWord(false);
        a.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return a;
    }

    private static JLabel metaLabel() {
        JLabel l = new JLabel(" ");
        l.setForeground(DIM);
        l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        l.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 2));
        return l;
    }

    private static JLabel smallLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(DIM);
        l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        return l;
    }

    private static JTextField plainField(String init, int cols) {
        JTextField f = new JTextField(init, cols);
        f.setBackground(Color.WHITE);
        f.setForeground(TEXT);
        f.setCaretColor(ACCENT);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        f.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        return f;
    }
}
