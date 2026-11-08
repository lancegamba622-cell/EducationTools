package com.mycompany.educationaltools;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Locale;

import static com.mycompany.educationaltools.UiKit.*;

/** Teacher view of ONE student in a hosted quiz: every answer, the time spent, and the away/left history. */
class StudentDetailDialog extends JDialog {

    StudentDetailDialog(Window owner, QuizSession s, Participant p) {
        super(owner, "Student detail — " + p.displayName, Dialog.ModalityType.APPLICATION_MODAL);
        setSize(900, 520);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(10, 10));
        getContentPane().setBackground(Theme.bg);

        // ---- summary line ----
        String away = p.awayCount > 0 ? p.awayCount + "× switched away (" + p.awaySeconds + "s total)" : "never switched away";
        String left = p.leftCount > 0 ? "left " + p.leftCount + "×" : "never left";
        String rejoin = p.rejoinCount > 0 ? ", rejoined " + p.rejoinCount + "×" : "";
        JLabel summary = new JLabel("<html><b>" + escapeHtml(p.displayName) + "</b> (" + escapeHtml(p.username) + ")  •  "
                + escapeHtml(p.status) + "  •  " + p.score + " pts  •  " + p.correct + "/" + p.total + " correct<br>"
                + away + "  •  " + left + rejoin + "</html>");
        summary.setFont(f(Font.PLAIN, 13));
        summary.setForeground(Theme.textDark);
        summary.setBorder(new EmptyBorder(12, 16, 0, 16));

        // ---- one row per question ----
        DefaultTableModel model = new DefaultTableModel(
                new String[]{"#", "Question", "Their answer", "Correct answer", "Result", "Time (s)"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        String results = p.results == null ? "" : p.results;
        String choices = p.choices == null ? "" : p.choices;
        double[] times = ClassReport.parseTimes(p.times);

        for (int i = 0; i < s.questions.size(); i++) {
            Question q = s.questions.get(i);
            boolean answered = results.length() > i;
            String theirs = "—";
            String result = "—";
            if (answered) {
                result = results.charAt(i) == '1' ? "✔ Correct" : "✘ Wrong";
                char c = i < choices.length() ? choices.charAt(i) : '?';
                if (c == '0') {
                    theirs = "No answer (time up)";
                } else if (c >= '1' && c - '1' < q.options.length) {
                    theirs = q.options[c - '1'];
                }
            }
            String time = "—";
            if (i < times.length && times[i] >= 0) {
                time = String.format(Locale.US, "%.1f", times[i]);
                char c = i < choices.length() ? choices.charAt(i) : '?';
                if (ClassReport.isFast(q, times[i], c)) time += "  ⚡ very fast";
            }
            model.addRow(new Object[]{i + 1, q.text, theirs, q.options[q.correctOption - 1], result, time});
        }

        JTable table = new JTable(model);
        table.setRowHeight(32);
        table.setFont(f(Font.PLAIN, 13));
        styleTable(table);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(330);
        table.getColumnModel().getColumn(4).setMaxWidth(100);
        table.getColumnModel().getColumn(5).setPreferredWidth(120);
        table.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                if (!sel) {
                    String text = String.valueOf(v);
                    comp.setForeground(text.contains("Correct") ? new Color(22, 163, 74)
                            : text.contains("Wrong") ? new Color(220, 38, 38) : t.getForeground());
                }
                return comp;
            }
        });

        JScrollPane sp = scrollOf(table);
        sp.setBorder(new EmptyBorder(0, 14, 0, 14));

        RoundedButton btnClose = new RoundedButton("Close", new Color(100, 116, 139), new Color(71, 85, 105), 12);
        btnClose.setPreferredSize(new Dimension(110, 34));
        btnClose.addActionListener(e -> dispose());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 8));
        bar.setOpaque(false);
        bar.add(btnClose);

        add(summary, BorderLayout.NORTH);
        add(sp, BorderLayout.CENTER);
        add(bar, BorderLayout.SOUTH);
    }
}