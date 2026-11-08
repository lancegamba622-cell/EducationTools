package com.mycompany.educationaltools;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static com.mycompany.educationaltools.UiKit.*;

/** Class report for one hosted quiz: overview + histogram, hardest questions, who needs help, and soft alerts. */
class ReportDialog extends JDialog {

    ReportDialog(Window owner, QuizSession s, List<Participant> participants) {
        super(owner, "Class Report — " + s.title + " (" + formatCode(s.code) + ")", Dialog.ModalityType.APPLICATION_MODAL);
        setSize(980, 660);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(10, 10));
        getContentPane().setBackground(Theme.bg);

        ClassReport report = ClassReport.build(s, participants);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("📊 Overview", overviewTab(report));
        tabs.addTab("❓ Questions", questionsTab(report));
        tabs.addTab("🎒 Students", studentsTab(report));
        tabs.addTab("🔔 Alerts (" + report.alerts.size() + ")", alertsTab(report));

        RoundedButton btnExport = new RoundedButton("📁 Save report (CSV)", new Color(16, 185, 129), new Color(5, 150, 105), 12);
        RoundedButton btnClose = new RoundedButton("Close", new Color(100, 116, 139), new Color(71, 85, 105), 12);
        btnExport.setPreferredSize(new Dimension(190, 36));
        btnClose.setPreferredSize(new Dimension(110, 36));
        btnClose.addActionListener(e -> dispose());
        btnExport.addActionListener(e -> {
            File out = new File("session_" + s.code + "_report.csv");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
                w.write("\uFEFF"); // so Excel opens the emoji and accents correctly
                w.write(report.reportCsv());
                JOptionPane.showMessageDialog(this, "Saved to:\n" + out.getAbsolutePath(), "Report saved", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                AppSupport.log(ex);
                JOptionPane.showMessageDialog(this, "Could not save the report.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
        bar.setOpaque(false);
        bar.add(btnExport);
        bar.add(btnClose);

        JLabel note = new JLabel("<html>Alerts are hints for the teacher, not proof of cheating. Ask the student before you decide anything.</html>");
        note.setFont(f(Font.PLAIN, 11));
        note.setForeground(Theme.textMuted);
        note.setBorder(new EmptyBorder(6, 16, 0, 16));

        add(note, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(bar, BorderLayout.SOUTH);
    }

    private JPanel statBox(String value, String label) {
        RoundedPanel box = new RoundedPanel(16, Theme.card, true);
        box.setLayout(new GridLayout(2, 1));
        box.setBorder(new EmptyBorder(10, 16, 16, 20));
        JLabel v = new JLabel(value);
        v.setFont(f(Font.BOLD, 24));
        v.setForeground(Theme.textDark);
        JLabel l = new JLabel(label);
        l.setFont(f(Font.PLAIN, 12));
        l.setForeground(Theme.textMuted);
        box.add(v);
        box.add(l);
        return box;
    }

    private JPanel overviewTab(ClassReport r) {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.bg);
        root.setBorder(new EmptyBorder(14, 16, 8, 16));

        JPanel stats = new JPanel(new GridLayout(1, 5, 12, 0));
        stats.setOpaque(false);
        stats.setPreferredSize(new Dimension(100, 82));
        stats.add(statBox(String.valueOf(r.students), "Students counted"));
        stats.add(statBox(String.format(Locale.US, "%.1f", r.avgScore), "Average score (pts)"));
        stats.add(statBox(String.format(Locale.US, "%.1f", r.medianScore), "Median score (pts)"));
        stats.add(statBox(String.format(Locale.US, "%.0f%%", r.avgPercent), "Average accuracy"));
        stats.add(statBox(r.highScore + " / " + r.lowScore, "Highest / lowest"));

        BarChartPanel histogram = new BarChartPanel();
        Color[] colors = {
                new Color(239, 68, 68), new Color(249, 115, 22), new Color(234, 179, 8),
                new Color(132, 204, 22), new Color(34, 197, 94), new Color(16, 185, 129)
        };
        histogram.setData(ClassReport.BUCKETS, r.histogram, colors);

        JLabel title = themedLabel("Students by accuracy", Font.BOLD, 14);
        JPanel chartBox = new JPanel(new BorderLayout(0, 6));
        chartBox.setOpaque(false);
        chartBox.add(title, BorderLayout.NORTH);
        chartBox.add(histogram, BorderLayout.CENTER);

        StringBuilder hard = new StringBuilder();
        for (int idx : r.hardest) {
            QuestionStat st = r.questions.get(idx);
            hard.append("• Q").append(st.number).append(" (").append(st.percent()).append("% correct): ")
                .append(escapeHtml(st.question.text)).append("<br>");
        }
        JLabel lblHard = new JLabel("<html><b>Hardest questions</b><br>"
                + (hard.length() == 0 ? "No answers yet." : hard) + "</html>");
        lblHard.setFont(f(Font.PLAIN, 13));
        lblHard.setForeground(Theme.textDark);
        lblHard.setPreferredSize(new Dimension(100, 90));

        root.add(stats, BorderLayout.NORTH);
        root.add(chartBox, BorderLayout.CENTER);
        root.add(lblHard, BorderLayout.SOUTH);
        return root;
    }

    private JPanel questionsTab(ClassReport r) {
        DefaultTableModel model = new DefaultTableModel(
                new String[]{"#", "Question", "Level", "Answered", "Correct %", "Most picked wrong answer"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        for (int i = 0; i < r.questions.size(); i++) {
            QuestionStat st = r.questions.get(i);
            int w = r.topWrongOption(i);
            String wrong = (w < 0) ? "—" : SHAPES[w] + " " + st.question.options[w] + "  (" + r.optionCounts[i][w] + " students)";
            model.addRow(new Object[]{st.number, st.question.text, difficultyLabel(st.question.difficulty),
                    st.answered, st, wrong});
        }
        JTable table = new JTable(model);
        table.setRowHeight(34);
        table.setFont(f(Font.PLAIN, 13));
        styleTable(table);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(320);
        table.getColumnModel().getColumn(2).setMaxWidth(80);
        table.getColumnModel().getColumn(3).setMaxWidth(80);
        table.getColumnModel().getColumn(4).setPreferredWidth(170);
        table.getColumnModel().getColumn(4).setCellRenderer(new PercentBarRenderer());
        table.getColumnModel().getColumn(5).setPreferredWidth(240);
        return wrap(table);
    }

    private JPanel studentsTab(ClassReport r) {
        DefaultTableModel model = new DefaultTableModel(
                new String[]{"#", "Student", "Status", "Score", "Correct", "Accuracy", "Needs help", "Very fast answers"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        int rank = 1;
        for (ClassReport.StudentRow row : r.rows) {
            Participant p = row.p;
            model.addRow(new Object[]{rank++, p.displayName, p.status, p.score, p.correct + "/" + p.total,
                    row.percent + "%", row.needsHelp ? "🆘 yes" : "—", row.fastCount > 0 ? "⚡ " + row.fastCount : "—"});
        }
        JTable table = new JTable(model);
        table.setRowHeight(32);
        table.setFont(f(Font.PLAIN, 13));
        styleTable(table);
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 2; i < table.getColumnCount(); i++) table.getColumnModel().getColumn(i).setCellRenderer(center);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        return wrap(table);
    }

    private JPanel alertsTab(ClassReport r) {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.bg);
        root.setBorder(new EmptyBorder(14, 16, 8, 16));
        StringBuilder html = new StringBuilder("<html><div style='width:820px'>");
        if (r.alerts.isEmpty()) {
            html.append("No alerts. Nobody answered suspiciously fast, no identical wrong-answer patterns, and nobody is under 50%.");
        } else {
            for (String a : r.alerts) html.append(escapeHtml(a)).append("<br><br>");
        }
        html.append("</div></html>");
        JLabel l = new JLabel(html.toString());
        l.setFont(f(Font.PLAIN, 13));
        l.setForeground(Theme.textDark);
        l.setVerticalAlignment(SwingConstants.TOP);
        JScrollPane sp = new JScrollPane(l);
        sp.setBorder(null);
        sp.getViewport().setBackground(Theme.bg);
        root.add(sp, BorderLayout.CENTER);
        return root;
    }

    private JPanel wrap(JTable table) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.bg);
        p.setBorder(new EmptyBorder(10, 14, 6, 14));
        p.add(scrollOf(table), BorderLayout.CENTER);
        return p;
    }
}