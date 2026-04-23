package com.sseparser.ui;

import com.sseparser.SseInterceptQueue;
import com.sseparser.SseInterceptQueue.PendingIntercept;

import javax.swing.*;
import java.awt.*;

/**
 * Panel displayed at the bottom of the main tab whenever intercept mode is
 * active and there is a pending event awaiting a decision.
 *
 * <h3>Available actions</h3>
 * <ul>
 *   <li><b>Forward</b>, sends the original, unmodified event data to the browser.</li>
 *   <li><b>Forward Modified</b>, sends whatever is currently in the editable
 *       text area, allowing the analyst to change field values before delivery.</li>
 *   <li><b>Drop</b>, discards the event entirely; it is never written to the
 *       browser stream (though it is still recorded in the event store).</li>
 * </ul>
 *
 * <p>Call {@link #setPending(PendingIntercept)} on the EDT when a new intercept
 * arrives, and {@link #clearPending()} when it is resolved or intercept mode is
 * disabled.
 */
public class SseInterceptPanel extends JPanel {


    private static final Color BG         = new Color(252, 250, 245);
    private static final Color BG_AREA    = Color.WHITE;
    private static final Color FG         = new Color(20, 20, 20);
    private static final Color FG_DIM     = new Color(110, 110, 120);
    private static final Color ACCENT     = new Color(235, 70, 12);    // Burp orange
    private static final Color BTN_FWD    = new Color(30, 140, 60);
    private static final Color BTN_DROP   = new Color(180, 40, 40);
    private static final Color BTN_MOD    = new Color(50, 90, 200);
    private static final Color BORDER_CLR = new Color(210, 205, 195);


    private final JLabel    headerLabel;
    private final JLabel    metaLabel;
    private final JTextArea dataArea;
    private final JButton   forwardBtn;
    private final JButton   forwardModBtn;
    private final JButton   dropBtn;

    /** The current pending intercept; {@code null} when none is active. */
    private volatile PendingIntercept currentPending = null;


    public SseInterceptPanel() {
        super(new BorderLayout(0, 0));
        setBackground(BG);
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(2, 0, 0, 0, ACCENT),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));

        headerLabel = new JLabel("  INTERCEPT");
        headerLabel.setForeground(ACCENT);
        headerLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));

        metaLabel = new JLabel(" ");
        metaLabel.setForeground(FG_DIM);
        metaLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        JPanel topRow = new JPanel(new BorderLayout(6, 0));
        topRow.setBackground(BG);
        topRow.add(headerLabel, BorderLayout.WEST);
        topRow.add(metaLabel,   BorderLayout.CENTER);

        dataArea = new JTextArea(5, 80);
        dataArea.setBackground(BG_AREA);
        dataArea.setForeground(FG);
        dataArea.setCaretColor(ACCENT);
        dataArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        dataArea.setLineWrap(true);
        dataArea.setWrapStyleWord(false);
        dataArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_CLR),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));

        JScrollPane areaScroll = new JScrollPane(dataArea);
        areaScroll.setBorder(BorderFactory.createEmptyBorder());
        areaScroll.setBackground(BG);
        areaScroll.getViewport().setBackground(BG_AREA);

        forwardBtn    = buildButton("Forward",          BTN_FWD);
        forwardModBtn = buildButton("Forward Modified", BTN_MOD);
        dropBtn       = buildButton("Drop",             BTN_DROP);

        forwardBtn.addActionListener(e -> {
            PendingIntercept p = currentPending;
            if (p != null && !p.isDone()) p.forward();
        });
        forwardModBtn.addActionListener(e -> {
            PendingIntercept p = currentPending;
            if (p != null && !p.isDone()) p.forward(dataArea.getText());
        });
        dropBtn.addActionListener(e -> {
            PendingIntercept p = currentPending;
            if (p != null && !p.isDone()) p.drop();
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        btnRow.setBackground(BG);
        btnRow.add(forwardBtn);
        btnRow.add(forwardModBtn);
        btnRow.add(dropBtn);

        add(topRow,     BorderLayout.NORTH);
        add(areaScroll, BorderLayout.CENTER);
        add(btnRow,     BorderLayout.SOUTH);

        setVisible(false);
    }


    /**
     * Populates the panel with the details of the given pending intercept and
     * makes the panel visible.  Must be called on the EDT.
     *
     * @param p the newly pending intercept, or {@code null} to clear
     */
    public void setPending(PendingIntercept p) {
        currentPending = p;
        if (p == null) {
            clearPending();
            return;
        }
        headerLabel.setText("  INTERCEPT, event held");
        metaLabel.setText(String.format(
            "  URL: %s   Event: %s   ID: %s   Seq: #%d%s",
            p.event.url,
            p.event.event,
            p.event.id.isEmpty() ? "(none)" : p.event.id,
            p.event.sequenceNum,
            p.event.isReconnect ? "   [RECONNECT]" : ""
        ));
        dataArea.setText(p.event.data);
        dataArea.setCaretPosition(0);
        setVisible(true);
        revalidate();
        repaint();
    }

    /**
     * Clears the panel and hides it.  Must be called on the EDT.
     */
    public void clearPending() {
        currentPending = null;
        headerLabel.setText("  INTERCEPT");
        metaLabel.setText(" ");
        dataArea.setText("");
        setVisible(false);
    }


    private static JButton buildButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(new Color(230, 230, 230));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        btn.setOpaque(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }
}
