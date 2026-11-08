package com.mycompany.educationaltools;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import static com.mycompany.educationaltools.UiKit.*;

/**
 * Big-screen view for the classroom projector: the current question and a live bar chart of how many students
 * picked each answer. In a teacher-paced quiz it follows the question the teacher opened.
 * The correct answer stays hidden until the teacher ticks "Reveal correct answer".
 */
class ProjectorDialog extends JDialog {
    private final String code;
    private final JLabel lblProgress = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel lblQuestion = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel lblCounts = new JLabel(" ", SwingConstants.CENTER);
    private final BarChartPanel chart = new BarChartPanel();
    private final JComboBox<String> cmbQuestion = new JComboBox<>();
    private final JCheckBox chkFollow = new JCheckBox("Follow the open question", true);
    private final JCheckBox chkReveal = new JCheckBox("Reveal correct answer", false);
    private final Timer refreshTimer;
    private int filledQuestions = -1;
    private boolean updatingCombo = false;

    private static final Color[] OPTION_COLORS = {
            new Color(79, 70, 229), new Color(219, 39, 119), new Color(13, 148, 136), new Color(234, 88, 12)
    };

    ProjectorDialog(Window owner, String code) {
        super(owner, "Projector — " + formatCode(code), Dialog.ModalityType.MODELESS);
        this.code = code;
        setSize(1000, 640);
        setLocationRelativeTo(owner);

        ModernGradientPanel bg = new ModernGradientPanel(new Color(30, 27, 75), new Color(126, 34, 206));
        bg.setLayout(new BorderLayout(0, 12));
        bg.setBorder(new EmptyBorder(18, 28, 14, 28));

        lblProgress.setFont(f(Font.BOLD, 16));
        lblProgress.setForeground(new Color(199, 210, 254));
        lblQuestion.setFont(f(Font.BOLD, 30));
        lblQuestion.setForeground(Color.WHITE);
        lblCounts.setFont(f(Font.BOLD, 16));
        lblCounts.setForeground(new Color(224, 231, 255));

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(lblProgress, BorderLayout.NORTH);
        top.add(lblQuestion, BorderLayout.CENTER);
        top.setPreferredSize(new Dimension(100, 150));

        chart.setFonts(f(Font.BOLD, 18), f(Font.BOLD, 34));
        chart.setTextColor(Color.WHITE);

        chkFollow.setOpaque(false);
        chkFollow.setForeground(Color.WHITE);
        chkFollow.setFocusable(false);
        chkReveal.setOpaque(false);
        chkReveal.setForeground(Color.WHITE);
        chkReveal.setFocusable(false);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 0));
        controls.setOpaque(false);
        controls.add(cmbQuestion);
        controls.add(chkFollow);
        controls.add(chkReveal);

        JPanel bottom = new JPanel(new GridLayout(2, 1, 0, 8));
        bottom.setOpaque(false);
        bottom.add(lblCounts);
        bottom.add(controls);

        bg.add(top, BorderLayout.NORTH);
        bg.add(chart, BorderLayout.CENTER);
        bg.add(bottom, BorderLayout.SOUTH);
        add(bg);

        cmbQuestion.addActionListener(e -> {
            if (updatingCombo) return;
            chkFollow.setSelected(false); // picking a question by hand stops following
            refresh();
        });
        chkFollow.addActionListener(e -> refresh());
        chkReveal.addActionListener(e -> refresh());

        refreshTimer = new Timer(1000, e -> refresh());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { refreshTimer.stop(); }
        });
        bindKey(getRootPane(), KeyEvent.VK_ESCAPE, this::dispose);

        refresh();
        refreshTimer.start();
    }

    private void refresh() {
        QuizSession s = DataStore.loadSession(code);
        if (s == null || s.questions.isEmpty()) {
            lblQuestion.setText("This session no longer exists.");
            return;
        }
        int total = s.questions.size();

        if (filledQuestions != total) {
            updatingCombo = true;
            cmbQuestion.removeAllItems();
            for (int i = 0; i < total; i++) cmbQuestion.addItem("Question " + (i + 1));
            updatingCombo = false;
            filledQuestions = total;
        }
        chkFollow.setVisible(s.paced);
        if (!s.paced) chkFollow.setSelected(false);

        int index = Math.max(0, cmbQuestion.getSelectedIndex());
        if (s.paced && chkFollow.isSelected()) {
            index = Math.min(s.currentIndex, total - 1);
            updatingCombo = true;
            cmbQuestion.setSelectedIndex(index);
            updatingCombo = false;
        }

        Question q = s.questions.get(index);
        List<Participant> parts = DataStore.listParticipants(code);

        int n = q.options.length;
        int[] counts = new int[n];
        int noAnswer = 0;
        int answered = 0;
        for (Participant p : parts) {
            String c = p.choices == null ? "" : p.choices;
            if (c.length() > index) {
                char ch = c.charAt(index);
                if (ch >= '1' && ch - '1' < n) {
                    counts[ch - '1']++;
                    answered++;
                } else if (ch == '0') {
                    noAnswer++;
                }
            }
        }

        String phase = "";
        if (s.paced) {
            phase = (s.currentIndex > index) ? "  •  closed" : (s.currentIndex == index ? "  •  OPEN" : "  •  not opened yet");
        }
        lblProgress.setText("Question " + (index + 1) + " of " + total + phase
                + "   |   Join code " + formatCode(code));
        lblQuestion.setText("<html><div style='text-align:center; width:860px'>" + escapeHtml(q.text) + "</div></html>");

        String[] labels = new String[n];
        Color[] colors = new Color[n];
        boolean reveal = chkReveal.isSelected();
        for (int i = 0; i < n; i++) {
            labels[i] = SHAPES[i] + " " + q.options[i];
            Color base = q.isTrueFalse() ? (i == 0 ? OPTION_COLORS[2] : OPTION_COLORS[1]) : OPTION_COLORS[i];
            if (reveal) {
                colors[i] = (i == q.correctOption - 1) ? new Color(34, 197, 94) : new Color(base.getRed(), base.getGreen(), base.getBlue(), 90);
            } else {
                colors[i] = base;
            }
        }
        chart.setData(labels, counts, colors);

        lblCounts.setText(answered + " answered   •   " + noAnswer + " ran out of time   •   "
                + parts.size() + " students in the session");
    }
}