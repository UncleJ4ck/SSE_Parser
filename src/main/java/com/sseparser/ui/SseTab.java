package com.sseparser.ui;

import burp.api.montoya.MontoyaApi;
import com.sseparser.SseEvent;
import com.sseparser.SseEventStore;
import com.sseparser.SseInterceptQueue;
import com.sseparser.SseInterceptQueue.PendingIntercept;
import com.sseparser.SseRelayServer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root Burp tab. Hosts the toolbar (intercept, clear, export, proxy, counter),
 * the endpoint tabs, and the intercept panel that appears at the bottom when
 * an event is held.
 */
public class SseTab {

    private static final Color BG           = Color.WHITE;
    private static final Color TOOLBAR_BG   = new Color(247, 247, 250);
    private static final Color TEXT         = new Color(20, 20, 20);
    private static final Color DIM          = new Color(110, 110, 120);
    private static final Color BORDER       = new Color(218, 218, 224);
    private static final Color ACCENT       = new Color(235, 70, 12);
    private static final Color INTERCEPT_ON = new Color(220, 55, 15);

    private static final DateTimeFormatter CSV_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final SseEventStore     store;
    private final SseInterceptQueue interceptQueue;
    private final SseRelayServer    relay;
    private final MontoyaApi        api;

    private final JComponent    root;
    private final JTabbedPane   tabbedPane;
    private final Map<String, Integer> urlToTabIndex = new HashMap<>();

    private final JButton       interceptBtn;
    private boolean             interceptOn = false;

    private final JLabel        statusLabel;
    private final SseInterceptPanel interceptPanel;

    public SseTab(SseEventStore store, SseInterceptQueue interceptQueue,
                  SseRelayServer relay, MontoyaApi api) {
        this.store          = store;
        this.interceptQueue = interceptQueue;
        this.relay          = relay;
        this.api            = api;

        tabbedPane     = new JTabbedPane();
        interceptPanel = new SseInterceptPanel();
        interceptBtn   = new JButton();
        statusLabel    = new JLabel("0 events");

        // Toolbar widgets
        JLabel title = new JLabel("SSE Parser");
        title.setForeground(ACCENT);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        title.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 10));

        interceptBtn.setPreferredSize(new Dimension(112, 28));
        interceptBtn.setFocusPainted(false);
        interceptBtn.setContentAreaFilled(true);
        interceptBtn.setOpaque(true);
        interceptBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        interceptBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        interceptBtn.addActionListener(e -> {
            interceptOn = !interceptOn;
            interceptQueue.setEnabled(interceptOn);
            paintInterceptButton();
            updateInterceptPanel();
        });
        paintInterceptButton();

        JButton clearBtn = toolBtn("Clear");
        clearBtn.addActionListener(e -> {
            store.clear();
            SwingUtilities.invokeLater(() -> {
                for (int i = tabbedPane.getTabCount() - 1; i >= 1; i--) {
                    java.awt.Component c = tabbedPane.getComponentAt(i);
                    if (c instanceof SseEndpointPanel sep) sep.dispose();
                    tabbedPane.removeTabAt(i);
                }
                urlToTabIndex.clear();
            });
        });

        JButton exportBtn = toolBtn("Export ▾");
        JPopupMenu exportMenu = new JPopupMenu();
        JMenuItem jsonItem = new JMenuItem("Export as JSON");
        JMenuItem csvItem  = new JMenuItem("Export as CSV");
        jsonItem.addActionListener(e -> exportJson());
        csvItem.addActionListener(e -> exportCsv());
        exportMenu.add(jsonItem);
        exportMenu.add(csvItem);
        exportBtn.addActionListener(e ->
            exportMenu.show(exportBtn, 0, exportBtn.getHeight()));

        statusLabel.setForeground(DIM);
        statusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        // Proxy field: apply on Enter
        JTextField proxyField = new JTextField(14);
        proxyField.setBackground(Color.WHITE);
        proxyField.setForeground(TEXT);
        proxyField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        proxyField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        proxyField.setToolTipText("Upstream proxy host:port. Leave blank for direct. Press Enter to apply.");
        proxyField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) applyProxy(proxyField);
            }
        });
        proxyField.addActionListener(e -> applyProxy(proxyField));

        JLabel proxyLabel = new JLabel("Proxy");
        proxyLabel.setForeground(DIM);
        proxyLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        proxyLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));

        // Toolbar assembly
        JPanel leftTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftTools.setBackground(TOOLBAR_BG);
        leftTools.add(title);
        leftTools.add(interceptBtn);
        leftTools.add(clearBtn);
        leftTools.add(exportBtn);

        JPanel rightTools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightTools.setBackground(TOOLBAR_BG);
        rightTools.add(proxyLabel);
        rightTools.add(proxyField);
        rightTools.add(statusLabel);

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(TOOLBAR_BG);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        toolbar.add(leftTools,  BorderLayout.WEST);
        toolbar.add(rightTools, BorderLayout.EAST);

        // Tabs
        tabbedPane.setBackground(BG);
        tabbedPane.setForeground(TEXT);
        tabbedPane.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        tabbedPane.addTab("All", new SseEndpointPanel(store, null, interceptQueue, api, relay));

        interceptQueue.addListener(() ->
            SwingUtilities.invokeLater(this::updateInterceptPanel));

        store.addListener(() ->
            SwingUtilities.invokeLater(this::onStoreChanged));

        // Root layout
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(BG);
        center.add(tabbedPane,     BorderLayout.CENTER);
        center.add(interceptPanel, BorderLayout.SOUTH);

        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBackground(BG);
        rootPanel.add(toolbar, BorderLayout.NORTH);
        rootPanel.add(center,  BorderLayout.CENTER);
        this.root = rootPanel;
    }

    public JComponent getComponent() {
        return root;
    }

    private void paintInterceptButton() {
        if (interceptOn) {
            interceptBtn.setText("Intercept ON");
            interceptBtn.setBackground(INTERCEPT_ON);
            interceptBtn.setForeground(Color.WHITE);
            interceptBtn.setBorder(BorderFactory.createLineBorder(new Color(170, 35, 0)));
        } else {
            interceptBtn.setText("Intercept OFF");
            interceptBtn.setBackground(Color.WHITE);
            interceptBtn.setForeground(TEXT);
            interceptBtn.setBorder(BorderFactory.createLineBorder(BORDER));
        }
    }

    private void applyProxy(JTextField field) {
        String txt = field.getText().trim();
        try {
            relay.setUpstreamProxy(txt.isEmpty() ? null : txt);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(root,
                "Proxy error: " + ex.getMessage(),
                "SSE Parser", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onStoreChanged() {
        int count = store.getEvents().size();
        statusLabel.setText(count + (count == 1 ? " event" : " events"));

        for (String url : store.getUrls()) {
            if (!urlToTabIndex.containsKey(url)) {
                SseEndpointPanel panel = new SseEndpointPanel(store, url, interceptQueue, api, relay);
                String title = tabTitle(url);
                tabbedPane.addTab(title, panel);
                int idx = tabbedPane.getTabCount() - 1;
                urlToTabIndex.put(url, idx);
                tabbedPane.setToolTipTextAt(idx, url);
            }
        }
    }

    private void updateInterceptPanel() {
        PendingIntercept p = interceptQueue.isEnabled() ? interceptQueue.current() : null;
        if (p != null) {
            interceptPanel.setPending(p);
        } else {
            interceptPanel.clearPending();
        }
    }

    // Export ---------------------------------------------------------------

    private void exportJson() {
        List<SseEvent> events = store.getEvents();
        if (events.isEmpty()) { info("Nothing to export yet."); return; }

        File file = chooseFile("sse-events.json");
        if (file == null) return;

        try (PrintWriter pw = new PrintWriter(file, "UTF-8")) {
            pw.println("[");
            for (int i = 0; i < events.size(); i++) {
                SseEvent e = events.get(i);
                pw.print("  {");
                pw.print("\"seq\":"       + e.sequenceNum + ",");
                pw.print("\"url\":"       + jsonStr(e.url) + ",");
                pw.print("\"event\":"     + jsonStr(e.event) + ",");
                pw.print("\"id\":"        + jsonStr(e.id) + ",");
                pw.print("\"deltaMs\":"   + e.deltaMs + ",");
                pw.print("\"reconnect\":" + e.isReconnect + ",");
                pw.print("\"timestamp\":" + jsonStr(e.timestamp.format(CSV_FMT)) + ",");
                pw.print("\"data\":"      + jsonStr(e.data));
                pw.print("}");
                if (i < events.size() - 1) pw.print(",");
                pw.println();
            }
            pw.println("]");
            info("Exported " + events.size() + " events to " + file.getName());
        } catch (Exception ex) {
            error("Export failed: " + ex.getMessage());
        }
    }

    private void exportCsv() {
        List<SseEvent> events = store.getEvents();
        if (events.isEmpty()) { info("Nothing to export yet."); return; }

        File file = chooseFile("sse-events.csv");
        if (file == null) return;

        try (PrintWriter pw = new PrintWriter(file, "UTF-8")) {
            pw.println("Seq,Time,URL,Event,ID,DeltaMs,Reconnect,Data");
            for (SseEvent e : events) {
                pw.printf("%d,%s,%s,%s,%s,%s,%b,%s%n",
                    e.sequenceNum,
                    csvCell(e.timestamp.format(CSV_FMT)),
                    csvCell(e.url),
                    csvCell(e.event),
                    csvCell(e.id),
                    e.deltaMs < 0 ? "" : String.valueOf(e.deltaMs),
                    e.isReconnect,
                    csvCell(e.data));
            }
            info("Exported " + events.size() + " events to " + file.getName());
        } catch (Exception ex) {
            error("Export failed: " + ex.getMessage());
        }
    }

    // Helpers --------------------------------------------------------------

    private static String tabTitle(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            String s = (host != null ? host : url) + (path == null || path.isEmpty() ? "" : path);
            return s.length() > 28 ? s.substring(0, 26) + "…" : s;
        } catch (Exception e) {
            return url.length() > 28 ? url.substring(0, 26) + "…" : url;
        }
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r")
                        .replace("\t", "\\t") + "\"";
    }

    private static String csvCell(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private File chooseFile(String defaultName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(defaultName));
        int result = chooser.showSaveDialog(root);
        return result == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private static JButton toolBtn(String text) {
        JButton b = new JButton(text);
        b.setBackground(Color.WHITE);
        b.setForeground(TEXT);
        b.setFocusPainted(false);
        b.setContentAreaFilled(true);
        b.setOpaque(true);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        b.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(root, msg, "SSE Parser", JOptionPane.INFORMATION_MESSAGE);
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(root, msg, "SSE Parser", JOptionPane.ERROR_MESSAGE);
    }
}
