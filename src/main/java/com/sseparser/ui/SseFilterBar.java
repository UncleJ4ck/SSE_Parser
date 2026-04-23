package com.sseparser.ui;

import com.sseparser.SseEvent;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One search field that filters the events table. Matches case-insensitive
 * substring across URL, event, id, and data. If the text parses as a regex
 * it's also applied, so typing {@code ^error} or {@code user.*bob} works.
 */
public class SseFilterBar extends JPanel {

    private static final Color BG      = new Color(249, 249, 251);
    private static final Color FG      = new Color(20, 20, 20);
    private static final Color HINT    = new Color(160, 160, 170);
    private static final Color BORDER  = new Color(222, 222, 228);
    private static final Color ERR_BG  = new Color(255, 223, 223);

    private final JTextField searchField;

    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public SseFilterBar() {
        super(new BorderLayout(8, 0));
        setBackground(BG);
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        searchField = createSearchField();

        JButton clearBtn = new JButton("Clear");
        clearBtn.setBackground(Color.WHITE);
        clearBtn.setForeground(FG);
        clearBtn.setBorder(BorderFactory.createLineBorder(BORDER));
        clearBtn.setFocusPainted(false);
        clearBtn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        clearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearBtn.addActionListener(e -> searchField.setText(""));

        add(searchField, BorderLayout.CENTER);
        add(clearBtn,    BorderLayout.EAST);
    }

    /**
     * Predicate that matches events whose combined (url, event, id, data) text
     * contains the search string, or matches it as a regex.
     */
    public Predicate<SseEvent> getPredicate() {
        String text = searchField.getText().trim();
        if (text.isEmpty()) return e -> true;

        String lower = text.toLowerCase();
        Pattern regex;
        try {
            regex = Pattern.compile(text, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        } catch (PatternSyntaxException ex) {
            regex = null;
        }
        final Pattern r = regex;

        return e -> {
            String all = e.url + "\n" + e.event + "\n" + e.id + "\n" + e.data;
            if (all.toLowerCase().contains(lower)) return true;
            return r != null && r.matcher(all).find();
        };
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private JTextField createSearchField() {
        JTextField f = new JTextField() {
            @Override
            public void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && !isFocusOwner()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(
                        RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(HINT);
                    g2.setFont(getFont().deriveFont(Font.ITALIC));
                    Insets ins = getInsets();
                    g2.drawString("Search events", ins.left + 2, getHeight() - ins.bottom - 5);
                    g2.dispose();
                }
            }
        };
        f.setBackground(Color.WHITE);
        f.setForeground(FG);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        f.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        f.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { fire(); }
            public void removeUpdate(DocumentEvent e)  { fire(); }
            public void changedUpdate(DocumentEvent e) { fire(); }
        });

        return f;
    }

    private void fire() {
        if (SwingUtilities.isEventDispatchThread()) {
            changeListeners.forEach(Runnable::run);
        } else {
            SwingUtilities.invokeLater(() -> changeListeners.forEach(Runnable::run));
        }
    }
}
