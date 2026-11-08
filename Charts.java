package com.mycompany.educationaltools;

import javax.swing.JPanel;
import java.awt.*;

/*
 * ============================================================================
 *  CHARTS - a simple vertical bar chart (used by the projector view and the
 *  class report histogram).
 * ============================================================================
 */
class BarChartPanel extends JPanel {
    private String[] labels = new String[0];
    private int[] values = new int[0];
    private Color[] colors = new Color[0];
    private Font labelFont = new Font("Segoe UI", Font.BOLD, 13);
    private Font valueFont = new Font("Segoe UI", Font.BOLD, 18);
    private Color textColor = null; // null = follow the theme

    BarChartPanel() {
        setOpaque(false);
    }

    void setData(String[] labels, int[] values, Color[] colors) {
        this.labels = labels;
        this.values = values;
        this.colors = colors;
        repaint();
    }

    void setFonts(Font labelFont, Font valueFont) {
        this.labelFont = labelFont;
        this.valueFont = valueFont;
    }

    void setTextColor(Color color) {
        this.textColor = color;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (values.length == 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Color fg = (textColor != null) ? textColor : Theme.textDark;
        int n = values.length;
        int max = 1;
        for (int v : values) max = Math.max(max, v);

        FontMetrics lfm = g2.getFontMetrics(labelFont);
        FontMetrics vfm = g2.getFontMetrics(valueFont);
        int labelH = lfm.getHeight() + 8;
        int valueH = vfm.getHeight() + 4;
        int gap = 16;
        int slot = (getWidth() - gap * (n + 1)) / n;
        int chartH = getHeight() - labelH - valueH - 8;
        int baseY = valueH + chartH;

        for (int i = 0; i < n; i++) {
            int x = gap + i * (slot + gap);
            int barH = (int) Math.round((double) values[i] / max * chartH);
            barH = (values[i] > 0) ? Math.max(barH, 6) : 3;
            Color c = (i < colors.length && colors[i] != null) ? colors[i] : new Color(79, 70, 229);

            g2.setColor(c);
            g2.fillRoundRect(x, baseY - barH, slot, barH, 12, 12);

            g2.setFont(valueFont);
            g2.setColor(fg);
            String v = String.valueOf(values[i]);
            g2.drawString(v, x + (slot - vfm.stringWidth(v)) / 2, baseY - barH - 6);

            g2.setFont(labelFont);
            String label = (i < labels.length) ? labels[i] : "";
            while (label.length() > 3 && lfm.stringWidth(label) > slot + gap - 4) {
                label = label.substring(0, label.length() - 2) + "…";
            }
            g2.drawString(label, x + (slot - lfm.stringWidth(label)) / 2, baseY + lfm.getAscent() + 6);
        }
        g2.dispose();
    }
}