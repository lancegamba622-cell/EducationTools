package com.mycompany.educationaltools;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.JTableHeader;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;

/*
 * ============================================================================
 *  UI KIT - small helpers shared by the main window and every dialog.
 *  (No state, no screens: just formatting and styling.)
 * ============================================================================
 */
final class UiKit {
    private UiKit() { }

    static final Color NAVY = new Color(30, 27, 75);

    /** Shapes shown on the answer buttons so colour is never the only clue (colour-blind friendly). */
    static final String[] SHAPES = {"▲", "◆", "●", "■"};

    static Font f(int style, int size) {
        return new Font("Segoe UI", style, size);
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String difficultyLabel(int diff) {
        return (diff == 1) ? "Easy" : (diff == 2) ? "Medium" : "Hard";
    }

    static String typeLabel(Question q) {
        return q.isTrueFalse() ? "True/False" : "Multiple choice";
    }

    static String formatCode(String code) {
        return (code != null && code.length() == 6) ? code.substring(0, 3) + " " + code.substring(3) : code;
    }

    static String ago(long timestamp) {
        if (timestamp <= 0) return "—";
        long s = Math.max(0, (System.currentTimeMillis() - timestamp) / 1000);
        if (s < 5) return "just now";
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h ago";
    }

    /**
     * Escapes a text for a CSV cell. Cells that start with = + - or @ get a leading apostrophe,
     * so Excel never runs them as a formula (CSV injection).
     */
    static String csvEscape(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    static JLabel themedLabel(String text, int style, int size) {
        JLabel l = new JLabel(text);
        l.setFont(f(style, size));
        l.setForeground(Theme.textDark);
        return l;
    }

    static JLabel chip(String text, Color bg, Color fg) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setOpaque(true);
        l.setBackground(bg);
        l.setForeground(fg);
        l.setFont(f(Font.BOLD, 12));
        l.setBorder(new EmptyBorder(5, 12, 5, 12));
        return l;
    }

    static void styleTable(JTable table) {
        table.setBackground(Theme.tableBg);
        table.setForeground(Theme.textDark);
        table.setGridColor(Theme.tableGrid);
        table.setFillsViewportHeight(true);
        JTableHeader header = table.getTableHeader();
        header.setFont(f(Font.BOLD, 12));
        header.setBackground(NAVY);
        header.setForeground(Color.WHITE);
        header.setPreferredSize(new Dimension(header.getPreferredSize().width, 32));
    }

    static JScrollPane scrollOf(JTable table) {
        JScrollPane sp = new JScrollPane(table);
        sp.getViewport().setBackground(Theme.tableBg);
        return sp;
    }

    static void bindKey(JRootPane root, int keyCode, Runnable action) {
        String name = "key-" + keyCode;
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }
}