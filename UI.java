package com.mycompany.educationaltools;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/*
 * ============================================================================
 *  UI COMPONENTS - the colour theme (light / dark) and the custom Swing widgets.
 * ============================================================================
 */

/** Light / dark palette. Call Theme.apply(...) and then rebuild the screens so they pick up the new colours. */
final class Theme {
    private static final String[] TEXT_KEYS = {
        "Label.foreground", "OptionPane.messageForeground", "CheckBox.foreground", "TextField.foreground",
        "TextField.caretForeground", "PasswordField.foreground", "PasswordField.caretForeground",
        "FormattedTextField.foreground", "FormattedTextField.caretForeground", "Spinner.foreground",
        "ComboBox.foreground", "List.foreground", "Table.foreground"
    };
    private static final String[] PANEL_KEYS = {
        "Panel.background", "OptionPane.background", "CheckBox.background", "ScrollPane.background"
    };
    private static final String[] INPUT_KEYS = {
        "TextField.background", "PasswordField.background", "FormattedTextField.background", "Spinner.background",
        "ComboBox.background", "List.background", "Viewport.background", "Table.background"
    };

    static boolean dark;
    static Color bg;
    static Color card;
    static Color textDark;
    static Color textMuted;
    static Color tileHover;
    static Color border;
    static Color tableBg;
    static Color tableGrid;
    static Color answerBg;
    static Color answerFg;

    static {
        apply(false);
    }

    private Theme() { }

    static void apply(boolean darkMode) {
        dark = darkMode;
        if (darkMode) {
            bg = new Color(15, 23, 42);
            card = new Color(30, 41, 59);
            textDark = new Color(241, 245, 249);
            textMuted = new Color(148, 163, 184);
            tileHover = new Color(51, 65, 85);
            border = new Color(71, 85, 105);
            tableBg = new Color(30, 41, 59);
            tableGrid = new Color(51, 65, 85);
            answerBg = new Color(20, 83, 45);
            answerFg = new Color(187, 247, 208);
        } else {
            bg = new Color(243, 244, 251);
            card = Color.WHITE;
            textDark = new Color(30, 41, 59);
            textMuted = new Color(100, 116, 139);
            tileHover = new Color(238, 242, 255);
            border = new Color(203, 213, 225);
            tableBg = Color.WHITE;
            tableGrid = new Color(226, 232, 240);
            answerBg = new Color(220, 252, 231);
            answerFg = new Color(22, 101, 52);
        }
        installUiDefaults();
    }

    /** Makes dialogs, message boxes and inputs created from now on follow the theme (null = back to the default). */
    private static void installUiDefaults() {
        Color input = dark ? new Color(51, 65, 85) : null;
        for (String key : TEXT_KEYS) put(key, dark ? textDark : null);
        for (String key : PANEL_KEYS) put(key, dark ? bg : null);
        for (String key : INPUT_KEYS) put(key, dark ? (key.equals("Table.background") ? tableBg : input) : null);
        put("Table.gridColor", dark ? tableGrid : null);
    }

    private static void put(String key, Color color) {
        UIManager.put(key, color == null ? null : new ColorUIResource(color));
    }
}

class AnalyticsChartPanel extends JPanel {
    private final int correctCount;
    private final int incorrectCount;

    public AnalyticsChartPanel(int correctCount, int incorrectCount) {
        this.correctCount = correctCount;
        this.incorrectCount = incorrectCount;
        setBackground(Theme.card);
        setPreferredSize(new Dimension(400, 200));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int total = correctCount + incorrectCount;
        if (total == 0) {
            g2d.setColor(Color.GRAY);
            g2d.setFont(new Font("Segoe UI", Font.BOLD, 14));
            g2d.drawString("No quiz attempts recorded yet.", 100, 100);
            return;
        }

        int height = getHeight();
        int barWidth = 80;
        int maxBarHeight = 120;
        int correctBarHeight = (int) (((double) correctCount / total) * maxBarHeight);
        int incorrectBarHeight = (int) (((double) incorrectCount / total) * maxBarHeight);

        g2d.setColor(new Color(34, 197, 94));
        g2d.fillRect(100, height - 50 - correctBarHeight, barWidth, correctBarHeight);
        g2d.setColor(Theme.textDark);
        g2d.drawString("Correct (" + correctCount + ")", 90, height - 30);

        g2d.setColor(new Color(239, 68, 68));
        g2d.fillRect(240, height - 50 - incorrectBarHeight, barWidth, incorrectBarHeight);
        g2d.setColor(Theme.textDark);
        g2d.drawString("Incorrect (" + incorrectCount + ")", 230, height - 30);

        g2d.setFont(new Font("Segoe UI", Font.BOLD, 14));
        g2d.drawString("Performance Ratio Bar Chart", 110, 25);
    }
}

class ModernGradientPanel extends JPanel {
    private final Color colorStart;
    private final Color colorEnd;

    public ModernGradientPanel(Color colorStart, Color colorEnd) {
        this.colorStart = colorStart;
        this.colorEnd = colorEnd;
        setLayout(new BorderLayout());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setPaint(new GradientPaint(0, 0, colorStart, getWidth(), getHeight(), colorEnd));
        g2d.fillRect(0, 0, getWidth(), getHeight());
    }
}

class RoundedButton extends JButton {
    private Color normalColor;
    private Color hoverColor;
    private Color pressedColor;
    private final int cornerRadius;

    public RoundedButton(String text, Color normalColor, Color hoverColor, int cornerRadius) {
        super(text);
        this.cornerRadius = cornerRadius;

        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(false);
        setFont(new Font("Segoe UI", Font.BOLD, 13));
        setForeground(Color.WHITE);
        setCursor(new Cursor(Cursor.HAND_CURSOR));

        // The listeners read the fields (not the constructor arguments) so setColors() takes effect.
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { if (isEnabled()) setBackground(RoundedButton.this.hoverColor); }
            @Override public void mouseExited(MouseEvent e) { if (isEnabled()) setBackground(RoundedButton.this.normalColor); }
            @Override public void mousePressed(MouseEvent e) { if (isEnabled()) setBackground(RoundedButton.this.pressedColor); }
            @Override public void mouseReleased(MouseEvent e) { if (isEnabled()) setBackground(RoundedButton.this.hoverColor); }
        });
        setColors(normalColor, hoverColor);
    }

    /** Changes the resting and hover colours (used when the same button is reused for different answers). */
    void setColors(Color normal, Color hover) {
        this.normalColor = normal;
        this.hoverColor = hover;
        this.pressedColor = hover.darker();
        setBackground(normal);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(isEnabled() ? getBackground() : new Color(148, 163, 184));
        g2d.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), cornerRadius, cornerRadius));
        g2d.dispose();
        super.paintComponent(g);
    }
}

/** A rounded card with an optional soft drop shadow (leave ~6px of border space on the right/bottom when shadow=true). */
class RoundedPanel extends JPanel {
    private final int radius;
    private final boolean shadow;

    RoundedPanel(int radius, Color background, boolean shadow) {
        this.radius = radius;
        this.shadow = shadow;
        setOpaque(false);
        setBackground(background);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth() - (shadow ? 6 : 0);
        int h = getHeight() - (shadow ? 7 : 0);
        if (shadow) {
            for (int i = 0; i < 6; i++) {
                g2.setColor(new Color(0, 0, 0, Theme.dark ? 22 : 9));
                g2.fill(new RoundRectangle2D.Float(i, i + 1, w, h, radius, radius));
            }
        }
        g2.setColor(getBackground());
        g2.fill(new RoundRectangle2D.Float(0, 0, w, h, radius, radius));
        g2.dispose();
        super.paintComponent(g);
    }
}

/** Clickable dashboard tile: icon, title and a one-line description. */
class ActionTile extends RoundedPanel {
    ActionTile(String icon, String title, String subtitle, Color accent, boolean highlight, Runnable action) {
        super(20, highlight ? accent : Theme.card, true);
        final Color baseColor = getBackground();
        final Color hoverColor = highlight ? accent.darker() : Theme.tileHover;

        setLayout(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(14, 18, 20, 24));
        setCursor(new Cursor(Cursor.HAND_CURSOR));

        RoundedPanel iconBox = new RoundedPanel(16,
                highlight ? new Color(255, 255, 255, 60) : new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 36),
                false);
        iconBox.setLayout(new GridBagLayout());
        iconBox.setPreferredSize(new Dimension(48, 48));
        JLabel lblIcon = new JLabel(icon);
        lblIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 24));
        iconBox.add(lblIcon);

        JPanel iconRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        iconRow.setOpaque(false);
        iconRow.add(iconBox);

        JLabel lblTitle = new JLabel(title);
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 15));
        lblTitle.setForeground(highlight ? Color.WHITE : Theme.textDark);
        JLabel lblSub = new JLabel(subtitle);
        lblSub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblSub.setForeground(highlight ? new Color(224, 231, 255) : Theme.textMuted);

        JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
        text.setOpaque(false);
        text.add(lblTitle);
        text.add(lblSub);

        add(iconRow, BorderLayout.NORTH);
        add(text, BorderLayout.CENTER);

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { setBackground(hoverColor); repaint(); }
            @Override public void mouseExited(MouseEvent e) { setBackground(baseColor); repaint(); }
            @Override public void mouseClicked(MouseEvent e) { action.run(); }
        });
    }
}

/** Colours the "Status" column of the teacher monitor. */
class StatusCellRenderer extends DefaultTableCellRenderer {
    StatusCellRenderer() { setHorizontalAlignment(CENTER); }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        String s = value == null ? "" : value.toString();
        if (!isSelected) {
            if (s.startsWith("⚠")) { c.setBackground(new Color(254, 226, 226)); c.setForeground(new Color(153, 27, 27)); }
            else if (s.startsWith("▶")) { c.setBackground(new Color(220, 252, 231)); c.setForeground(new Color(22, 101, 52)); }
            else if (s.startsWith("✅")) { c.setBackground(new Color(224, 231, 255)); c.setForeground(new Color(55, 48, 163)); }
            else if (s.startsWith("⏳")) { c.setBackground(new Color(254, 243, 199)); c.setForeground(new Color(146, 64, 14)); }
            else { c.setBackground(Theme.tableBg); c.setForeground(Theme.textDark); }
        }
        c.setFont(c.getFont().deriveFont(Font.BOLD));
        return c;
    }
}

/** Highlights any cell that is not "—" (used for the "Switched Away" and "Left Quiz" columns). */
class FlagCellRenderer extends DefaultTableCellRenderer {
    private final Color flagBackground;
    private final Color flagForeground;

    FlagCellRenderer(Color flagBackground, Color flagForeground) {
        this.flagBackground = flagBackground;
        this.flagForeground = flagForeground;
        setHorizontalAlignment(CENTER);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        String s = value == null ? "" : value.toString();
        if (!isSelected) {
            boolean flagged = !s.equals("—");
            c.setBackground(flagged ? flagBackground : Theme.tableBg);
            c.setForeground(flagged ? flagForeground : Theme.textMuted);
        }
        c.setFont(c.getFont().deriveFont(s.equals("—") ? Font.PLAIN : Font.BOLD));
        return c;
    }
}

/** Draws a QuestionStat as a coloured percentage bar (red = weak, amber = so-so, green = strong). */
class PercentBarRenderer extends JProgressBar implements TableCellRenderer {
    PercentBarRenderer() {
        super(0, 100);
        setStringPainted(true);
        setBorderPainted(false);
        setFont(new Font("Segoe UI", Font.BOLD, 12));
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        QuestionStat st = (value instanceof QuestionStat) ? (QuestionStat) value : null;
        int pct = (st == null) ? -1 : st.percent();
        setBackground(Theme.tableGrid);
        if (pct < 0) {
            setValue(0);
            setString("no answers yet");
            setForeground(new Color(148, 163, 184));
        } else {
            setValue(pct);
            setString(pct + "%  (" + st.correct + "/" + st.answered + ")");
            setForeground(pct < 50 ? new Color(239, 68, 68) : pct < 75 ? new Color(234, 179, 8) : new Color(34, 197, 94));
        }
        return this;
    }
}