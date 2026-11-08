package com.mycompany.educationaltools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.Timer;

import static com.mycompany.educationaltools.UiKit.*;

/*
 * ============================================================================
 *  EDUCATIONAL TOOLS - Login, Join Codes, Live Monitor, Quiz Sets, Podium,
 *  Teacher-paced quizzes, Projector view, Class Report, Retry mistakes ...
 * ----------------------------------------------------------------------------
 *  Default teacher account (first run):  username "teacher"  /  password "teacher123"
 *  -> Please change it using the "Change Password" tile after the first login.
 *  Students create their own account with "Create a Student Account".
 *
 *  Project layout (all in package com.mycompany.educationaltools):
 *     Status.java              - every status / role text in one place
 *     Models.java              - Question, results, sessions, participants, quiz sets ...
 *     DataStore.java           - accounts, sessions, live progress, results, images (files in the data folder)
 *     UiComponents.java        - Theme (light/dark) + custom Swing components
 *     UiKit.java               - small shared UI helpers (fonts, tables, CSV escaping ...)
 *     Charts.java              - BarChartPanel
 *     ClassReport.java         - statistics + alerts for a hosted quiz (no Swing)
 *     Importers.java           - CSV and Aiken parsers (no Swing)
 *     AppSupport.java          - input limits + global crash handler / error log
 *     ReportDialog.java, StudentDetailDialog.java, ProjectorDialog.java - teacher screens
 *     EducationalTools.java    - this file: the main window and the other screens
 *
 *  The data folder (default "quiz_data", see DataStore) holds EVERYTHING that is shared:
 *     users, sessions, participants, results, images, custom_questions.json, quiz_sets.json
 * ============================================================================
 */
public class EducationalTools extends JFrame {

    private static final String ALL_CATEGORIES = "All Categories";
    private static final String BOOKMARK_SOURCE = "⭐ Bookmarked Only";
    private static final String RETRY_CATEGORY = "Mistakes review";

    private static final Map<String, Map<Integer, List<Question>>> questionBank = new HashMap<>();
    private static List<StudentResult> leaderboard = new ArrayList<>();
    private static final List<Question> bookmarkedQuestions = new ArrayList<>();
    private static final List<Question> customQuestions = new ArrayList<>();
    private static final List<QuizSet> quizSets = new ArrayList<>();
    private static final String QUESTIONS_FILE = "custom_questions.json";
    private static final String SETS_FILE = "quiz_sets.json";
    private static final String LEGACY_LEADERBOARD = "leaderboard.json";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // ---- fixed theme colours (the light/dark surfaces live in Theme) ----
    private final Color PRIMARY_NAVY = new Color(30, 27, 75);
    private final Color ACCENT_BLUE = new Color(79, 70, 229);
    private final Color ACCENT_HOVER = new Color(67, 56, 202);
    private final Color ACCENT_PINK = new Color(236, 72, 153);
    private final Color ACCENT_PINK_HOVER = new Color(219, 39, 119);
    private final Color DANGER_RED = new Color(239, 68, 68);
    private final Color DANGER_HOVER = new Color(185, 28, 28);
    private final Color[] OPTION_COLORS = {
            new Color(79, 70, 229), new Color(219, 39, 119), new Color(13, 148, 136), new Color(234, 88, 12)
    };
    private final Color[] OPTION_HOVERS = {
            new Color(67, 56, 202), new Color(190, 24, 93), new Color(15, 118, 110), new Color(194, 65, 12)
    };

    // ---- settings (remembered per computer user) ----
    private final Preferences prefs = Preferences.userNodeForPackage(EducationalTools.class);
    private boolean soundOn = true;

    // ---- login state ----
    private UserAccount currentUser;
    private JPanel loginPanel;
    private JPanel quizPanel;
    private JPanel dashboardPanel;
    private JTextField txtLoginUser;
    private JPasswordField txtLoginPass;
    private JLabel lblLoginError;

    // ---- quiz state ----
    private String currentStudentName = "";
    private String selectedCategory = ALL_CATEGORIES;
    private int currentDifficulty = 2;
    private int totalScore = 0;
    private int correctCount = 0;
    private int questionCounter = 0;
    private int maxQuestions = 5;
    private long startTime;
    private Question currentQuestion;   // what is on screen (may be a shuffled copy)
    private Question currentOriginal;   // the real question in the bank (used for bookmarks)
    private boolean quizActive = false;
    private boolean answerLocked = false; // true while the feedback panel is showing
    private boolean selfPaced = false;
    private int secondsPerQuestion = 15;
    private int streak = 0;
    private int bestStreak = 0;
    private final Set<Question> usedQuestions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Random random = new Random();
    private boolean fiftyFiftyUsed = false;
    private final StringBuilder liveResults = new StringBuilder(); // '1' / '0' per answered question
    private final StringBuilder liveChoices = new StringBuilder(); // original option number picked per question ('0' = none)
    private final StringBuilder liveTimes = new StringBuilder();   // seconds spent per question, comma separated
    private long questionShownAt = 0;
    private List<Question> fixedQueue;  // "retry my mistakes": a fixed list of questions instead of the adaptive picker

    // ---- live session state (only set while a student plays a hosted quiz) ----
    private QuizSession activeSession;
    private Participant myParticipant;
    private long awayStart = 0;
    private Timer bannerTimer;
    private JDialog monitorDialog;
    private Timer pacedTimer;                // teacher-paced quizzes: checks every second what the teacher has opened
    private boolean waitingForTeacher = false;

    private Timer questionTimer;
    private int timeRemaining = 15;
    private JLabel lblTimerBadge;
    private JLabel lblStreakBadge;
    private JLabel lblSessionChip;
    private JLabel lblAwayBanner;

    // ---- inline feedback panel (shown after every answer instead of a pop-up) ----
    private RoundedPanel feedbackPanel;
    private JLabel lblFeedbackTitle;
    private JLabel lblFeedbackBody;
    private RoundedButton btnNext;
    private Timer feedbackTimer;
    private int feedbackCountdown;

    private final List<UserAnswerRecord> currentSessionReview = new ArrayList<>();

    private CardLayout cardLayout;
    private JPanel mainContainer;

    private JLabel lblQuestionCounter;
    private JLabel lblDifficultyBadge;
    private JLabel lblScoreBadge;
    private JProgressBar progressBar;
    private JLabel lblQuestionText;
    private JPanel optionGrid;
    private final RoundedButton[] optionButtons = new RoundedButton[4];
    private RoundedButton btnHint, btnFiftyFifty, btnBookmark;

    public EducationalTools() {
        setTitle("Educational Tools — Ultimate LMS & Adaptive Quiz Suite");
        setSize(1000, 780);
        setMinimumSize(new Dimension(1000, 780));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        soundOn = prefs.getBoolean("sound", true);
        Theme.apply(prefs.getBoolean("dark", false));

        DataStore.init();
        loadLeaderboardData();
        seedQuestions();
        loadCustomQuestions();
        loadQuizSets();

        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);
        installStaticPanels();
        add(mainContainer);
        cardLayout.show(mainContainer, "Login");

        // Keyboard shortcuts during a quiz: 1-4 answer, Enter = next question.
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            bindKey(getRootPane(), KeyEvent.VK_1 + i, () -> {
                if (quizActive && quizPanel.isShowing() && !answerLocked && idx < optionButtons.length
                        && optionButtons[idx].isShowing() && optionButtons[idx].isEnabled()) {
                    processAnswerSelection(idx + 1);
                }
            });
        }
        bindKey(getRootPane(), KeyEvent.VK_ENTER, () -> {
            if (quizActive && quizPanel.isShowing() && feedbackPanel.isVisible()) advanceFromFeedback();
        });

        // If the window is closed in the middle of a live quiz, the teacher must see that the student left.
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (quizActive && activeSession != null) {
                    stopAllTimers();
                    markLeftQuiz();
                }
            }
        });

        // Switching to another app/window during a live quiz is recorded as "switched away".
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                // getOppositeWindow() != null means focus just moved to one of OUR dialogs, which is fine.
                if (quizActive && activeSession != null && e.getOppositeWindow() == null && awayStart == 0) {
                    awayStart = System.currentTimeMillis();
                }
            }

            @Override
            public void windowGainedFocus(WindowEvent e) {
                if (awayStart > 0) {
                    long ms = System.currentTimeMillis() - awayStart;
                    awayStart = 0;
                    if (quizActive && activeSession != null && ms >= 800) recordAway(ms);
                }
            }
        });
    }

    /** (Re)creates the login and quiz screens so they pick up the current theme. */
    private void installStaticPanels() {
        if (loginPanel != null) mainContainer.remove(loginPanel);
        if (quizPanel != null) mainContainer.remove(quizPanel);
        loginPanel = createLoginPanel();
        quizPanel = createQuizPanel();
        mainContainer.add(loginPanel, "Login");
        mainContainer.add(quizPanel, "QuizPanel");
        mainContainer.revalidate();
        mainContainer.repaint();
    }

    // ===================== HELPERS =====================

    private void beep() {
        if (soundOn) Toolkit.getDefaultToolkit().beep();
    }

    private String modeOf(StudentResult r) {
        return (r.mode != null) ? r.mode : "Timed";
    }

    private boolean isPaced() {
        return activeSession != null && activeSession.paced;
    }

    private List<String> getCategoryNames() {
        List<String> names = new ArrayList<>();
        names.add(ALL_CATEGORIES);
        List<String> others = new ArrayList<>();
        for (String c : questionBank.keySet()) {
            if (!c.equals(ALL_CATEGORIES)) others.add(c);
        }
        Collections.sort(others);
        names.addAll(others);
        return names;
    }

    private int countQuestions(String category) {
        Map<Integer, List<Question>> pool = questionBank.get(category);
        if (pool == null) return 0;
        int total = 0;
        for (List<Question> list : pool.values()) total += list.size();
        return total;
    }

    private int nextQuestionId() {
        int max = 0;
        for (List<Question> list : questionBank.get(ALL_CATEGORIES).values()) {
            for (Question q : list) max = Math.max(max, q.id);
        }
        return max + 1;
    }

    /** Every question in the bank, sorted by category, then difficulty, then id. */
    private List<Question> allQuestionsSorted() {
        List<Question> all = new ArrayList<>();
        for (List<Question> list : questionBank.get(ALL_CATEGORIES).values()) all.addAll(list);
        all.sort(Comparator.comparing((Question q) -> q.category.toLowerCase(Locale.ROOT))
                .thenComparingInt(q -> q.difficulty).thenComparingInt(q -> q.id));
        return all;
    }

    /** Loads a question picture from the images folder, shrunk to fit the given size (null if there is none). */
    private ImageIcon loadQuestionImage(String stored, int maxW, int maxH) {
        File file = DataStore.imageFile(stored);
        if (file == null) return null;
        ImageIcon raw = new ImageIcon(file.getAbsolutePath());
        int w = raw.getIconWidth();
        int h = raw.getIconHeight();
        if (w <= 0 || h <= 0) return null;
        double scale = Math.min(1.0, Math.min((double) maxW / w, (double) maxH / h));
        if (scale >= 1.0) return raw;
        return new ImageIcon(raw.getImage().getScaledInstance((int) (w * scale), (int) (h * scale), Image.SCALE_SMOOTH));
    }

    private void styleField(JTextField field) {
        field.setFont(f(Font.PLAIN, 14));
        field.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(Theme.border, 1, true), new EmptyBorder(9, 12, 9, 12)));
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(f(Font.BOLD, 12));
        l.setForeground(Theme.textMuted);
        return l;
    }

    /** Writes a text file in UTF-8 (with a BOM so Excel reads emoji and accents) and tells the teacher where it is. */
    private void writeTextFile(Component parent, File out, String content) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write("\uFEFF");
            w.write(content);
            JOptionPane.showMessageDialog(parent, "Saved to:\n" + out.getAbsolutePath(), "Export Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            AppSupport.log(ex);
            JOptionPane.showMessageDialog(parent, "Could not save the file.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ===================== LOGIN / LOGOUT =====================

    private JPanel createLoginPanel() {
        ModernGradientPanel bg = new ModernGradientPanel(new Color(30, 27, 75), new Color(126, 34, 206));
        bg.setLayout(new GridBagLayout());

        RoundedPanel card = new RoundedPanel(28, Theme.card, true);
        card.setLayout(new GridBagLayout());
        card.setBorder(new EmptyBorder(30, 46, 40, 52));
        card.setPreferredSize(new Dimension(450, 620));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(3, 0, 3, 0);

        JLabel logo = new JLabel("🎓", SwingConstants.CENTER);
        logo.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 54));
        JLabel title = new JLabel("Educational Tools", SwingConstants.CENTER);
        title.setFont(f(Font.BOLD, 26));
        title.setForeground(Theme.textDark);
        JLabel subtitle = new JLabel("Log in to join or host a live quiz", SwingConstants.CENTER);
        subtitle.setFont(f(Font.PLAIN, 13));
        subtitle.setForeground(Theme.textMuted);

        txtLoginUser = new JTextField();
        styleField(txtLoginUser);
        txtLoginPass = new JPasswordField();
        styleField(txtLoginPass);
        final char defaultEcho = txtLoginPass.getEchoChar();

        JCheckBox chkShow = new JCheckBox("Show password");
        chkShow.setOpaque(false);
        chkShow.setFont(f(Font.PLAIN, 12));
        chkShow.setForeground(Theme.textMuted);
        chkShow.setFocusable(false);
        chkShow.addActionListener(e -> txtLoginPass.setEchoChar(chkShow.isSelected() ? (char) 0 : defaultEcho));

        lblLoginError = new JLabel(" ", SwingConstants.CENTER);
        lblLoginError.setFont(f(Font.BOLD, 12));
        lblLoginError.setForeground(new Color(220, 38, 38));

        RoundedButton btnLogin = new RoundedButton("Log In", ACCENT_BLUE, ACCENT_HOVER, 14);
        btnLogin.setFont(f(Font.BOLD, 15));
        btnLogin.setPreferredSize(new Dimension(100, 46));

        JLabel lblNew = new JLabel("New here? Students can sign up below.", SwingConstants.CENTER);
        lblNew.setFont(f(Font.PLAIN, 12));
        lblNew.setForeground(Theme.textMuted);

        RoundedButton btnSignUp = new RoundedButton("Create a Student Account", ACCENT_PINK, ACCENT_PINK_HOVER, 14);
        btnSignUp.setPreferredSize(new Dimension(100, 42));

        JButton btnFolder = new JButton("📁 Data folder (shared classroom setup)");
        btnFolder.setFont(f(Font.PLAIN, 11));
        btnFolder.setForeground(Theme.textMuted);
        btnFolder.setContentAreaFilled(false);
        btnFolder.setBorderPainted(false);
        btnFolder.setFocusPainted(false);
        btnFolder.setCursor(new Cursor(Cursor.HAND_CURSOR));

        btnLogin.addActionListener(e -> attemptLogin());
        txtLoginPass.addActionListener(e -> attemptLogin());
        txtLoginUser.addActionListener(e -> txtLoginPass.requestFocusInWindow());
        btnSignUp.addActionListener(e -> displaySignUpDialog());
        btnFolder.addActionListener(e -> displayDataFolderDialog());

        int row = 0;
        c.gridy = row++; card.add(logo, c);
        c.gridy = row++; card.add(title, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 18, 0); card.add(subtitle, c);
        c.insets = new Insets(3, 0, 3, 0);
        c.gridy = row++; card.add(fieldLabel("USERNAME"), c);
        c.gridy = row++; card.add(txtLoginUser, c);
        c.gridy = row++; c.insets = new Insets(10, 0, 3, 0); card.add(fieldLabel("PASSWORD"), c);
        c.insets = new Insets(3, 0, 3, 0);
        c.gridy = row++; card.add(txtLoginPass, c);
        c.gridy = row++; card.add(chkShow, c);
        c.gridy = row++; card.add(lblLoginError, c);
        c.gridy = row++; card.add(btnLogin, c);
        c.gridy = row++; c.insets = new Insets(16, 0, 6, 0); card.add(lblNew, c);
        c.insets = new Insets(3, 0, 3, 0);
        c.gridy = row++; card.add(btnSignUp, c);
        c.gridy = row++; c.insets = new Insets(10, 0, 0, 0); card.add(btnFolder, c);

        bg.add(card);
        return bg;
    }

    /** Shows where the shared data lives and lets the teacher pick another folder (used the next time the program starts). */
    private void displayDataFolderDialog() {
        int ask = JOptionPane.showConfirmDialog(this,
                "Current data folder:\n" + DataStore.ROOT.toAbsolutePath()
                        + "\n\nOn a classroom network EVERY computer must use the SAME shared folder.\nChoose a different folder?",
                "Data Folder", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ask != JOptionPane.YES_OPTION) return;

        JFileChooser chooser = new JFileChooser(DataStore.ROOT.toAbsolutePath().toFile());
        chooser.setDialogTitle("Choose the shared data folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        DataStore.saveDataFolder(chooser.getSelectedFile().getAbsolutePath());
        JOptionPane.showMessageDialog(this,
                "Saved: " + chooser.getSelectedFile().getAbsolutePath() + "\n\nClose and reopen the program to use the new folder.",
                "Data Folder", JOptionPane.INFORMATION_MESSAGE);
    }

    private void attemptLogin() {
        String user = txtLoginUser.getText().trim();
        char[] pw = txtLoginPass.getPassword();
        if (user.isEmpty() || pw.length == 0) {
            lblLoginError.setText("Enter your username and password.");
            return;
        }
        UserAccount account = DataStore.authenticate(user, pw);
        Arrays.fill(pw, '\0');
        if (account == null) {
            lblLoginError.setText("Incorrect username or password.");
            txtLoginPass.setText("");
            return;
        }
        currentUser = account;
        lblLoginError.setText(" ");
        txtLoginPass.setText("");
        showDashboard();
    }

    private void displaySignUpDialog() {
        JTextField txtName = new JTextField();
        JTextField txtUser = new JTextField();
        JPasswordField txtPass = new JPasswordField();
        JPasswordField txtConfirm = new JPasswordField();
        JLabel lblErr = new JLabel(" ");
        lblErr.setForeground(new Color(220, 38, 38));
        lblErr.setFont(f(Font.BOLD, 12));

        Object[] fields = {
            "Full name:", txtName,
            "Username (letters, numbers, . or _):", txtUser,
            "Password (min. 6 characters):", txtPass,
            "Confirm password:", txtConfirm,
            lblErr
        };

        while (true) {
            int result = JOptionPane.showConfirmDialog(this, fields, "Create Student Account",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;

            if (!Arrays.equals(txtPass.getPassword(), txtConfirm.getPassword())) {
                lblErr.setText("Passwords do not match.");
                continue;
            }
            String error = DataStore.createUser(txtUser.getText(), Limits.clean(txtName.getText(), Limits.NAME),
                    Status.STUDENT, txtPass.getPassword());
            if (error != null) {
                lblErr.setText(error);
                continue;
            }
            txtLoginUser.setText(DataStore.normalizeUsername(txtUser.getText()));
            txtLoginPass.setText("");
            lblLoginError.setText(" ");
            JOptionPane.showMessageDialog(this, "Account created! You can log in now.", "Welcome", JOptionPane.INFORMATION_MESSAGE);
            txtLoginPass.requestFocusInWindow();
            return;
        }
    }

    private void logout() {
        int confirm = JOptionPane.showConfirmDialog(this, "Log out of " + currentUser.displayName + "?",
                "Log Out", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        if (monitorDialog != null) {
            monitorDialog.dispose();
            monitorDialog = null;
        }
        for (Question q : bookmarkedQuestions) q.isBookmarked = false;
        bookmarkedQuestions.clear();
        currentUser = null;
        txtLoginUser.setText("");
        txtLoginPass.setText("");
        lblLoginError.setText(" ");
        cardLayout.show(mainContainer, "Login");
        txtLoginUser.requestFocusInWindow();
    }

    private void displayChangePasswordDialog() {
        JPasswordField txtOld = new JPasswordField();
        JPasswordField txtNew = new JPasswordField();
        JPasswordField txtConfirm = new JPasswordField();
        JLabel lblErr = new JLabel(" ");
        lblErr.setForeground(new Color(220, 38, 38));
        lblErr.setFont(f(Font.BOLD, 12));

        Object[] fields = {
            "Current password:", txtOld,
            "New password (min. 6 characters):", txtNew,
            "Confirm new password:", txtConfirm,
            lblErr
        };

        while (true) {
            int result = JOptionPane.showConfirmDialog(this, fields, "Change Password",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;

            if (!Arrays.equals(txtNew.getPassword(), txtConfirm.getPassword())) {
                lblErr.setText("New passwords do not match.");
                continue;
            }
            String error = DataStore.changePassword(currentUser.username, txtOld.getPassword(), txtNew.getPassword());
            if (error != null) {
                lblErr.setText(error);
                continue;
            }
            JOptionPane.showMessageDialog(this, "Your password was updated.", "Password Changed", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
    }

    // ===================== DASHBOARD =====================

    private void showDashboard() {
        loadLeaderboardData();
        if (dashboardPanel != null) mainContainer.remove(dashboardPanel);
        dashboardPanel = buildDashboard();
        mainContainer.add(dashboardPanel, "Dashboard");
        mainContainer.revalidate();
        mainContainer.repaint();
        cardLayout.show(mainContainer, "Dashboard");
    }

    private void toggleTheme() {
        Theme.apply(!Theme.dark);
        prefs.putBoolean("dark", Theme.dark);
        installStaticPanels();
        showDashboard();
    }

    private void toggleSound() {
        soundOn = !soundOn;
        prefs.putBoolean("sound", soundOn);
        showDashboard();
    }

    private RoundedPanel statCard(String icon, String value, String label, Color accent) {
        RoundedPanel card = new RoundedPanel(18, Theme.card, true);
        card.setLayout(new BorderLayout(12, 0));
        card.setBorder(new EmptyBorder(14, 18, 20, 22));

        RoundedPanel iconBox = new RoundedPanel(14, new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 36), false);
        iconBox.setLayout(new GridBagLayout());
        iconBox.setPreferredSize(new Dimension(46, 46));
        JLabel lblIcon = new JLabel(icon);
        lblIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 22));
        iconBox.add(lblIcon);

        JLabel lblValue = new JLabel(value);
        lblValue.setFont(f(Font.BOLD, 24));
        lblValue.setForeground(Theme.textDark);
        JLabel lblLabel = new JLabel(label);
        lblLabel.setFont(f(Font.PLAIN, 12));
        lblLabel.setForeground(Theme.textMuted);

        JPanel text = new JPanel(new GridLayout(2, 1, 0, 0));
        text.setOpaque(false);
        text.add(lblValue);
        text.add(lblLabel);

        JPanel iconWrap = new JPanel(new GridBagLayout());
        iconWrap.setOpaque(false);
        iconWrap.add(iconBox);

        card.add(iconWrap, BorderLayout.WEST);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    private RoundedButton headerIconButton(String icon, String tooltip, Runnable action) {
        RoundedButton b = new RoundedButton(icon, new Color(255, 255, 255, 50), new Color(255, 255, 255, 90), 14);
        b.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));
        b.setPreferredSize(new Dimension(44, 36));
        b.setToolTipText(tooltip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private JPanel buildDashboard() {
        final boolean teacher = currentUser.isTeacher();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.bg);

        // ---- header ----
        ModernGradientPanel header = new ModernGradientPanel(new Color(30, 27, 75), new Color(126, 34, 206));
        header.setBorder(new EmptyBorder(22, 34, 22, 34));

        JLabel lblHello = new JLabel("Hi, " + currentUser.displayName + " 👋");
        lblHello.setFont(f(Font.BOLD, 26));
        lblHello.setForeground(Color.WHITE);
        JLabel lblSub = new JLabel(teacher ? "Host a live quiz, watch your class in real time and manage your question bank."
                                           : "Join a quiz with your teacher's code, or practice on your own.");
        lblSub.setFont(f(Font.PLAIN, 13));
        lblSub.setForeground(new Color(224, 231, 255));
        JPanel greeting = new JPanel(new GridLayout(2, 1, 0, 4));
        greeting.setOpaque(false);
        greeting.add(lblHello);
        greeting.add(lblSub);

        JLabel rolePill = chip(teacher ? "👩‍🏫  TEACHER" : "🎒  STUDENT", new Color(255, 255, 255, 40), Color.WHITE);
        RoundedButton btnTheme = headerIconButton(Theme.dark ? "☀" : "🌙",
                Theme.dark ? "Switch to light mode" : "Switch to dark mode", this::toggleTheme);
        RoundedButton btnSound = headerIconButton(soundOn ? "🔊" : "🔇",
                soundOn ? "Sound effects are ON (click to mute)" : "Sound effects are OFF (click to turn on)", this::toggleSound);
        RoundedButton btnLogout = new RoundedButton("Log out  ➜", new Color(255, 255, 255, 50), new Color(255, 255, 255, 90), 14);
        btnLogout.setPreferredSize(new Dimension(120, 36));
        btnLogout.addActionListener(e -> logout());
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerRight.setOpaque(false);
        headerRight.add(rolePill);
        headerRight.add(btnTheme);
        headerRight.add(btnSound);
        headerRight.add(btnLogout);

        header.add(greeting, BorderLayout.WEST);
        header.add(headerRight, BorderLayout.EAST);

        // ---- stats ----
        JPanel stats = new JPanel(new GridLayout(1, 4, 16, 0));
        stats.setOpaque(false);
        stats.setPreferredSize(new Dimension(100, 92));

        if (teacher) {
            List<QuizSession> mine = DataStore.listSessionsByHost(currentUser.username);
            int open = 0;
            for (QuizSession s : mine) if (!Status.ENDED.equals(s.status)) open++;
            stats.add(statCard("📚", String.valueOf(countQuestions(ALL_CATEGORIES)), "Questions in bank", ACCENT_BLUE));
            stats.add(statCard("🎮", String.valueOf(mine.size()), "Quizzes hosted", ACCENT_PINK));
            stats.add(statCard("🟢", String.valueOf(open), "Open sessions now", new Color(16, 185, 129)));
            stats.add(statCard("🎒", String.valueOf(DataStore.listStudents().size()), "Student accounts", new Color(234, 88, 12)));
        } else {
            int attempts = 0, bestScore = 0, bestStreakAll = 0;
            double accSum = 0;
            for (StudentResult r : leaderboard) {
                if (currentUser.username.equals(r.username)) {
                    attempts++;
                    bestScore = Math.max(bestScore, r.score);
                    bestStreakAll = Math.max(bestStreakAll, r.bestStreak);
                    accSum += r.accuracy;
                }
            }
            String avg = attempts == 0 ? "—" : String.format("%.0f%%", accSum / attempts);
            stats.add(statCard("📝", String.valueOf(attempts), "Quizzes taken", ACCENT_BLUE));
            stats.add(statCard("🏅", String.valueOf(bestScore), "Best score (pts)", ACCENT_PINK));
            stats.add(statCard("🎯", avg, "Average accuracy", new Color(16, 185, 129)));
            stats.add(statCard("🔥", String.valueOf(bestStreakAll), "Best streak", new Color(234, 88, 12)));
        }

        // ---- action tiles ----
        JPanel tiles = new JPanel(new GridLayout(3, teacher ? 4 : 3, 16, 16));
        tiles.setOpaque(false);

        Color violet = new Color(124, 58, 237);
        Color teal = new Color(13, 148, 136);
        Color amber = new Color(217, 119, 6);
        Color slate = new Color(71, 85, 105);
        Color green = new Color(16, 185, 129);

        if (teacher) {
            tiles.add(new ActionTile("🚀", "Host a Quiz", "Create a join code", ACCENT_PINK, true, () -> displayHostQuizDialog(null)));
            tiles.add(new ActionTile("📡", "Live Sessions", "Watch your class live", ACCENT_BLUE, false, () -> openSessionMonitor(null)));
            tiles.add(new ActionTile("⚙", "Question Bank", "Add, edit, delete", slate, false, this::displayQuestionEditorModal));
            tiles.add(new ActionTile("📦", "Quiz Sets", "Save & reuse quizzes", violet, false, this::displayQuizSetsDialog));
            tiles.add(new ActionTile("📥", "Import", "CSV or Aiken (Moodle)", teal, false, this::displayImportQuestionsDialog));
            tiles.add(new ActionTile("🏆", "Leaderboard", "Results & export", amber, false, () -> displayLeaderboardModal(false)));
            tiles.add(new ActionTile("📋", "Class Reports", "Hardest questions, who needs help", ACCENT_BLUE, false, this::displayReportPicker));
            tiles.add(new ActionTile("📊", "Analytics", "Accuracy overview", green, false, this::displayAnalyticsModal));
            tiles.add(new ActionTile("🃏", "Flashcards", "Preview any deck", violet, false, this::displayFlashcardModal));
            tiles.add(new ActionTile("👥", "Students", "Reset passwords", teal, false, this::displayManageStudentsDialog));
            tiles.add(new ActionTile("🔑", "Change Password", "Keep it safe", slate, false, this::displayChangePasswordDialog));
            tiles.add(new ActionTile("🚪", "Exit App", "Close the program", DANGER_RED, false, () -> System.exit(0)));
        } else {
            tiles.add(new ActionTile("🚀", "Join a Quiz", "Enter the code from your teacher", ACCENT_PINK, true, this::displayJoinQuizDialog));
            tiles.add(new ActionTile("🧠", "Practice Quiz", "Adaptive, timed or self-paced", ACCENT_BLUE, false, this::startNewQuizSession));
            tiles.add(new ActionTile("🃏", "Flashcards", "Study and flip cards", violet, false, this::displayFlashcardModal));
            tiles.add(new ActionTile("⭐", "Bookmarks", "Questions you saved", amber, false, this::displayBookmarksModal));
            tiles.add(new ActionTile("📈", "My Results", "Your quiz history", teal, false, () -> displayLeaderboardModal(true)));
            tiles.add(new ActionTile("🏆", "Leaderboard", "See the top scores", green, false, () -> displayLeaderboardModal(false)));
            tiles.add(new ActionTile("🔑", "Change Password", "Keep your account safe", slate, false, this::displayChangePasswordDialog));
            tiles.add(new ActionTile("🚪", "Exit App", "Close Educational Tools", DANGER_RED, false, () -> System.exit(0)));
        }

        JPanel body = new JPanel(new BorderLayout(0, 18));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(22, 34, 26, 34));
        body.add(stats, BorderLayout.NORTH);
        body.add(tiles, BorderLayout.CENTER);

        root.add(header, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        return root;
    }

    // ===================== MANAGE STUDENTS (teacher) =====================

    private void displayManageStudentsDialog() {
        JDialog dialog = new JDialog(this, "Manage Students", true);
        dialog.setSize(620, 440);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));

        DefaultTableModel model = new DefaultTableModel(new String[]{"Username", "Full Name", "Created"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(30);
        table.setFont(f(Font.PLAIN, 13));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        styleTable(table);

        Runnable reload = () -> {
            model.setRowCount(0);
            for (UserAccount u : DataStore.listStudents()) {
                model.addRow(new Object[]{u.username, u.displayName, new Date(u.createdAt)});
            }
        };
        reload.run();

        RoundedButton btnReset = new RoundedButton("🔑 Reset Password", ACCENT_BLUE, ACCENT_HOVER, 12);
        RoundedButton btnDelete = new RoundedButton("🗑 Delete Account", DANGER_RED, DANGER_HOVER, 12);
        btnReset.setPreferredSize(new Dimension(170, 36));
        btnDelete.setPreferredSize(new Dimension(170, 36));

        btnReset.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(dialog, "Select a student first."); return; }
            String user = (String) model.getValueAt(row, 0);
            JPasswordField pw = new JPasswordField();
            int ok = JOptionPane.showConfirmDialog(dialog, new Object[]{"New password for " + user + " (min. 6 characters):", pw},
                    "Reset Password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return;
            String err = DataStore.resetPassword(user, pw.getPassword());
            JOptionPane.showMessageDialog(dialog, err == null ? "Password updated. Tell the student the new password." : err);
        });

        btnDelete.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(dialog, "Select a student first."); return; }
            String user = (String) model.getValueAt(row, 0);
            int ok = JOptionPane.showConfirmDialog(dialog, "Delete the account '" + user + "'? This cannot be undone.",
                    "Delete Account", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok == JOptionPane.YES_OPTION) {
                if (!DataStore.deleteUser(user)) {
                    JOptionPane.showMessageDialog(dialog, "That account could not be deleted (teacher accounts are protected).");
                }
                reload.run();
            }
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        bar.add(btnReset);
        bar.add(btnDelete);

        dialog.add(scrollOf(table), BorderLayout.CENTER);
        dialog.add(bar, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    // ===================== QUIZ PANEL =====================

    private JPanel createQuizPanel() {
        JPanel outerPanel = new JPanel(new BorderLayout(0, 0));
        outerPanel.setBackground(Theme.bg);

        JPanel topHeader = new JPanel(new BorderLayout());
        topHeader.setBackground(PRIMARY_NAVY);
        topHeader.setBorder(new EmptyBorder(15, 25, 15, 25));

        lblQuestionCounter = new JLabel("Question 1 of 5", SwingConstants.LEFT);
        lblQuestionCounter.setFont(f(Font.BOLD, 17));
        lblQuestionCounter.setForeground(Color.WHITE);

        JPanel badgeContainer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        badgeContainer.setOpaque(false);

        lblSessionChip = badge("LIVE", ACCENT_PINK, Color.WHITE);
        lblSessionChip.setVisible(false);
        lblTimerBadge = badge("⏱ 15s", new Color(225, 29, 72), Color.WHITE);
        lblStreakBadge = badge("🔥 STREAK: 0", new Color(71, 85, 105), Color.WHITE);
        lblDifficultyBadge = badge("DIFFICULTY: MEDIUM", new Color(234, 179, 8), Color.BLACK);
        lblScoreBadge = badge("SCORE: 0 pts", ACCENT_BLUE, Color.WHITE);

        RoundedButton btnQuit = new RoundedButton("✖ Leave", new Color(100, 116, 139), new Color(71, 85, 105), 6);
        btnQuit.setPreferredSize(new Dimension(80, 26));
        btnQuit.setFocusable(false);
        btnQuit.addActionListener(e -> quitQuiz());

        badgeContainer.add(lblSessionChip);
        badgeContainer.add(lblTimerBadge);
        badgeContainer.add(lblStreakBadge);
        badgeContainer.add(lblDifficultyBadge);
        badgeContainer.add(lblScoreBadge);
        badgeContainer.add(btnQuit);

        topHeader.add(lblQuestionCounter, BorderLayout.WEST);
        topHeader.add(badgeContainer, BorderLayout.EAST);

        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(20);
        progressBar.setPreferredSize(new Dimension(100, 6));
        progressBar.setForeground(ACCENT_PINK);
        progressBar.setBackground(new Color(226, 232, 240));
        progressBar.setBorderPainted(false);

        lblAwayBanner = new JLabel(" ", SwingConstants.CENTER);
        lblAwayBanner.setOpaque(true);
        lblAwayBanner.setBackground(new Color(254, 243, 199));
        lblAwayBanner.setForeground(new Color(146, 64, 14));
        lblAwayBanner.setFont(f(Font.BOLD, 12));
        lblAwayBanner.setBorder(new EmptyBorder(7, 10, 7, 10));
        lblAwayBanner.setVisible(false);

        JPanel northWrapper = new JPanel(new BorderLayout());
        northWrapper.add(topHeader, BorderLayout.NORTH);
        northWrapper.add(progressBar, BorderLayout.CENTER);
        northWrapper.add(lblAwayBanner, BorderLayout.SOUTH);

        JPanel centerContainer = new JPanel(new BorderLayout(0, 14));
        centerContainer.setBorder(new EmptyBorder(18, 34, 22, 34));
        centerContainer.setBackground(Theme.bg);

        RoundedPanel questionCard = new RoundedPanel(22, Theme.card, true);
        questionCard.setLayout(new BorderLayout());
        questionCard.setBorder(new EmptyBorder(14, 24, 22, 30));

        // The question text; when the question has a picture it is shown above the text.
        lblQuestionText = new JLabel("Question prompt appears here...", SwingConstants.CENTER);
        lblQuestionText.setFont(f(Font.BOLD, 20));
        lblQuestionText.setForeground(Theme.textDark);
        lblQuestionText.setHorizontalTextPosition(SwingConstants.CENTER);
        lblQuestionText.setVerticalTextPosition(SwingConstants.BOTTOM);
        lblQuestionText.setIconTextGap(10);
        questionCard.add(lblQuestionText, BorderLayout.CENTER);

        // ---- inline feedback panel (replaces the old pop-up after each answer) ----
        feedbackPanel = new RoundedPanel(18, new Color(220, 252, 231), false);
        feedbackPanel.setLayout(new BorderLayout(14, 0));
        feedbackPanel.setBorder(new EmptyBorder(10, 18, 10, 14));
        lblFeedbackTitle = new JLabel(" ");
        lblFeedbackTitle.setFont(f(Font.BOLD, 16));
        lblFeedbackBody = new JLabel(" ");
        lblFeedbackBody.setFont(f(Font.PLAIN, 13));
        JPanel feedbackText = new JPanel(new GridLayout(2, 1, 0, 2));
        feedbackText.setOpaque(false);
        feedbackText.add(lblFeedbackTitle);
        feedbackText.add(lblFeedbackBody);
        btnNext = new RoundedButton("Next ▶", ACCENT_BLUE, ACCENT_HOVER, 14);
        btnNext.setFont(f(Font.BOLD, 14));
        btnNext.setPreferredSize(new Dimension(200, 40));
        btnNext.setFocusable(false);
        btnNext.addActionListener(e -> advanceFromFeedback());
        feedbackPanel.add(feedbackText, BorderLayout.CENTER);
        feedbackPanel.add(btnNext, BorderLayout.EAST);
        feedbackPanel.setVisible(false);

        JPanel lifelineBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        lifelineBar.setOpaque(false);

        btnHint = new RoundedButton("💡 Hint (-5 pts)", new Color(217, 119, 6), new Color(180, 83, 9), 14);
        btnFiftyFifty = new RoundedButton("⚡ 50:50 Lifeline", new Color(147, 51, 234), new Color(126, 34, 206), 14);
        btnBookmark = new RoundedButton("⭐ Bookmark", new Color(59, 130, 246), new Color(37, 99, 235), 14);

        Dimension lifelineBtnSize = new Dimension(160, 34);
        btnHint.setPreferredSize(lifelineBtnSize);
        btnFiftyFifty.setPreferredSize(lifelineBtnSize);
        btnBookmark.setPreferredSize(lifelineBtnSize);

        btnHint.addActionListener(e -> showHint());
        btnFiftyFifty.addActionListener(e -> useFiftyFiftyLifeline());
        btnBookmark.addActionListener(e -> toggleBookmarkCurrentQuestion());

        lifelineBar.add(btnHint);
        lifelineBar.add(btnFiftyFifty);
        lifelineBar.add(btnBookmark);

        JPanel belowCard = new JPanel(new BorderLayout(0, 8));
        belowCard.setOpaque(false);
        belowCard.add(feedbackPanel, BorderLayout.NORTH);
        belowCard.add(lifelineBar, BorderLayout.SOUTH);

        JPanel questionWithLifelines = new JPanel(new BorderLayout(0, 8));
        questionWithLifelines.setOpaque(false);
        questionWithLifelines.add(questionCard, BorderLayout.CENTER);
        questionWithLifelines.add(belowCard, BorderLayout.SOUTH);

        optionGrid = new JPanel(new GridLayout(2, 2, 14, 14));
        optionGrid.setOpaque(false);
        optionGrid.setPreferredSize(new Dimension(100, 200));

        for (int i = 0; i < 4; i++) {
            final int optionIndex = i + 1;
            optionButtons[i] = new RoundedButton("Option", OPTION_COLORS[i], OPTION_HOVERS[i], 22);
            optionButtons[i].setFont(f(Font.BOLD, 16));
            optionButtons[i].addActionListener(e -> processAnswerSelection(optionIndex));
            optionGrid.add(optionButtons[i]);
        }

        centerContainer.add(questionWithLifelines, BorderLayout.CENTER);
        centerContainer.add(optionGrid, BorderLayout.SOUTH);

        outerPanel.add(northWrapper, BorderLayout.NORTH);
        outerPanel.add(centerContainer, BorderLayout.CENTER);
        return outerPanel;
    }

    private JLabel badge(String text, Color bg, Color fg) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setOpaque(true);
        l.setBackground(bg);
        l.setForeground(fg);
        l.setFont(f(Font.BOLD, 12));
        l.setBorder(new EmptyBorder(4, 10, 4, 10));
        return l;
    }

    private void updateStreakBadge() {
        lblStreakBadge.setText("🔥 STREAK: " + streak);
        lblStreakBadge.setBackground(streak >= 3 ? new Color(249, 115, 22) : new Color(71, 85, 105));
    }

    private void showBanner(String message, Color bg, Color fg) {
        lblAwayBanner.setText(message);
        lblAwayBanner.setBackground(bg);
        lblAwayBanner.setForeground(fg);
        lblAwayBanner.setVisible(true);
        if (bannerTimer != null) bannerTimer.stop();
        bannerTimer = new Timer(7000, e -> lblAwayBanner.setVisible(false));
        bannerTimer.setRepeats(false);
        bannerTimer.start();
    }

    private void stopQuizTimers() {
        if (questionTimer != null) questionTimer.stop();
        if (feedbackTimer != null) feedbackTimer.stop();
    }

    private void stopPacedWatcher() {
        if (pacedTimer != null) pacedTimer.stop();
    }

    private void stopAllTimers() {
        stopQuizTimers();
        stopPacedWatcher();
    }

    // ===================== INLINE FEEDBACK =====================

    private void hideFeedback() {
        if (feedbackTimer != null) feedbackTimer.stop();
        feedbackPanel.setVisible(false);
        feedbackPanel.getParent().revalidate();
    }

    /** Shows the green / red / amber result strip under the question. Timed quizzes move on by themselves. */
    private void showFeedback(boolean correct, boolean timedOut, String title, String body) {
        Color bg, fg;
        if (correct) {
            bg = Theme.dark ? new Color(20, 83, 45) : new Color(220, 252, 231);
            fg = Theme.dark ? new Color(134, 239, 172) : new Color(22, 101, 52);
        } else if (timedOut) {
            bg = Theme.dark ? new Color(120, 53, 15) : new Color(254, 243, 199);
            fg = Theme.dark ? new Color(252, 211, 77) : new Color(146, 64, 14);
        } else {
            bg = Theme.dark ? new Color(127, 29, 29) : new Color(254, 226, 226);
            fg = Theme.dark ? new Color(252, 165, 165) : new Color(153, 27, 27);
        }
        feedbackPanel.setBackground(bg);
        lblFeedbackTitle.setForeground(fg);
        lblFeedbackBody.setForeground(fg);
        lblFeedbackTitle.setText(title);
        lblFeedbackBody.setText("<html>" + body + "</html>");

        final boolean paced = isPaced();
        final String nextLabel = (questionCounter >= maxQuestions) ? "Finish ▶" : "Next ▶";
        btnNext.setEnabled(!paced);
        btnNext.setText(paced ? "⏳ Waiting for teacher" : nextLabel);
        feedbackPanel.setVisible(true);
        feedbackPanel.getParent().revalidate();
        feedbackPanel.getParent().repaint();

        // In a teacher-paced quiz only the teacher moves the class on (see pacedTick).
        if (!selfPaced && !paced) {
            feedbackCountdown = correct ? 3 : 5;
            btnNext.setText(nextLabel + " (" + feedbackCountdown + ")");
            if (feedbackTimer != null) feedbackTimer.stop();
            feedbackTimer = new Timer(1000, e -> {
                feedbackCountdown--;
                if (feedbackCountdown <= 0) {
                    advanceFromFeedback();
                } else {
                    btnNext.setText(nextLabel + " (" + feedbackCountdown + ")");
                }
            });
            feedbackTimer.start();
        }
    }

    /** Student pressed Next / Enter, or the countdown ran out. Ignored in teacher-paced quizzes. */
    private void advanceFromFeedback() {
        if (!quizActive || !feedbackPanel.isVisible() || isPaced()) return;
        hideFeedback();
        loadNextAdaptiveQuestion();
    }

    /** The teacher opened the next question (or finished the quiz). */
    private void advanceFromTeacher() {
        if (!quizActive || !feedbackPanel.isVisible()) return;
        hideFeedback();
        loadNextAdaptiveQuestion();
    }

    // ===================== LEAVING / SWITCHING AWAY (live quizzes) =====================

    private void quitQuiz() {
        boolean questionRunning = questionTimer != null && questionTimer.isRunning();
        boolean feedbackRunning = feedbackTimer != null && feedbackTimer.isRunning();
        boolean pacedRunning = pacedTimer != null && pacedTimer.isRunning();
        stopAllTimers();

        boolean live = (activeSession != null);
        int confirm = JOptionPane.showConfirmDialog(this,
                live ? "Leave this live quiz?\n\nYour teacher will see that you left, and which question you were on.\nYou can only come back if your teacher lets you."
                     : "Quit this quiz? Your progress will not be saved.",
                live ? "Leave Live Quiz" : "Quit Quiz", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            if (live) markLeftQuiz();
            quizActive = false;
            waitingForTeacher = false;
            hideFeedback();
            activeSession = null;
            myParticipant = null;
            showDashboard();
        } else {
            if (questionRunning) questionTimer.start();
            if (feedbackRunning) feedbackTimer.start();
            if (pacedRunning) pacedTimer.start();
        }
    }

    /** Copies the student's running totals into the participant record (not saved yet). */
    private void copyProgressInto(Participant p) {
        p.answered = currentSessionReview.size();
        p.score = totalScore;
        p.correct = correctCount;
        p.bestStreak = bestStreak;
        p.results = liveResults.toString();
        p.choices = liveChoices.toString();
        p.times = liveTimes.toString();
    }

    /** Writes "LEFT" for the current student so the teacher's monitor shows it right away. */
    private void markLeftQuiz() {
        if (activeSession == null || myParticipant == null) return;
        myParticipant.status = Status.LEFT;
        myParticipant.leftCount++;
        myParticipant.leftAtQuestion = Math.max(1, questionCounter);
        copyProgressInto(myParticipant);
        myParticipant.leftAt = System.currentTimeMillis();
        myParticipant.updatedAt = myParticipant.leftAt;
        DataStore.saveParticipant(myParticipant);
    }

    private void recordAway(long millisAway) {
        if (myParticipant == null) return;
        long secs = Math.max(1, Math.round(millisAway / 1000.0));
        myParticipant.awayCount++;
        myParticipant.awaySeconds += secs;
        myParticipant.updatedAt = System.currentTimeMillis();
        DataStore.saveParticipant(myParticipant);
        showBanner("⚠ You left the quiz window for " + secs + "s — your teacher can see this.",
                new Color(254, 226, 226), new Color(153, 27, 27));
    }

    private void syncParticipant() {
        if (activeSession == null || myParticipant == null) return;
        copyProgressInto(myParticipant);
        myParticipant.updatedAt = System.currentTimeMillis();
        DataStore.saveParticipant(myParticipant);
    }

    // ===================== LIFELINES =====================

    private void useFiftyFiftyLifeline() {
        if (answerLocked || currentQuestion == null || currentQuestion.optionCount() != 4) return;
        if (fiftyFiftyUsed) {
            JOptionPane.showMessageDialog(this, "You have already used the 50:50 lifeline in this session!", "Lifeline Used", JOptionPane.WARNING_MESSAGE);
            return;
        }

        fiftyFiftyUsed = true;
        btnFiftyFifty.setEnabled(false);

        List<Integer> incorrectIndices = new ArrayList<>();
        for (int i = 1; i <= currentQuestion.optionCount(); i++) {
            if (i != currentQuestion.correctOption) incorrectIndices.add(i);
        }
        Collections.shuffle(incorrectIndices);

        for (int k = 0; k < 2; k++) {
            int hide = incorrectIndices.get(k) - 1;
            optionButtons[hide].setEnabled(false);
            optionButtons[hide].setText("[Removed]");
        }
        beep();
    }

    private boolean toggleBookmark(Question q) {
        if (bookmarkedQuestions.contains(q)) {
            bookmarkedQuestions.remove(q);
            q.isBookmarked = false;
            return false;
        }
        bookmarkedQuestions.add(q);
        q.isBookmarked = true;
        return true;
    }

    /** Bookmarking no longer interrupts the quiz with a pop-up: the button just changes colour. */
    private void toggleBookmarkCurrentQuestion() {
        if (currentOriginal != null) {
            if (toggleBookmark(currentOriginal)) {
                btnBookmark.setColors(new Color(234, 179, 8), new Color(202, 138, 4));
                btnBookmark.setText("⭐ Saved!");
            } else {
                btnBookmark.setColors(new Color(59, 130, 246), new Color(37, 99, 235));
                btnBookmark.setText("⭐ Bookmark");
            }
        }
    }

    private void showHint() {
        if (answerLocked) return;
        if (currentQuestion != null && currentQuestion.hint != null && !currentQuestion.hint.isEmpty()) {
            totalScore = Math.max(0, totalScore - 5);
            lblScoreBadge.setText("SCORE: " + totalScore + " pts");
            syncParticipant();
            JOptionPane.showMessageDialog(this, "HINT: " + currentQuestion.hint, "Question Hint", JOptionPane.INFORMATION_MESSAGE);
            btnHint.setEnabled(false);
        } else {
            JOptionPane.showMessageDialog(this, "No hint available for this question.", "Hint", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ===================== PRACTICE QUIZ (adaptive) =====================

    private void startNewQuizSession() {
        JComboBox<String> cmbCategory = new JComboBox<>(getCategoryNames().toArray(new String[0]));
        JComboBox<String> cmbMode = new JComboBox<>(new String[]{
                "⏱ Timed Challenge (15s per question)",
                "🧘 Self-Paced (no timer)"
        });
        JSpinner spnQuestions = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));

        Object[] inputFields = {
            "Select Quiz Category:", cmbCategory,
            "Quiz Mode:", cmbMode,
            "Number of Questions:", spnQuestions
        };

        int option = JOptionPane.showConfirmDialog(this, inputFields, "Practice Quiz Setup", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (option != JOptionPane.OK_OPTION) return;

        String category = (String) cmbCategory.getSelectedItem();
        int available = countQuestions(category);
        if (available == 0) {
            JOptionPane.showMessageDialog(this, "That category has no questions yet.", "Empty Category", JOptionPane.WARNING_MESSAGE);
            return;
        }

        this.activeSession = null;
        this.myParticipant = null;
        this.fixedQueue = null;
        this.currentStudentName = currentUser.displayName;
        this.selectedCategory = category;
        this.selfPaced = (cmbMode.getSelectedIndex() == 1);
        this.secondsPerQuestion = 15;
        // The student gets exactly the number of questions they asked for.
        // If the category has fewer questions than that, the questions simply come around again (see pickQuestion).
        this.maxQuestions = ((Number) spnQuestions.getValue()).intValue();
        if (maxQuestions > available) {
            JOptionPane.showMessageDialog(this,
                    "\"" + category + "\" has only " + available + " different questions,\n"
                            + "so some of them will come again to reach " + maxQuestions + ".\n\n"
                            + "Tip: add more questions in the Question Bank (or import a CSV / Aiken file).",
                    "Fewer questions than requested", JOptionPane.INFORMATION_MESSAGE);
        }
        this.currentDifficulty = 2;
        resetRunCounters();
        lblSessionChip.setVisible(false);
        lblAwayBanner.setVisible(false);

        quizActive = true;
        cardLayout.show(mainContainer, "QuizPanel");
        loadNextAdaptiveQuestion();
    }

    /** "Retry my mistakes": a self-paced practice round with only the questions the student got wrong. */
    private void startRetryMistakes(List<Question> mistakes) {
        this.activeSession = null;
        this.myParticipant = null;
        this.fixedQueue = new ArrayList<>(mistakes);
        Collections.shuffle(this.fixedQueue);
        this.currentStudentName = currentUser.displayName;
        this.selectedCategory = RETRY_CATEGORY;
        this.selfPaced = true;
        this.secondsPerQuestion = 15;
        this.maxQuestions = fixedQueue.size();
        this.currentDifficulty = 2;
        resetRunCounters();
        lblSessionChip.setVisible(false);
        lblAwayBanner.setVisible(false);

        quizActive = true;
        cardLayout.show(mainContainer, "QuizPanel");
        loadNextAdaptiveQuestion();
    }

    private void resetRunCounters() {
        this.totalScore = 0;
        this.correctCount = 0;
        this.questionCounter = 0;
        this.streak = 0;
        this.bestStreak = 0;
        this.fiftyFiftyUsed = false;
        this.answerLocked = false;
        this.waitingForTeacher = false;
        this.usedQuestions.clear();
        this.currentSessionReview.clear();
        this.liveResults.setLength(0);
        this.liveChoices.setLength(0);
        this.liveTimes.setLength(0);
        this.startTime = System.currentTimeMillis();
        updateStreakBadge();
    }

    private int[] difficultyOrder(int difficulty) {
        switch (difficulty) {
            case 1:  return new int[]{1, 2, 3};
            case 3:  return new int[]{3, 2, 1};
            default: return new int[]{2, 1, 3};
        }
    }

    // Picks an unused question at the current difficulty, falling back to the nearest levels.
    // When every question of the category has been used (the student asked for more questions
    // than the category holds), a new round starts, without repeating the same question twice in a row.
    private Question pickQuestion() {
        Map<Integer, List<Question>> pool = questionBank.get(selectedCategory);
        if (pool == null) return null;

        Question picked = pickUnused(pool);
        if (picked == null && countQuestions(selectedCategory) > 0) {
            Question last = currentOriginal;
            usedQuestions.clear();
            if (last != null) usedQuestions.add(last);
            picked = pickUnused(pool);
            if (picked == null) {          // the category has a single question
                usedQuestions.clear();
                picked = pickUnused(pool);
            }
        }
        return picked;
    }

    private Question pickUnused(Map<Integer, List<Question>> pool) {
        for (int diff : difficultyOrder(currentDifficulty)) {
            List<Question> candidates = new ArrayList<>();
            for (Question q : pool.getOrDefault(diff, Collections.emptyList())) {
                if (!usedQuestions.contains(q)) candidates.add(q);
            }
            if (!candidates.isEmpty()) {
                return candidates.get(random.nextInt(candidates.size()));
            }
        }
        return null;
    }

    // ===================== HOSTED (LIVE) QUIZZES - STUDENT SIDE =====================

    private void displayJoinQuizDialog() {
        JDialog d = new JDialog(this, "Join a Quiz", true);
        d.setSize(450, 430);
        d.setLocationRelativeTo(this);
        d.setResizable(false);

        ModernGradientPanel bg = new ModernGradientPanel(new Color(30, 27, 75), new Color(126, 34, 206));
        bg.setLayout(new GridBagLayout());

        RoundedPanel card = new RoundedPanel(26, Theme.card, true);
        card.setLayout(new GridBagLayout());
        card.setBorder(new EmptyBorder(24, 36, 34, 42));
        card.setPreferredSize(new Dimension(370, 340));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(4, 0, 4, 0);

        JLabel icon = new JLabel("🚀", SwingConstants.CENTER);
        icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 40));
        JLabel title = new JLabel("Join a Quiz", SwingConstants.CENTER);
        title.setFont(f(Font.BOLD, 24));
        title.setForeground(Theme.textDark);
        JLabel hint = new JLabel("Enter the 6-digit code your teacher shows", SwingConstants.CENTER);
        hint.setFont(f(Font.PLAIN, 12));
        hint.setForeground(Theme.textMuted);

        JTextField txtCode = new JTextField();
        txtCode.setHorizontalAlignment(SwingConstants.CENTER);
        txtCode.setFont(f(Font.BOLD, 34));
        txtCode.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(167, 139, 250), 2, true), new EmptyBorder(8, 10, 8, 10)));

        JLabel lblErr = new JLabel(" ", SwingConstants.CENTER);
        lblErr.setFont(f(Font.BOLD, 12));
        lblErr.setForeground(new Color(220, 38, 38));

        RoundedButton btnJoin = new RoundedButton("Join Quiz", ACCENT_PINK, ACCENT_PINK_HOVER, 14);
        btnJoin.setFont(f(Font.BOLD, 16));
        btnJoin.setPreferredSize(new Dimension(100, 46));

        Runnable attempt = () -> {
            String digits = txtCode.getText().replaceAll("\\D", "");
            if (digits.length() != 6) {
                lblErr.setText("Please enter the 6-digit code.");
                return;
            }
            final QuizSession session = DataStore.loadSession(digits);
            if (session == null) {
                lblErr.setText("No quiz found with that code.");
                return;
            }
            if (Status.ENDED.equals(session.status)) {
                lblErr.setText("This quiz has already ended.");
                return;
            }
            Participant existing = DataStore.loadParticipant(digits, currentUser.username);
            if (existing != null) {
                if (Status.FINISHED.equals(existing.status)) {
                    lblErr.setText("You have already finished this quiz.");
                    return;
                }
                // Started before and not finished (left, or the app crashed): only the teacher can let them back in.
                boolean startedBefore = Status.PLAYING.equals(existing.status)
                        || (Status.LEFT.equals(existing.status) && existing.leftAtQuestion > 0);
                if (startedBefore) {
                    lblErr.setText("Ask your teacher to let you rejoin.");
                    return;
                }
            }
            d.dispose();
            SwingUtilities.invokeLater(() -> enterSession(session));
        };
        btnJoin.addActionListener(e -> attempt.run());
        txtCode.addActionListener(e -> attempt.run());

        int row = 0;
        c.gridy = row++; card.add(icon, c);
        c.gridy = row++; card.add(title, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 14, 0); card.add(hint, c);
        c.insets = new Insets(4, 0, 4, 0);
        c.gridy = row++; card.add(txtCode, c);
        c.gridy = row++; card.add(lblErr, c);
        c.gridy = row++; card.add(btnJoin, c);

        bg.add(card);
        d.add(bg);
        d.getRootPane().setDefaultButton(btnJoin);
        SwingUtilities.invokeLater(txtCode::requestFocusInWindow);
        d.setVisible(true);
    }

    private void enterSession(QuizSession session) {
        long now = System.currentTimeMillis();
        Participant p = DataStore.loadParticipant(session.code, currentUser.username);
        if (p == null) {
            p = new Participant();
            p.sessionCode = session.code;
            p.username = currentUser.username;
            p.displayName = currentUser.displayName;
            p.joinedAt = now;
        }
        p.status = Status.JOINED;
        p.total = session.questions.size();
        p.updatedAt = now;
        DataStore.saveParticipant(p);

        QuizSession live = session;
        if (Status.LOBBY.equals(session.status)) {
            int result = showWaitingRoom(session);
            if (result == 0) {                 // student left the waiting room
                p.status = Status.LEFT;
                p.leftCount++;
                p.leftAtQuestion = 0;
                p.leftAt = System.currentTimeMillis();
                p.updatedAt = p.leftAt;
                DataStore.saveParticipant(p);
                return;
            }
            if (result == 2) {                 // host ended the session before it started
                p.status = Status.LEFT;
                p.updatedAt = System.currentTimeMillis();
                DataStore.saveParticipant(p);
                JOptionPane.showMessageDialog(this, "The host ended this quiz before it started.", "Quiz Ended", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            live = DataStore.loadSession(session.code);
            if (live == null) return;
        }
        startHostedQuiz(live, p);
    }

    /** Returns 0 = student left, 1 = quiz started, 2 = host ended the session. */
    private int showWaitingRoom(QuizSession session) {
        final int[] outcome = {0};
        final int[] dots = {0};

        JDialog d = new JDialog(this, "Waiting Room", true);
        d.setSize(500, 420);
        d.setLocationRelativeTo(this);
        d.setResizable(false);

        ModernGradientPanel bg = new ModernGradientPanel(new Color(30, 27, 75), new Color(126, 34, 206));
        bg.setLayout(new GridBagLayout());

        JPanel box = new JPanel(new GridLayout(6, 1, 0, 8));
        box.setOpaque(false);

        JLabel lblIcon = new JLabel("⏳", SwingConstants.CENTER);
        lblIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 54));
        JLabel lblTitle = new JLabel(session.title, SwingConstants.CENTER);
        lblTitle.setFont(f(Font.BOLD, 24));
        lblTitle.setForeground(Color.WHITE);
        JLabel lblCode = new JLabel("Code  " + formatCode(session.code) + "   •   " + session.questions.size() + " questions"
                + (session.paced ? "   •   teacher-paced" : ""), SwingConstants.CENTER);
        lblCode.setFont(f(Font.PLAIN, 14));
        lblCode.setForeground(new Color(199, 210, 254));
        JLabel lblWait = new JLabel("Waiting for your teacher to start", SwingConstants.CENTER);
        lblWait.setFont(f(Font.BOLD, 16));
        lblWait.setForeground(Color.WHITE);
        JLabel lblPlayers = new JLabel("You're in as " + currentUser.displayName, SwingConstants.CENTER);
        lblPlayers.setFont(f(Font.PLAIN, 13));
        lblPlayers.setForeground(new Color(199, 210, 254));
        RoundedButton btnLeave = new RoundedButton("Leave waiting room", new Color(255, 255, 255, 50), new Color(255, 255, 255, 90), 14);
        btnLeave.setPreferredSize(new Dimension(190, 38));
        JPanel leaveWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        leaveWrap.setOpaque(false);
        leaveWrap.add(btnLeave);

        box.add(lblIcon);
        box.add(lblTitle);
        box.add(lblCode);
        box.add(lblWait);
        box.add(lblPlayers);
        box.add(leaveWrap);
        bg.add(box);
        d.add(bg);

        Timer poll = new Timer(1000, e -> {
            QuizSession fresh = DataStore.loadSession(session.code);
            if (fresh == null || Status.ENDED.equals(fresh.status)) {
                outcome[0] = 2;
                ((Timer) e.getSource()).stop();
                d.dispose();
                return;
            }
            if (Status.LIVE.equals(fresh.status)) {
                outcome[0] = 1;
                ((Timer) e.getSource()).stop();
                d.dispose();
                return;
            }
            dots[0] = (dots[0] + 1) % 4;
            lblWait.setText("Waiting for your teacher to start" + ".".repeat(dots[0]));
            int inLobby = 0;
            for (Participant x : DataStore.listParticipants(session.code)) {
                if (Status.JOINED.equals(x.status)) inLobby++;
            }
            lblPlayers.setText("👥 " + inLobby + " in the lobby  •  you're in as " + currentUser.displayName);
        });

        btnLeave.addActionListener(e -> { outcome[0] = 0; poll.stop(); d.dispose(); });
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { poll.stop(); }
        });
        poll.start();
        d.setVisible(true); // blocks until the dialog is disposed
        return outcome[0];
    }

    private void startHostedQuiz(QuizSession s, Participant p) {
        this.activeSession = s;
        this.myParticipant = p;
        this.fixedQueue = null;
        this.currentStudentName = currentUser.displayName;
        this.selectedCategory = s.category;
        this.selfPaced = !s.timed;
        this.secondsPerQuestion = s.secondsPerQuestion;
        this.maxQuestions = s.questions.size();
        this.currentDifficulty = 2;
        resetRunCounters();

        long now = System.currentTimeMillis();
        p.status = Status.PLAYING;
        p.total = s.questions.size();
        p.answered = 0;
        p.score = 0;
        p.correct = 0;
        p.bestStreak = 0;
        p.results = "";
        p.choices = "";
        p.times = "";
        p.updatedAt = now;
        DataStore.saveParticipant(p);

        lblSessionChip.setText("LIVE • " + formatCode(s.code));
        lblSessionChip.setVisible(true);
        showBanner(s.paced
                        ? "👀 Live quiz, paced by your teacher: wait for each question to open. Your teacher sees your progress and if you leave this window."
                        : "👀 This is a live quiz: your teacher can see your progress and if you leave this window.",
                new Color(254, 243, 199), new Color(146, 64, 14));

        quizActive = true;
        awayStart = 0;
        cardLayout.show(mainContainer, "QuizPanel");
        if (s.paced) startPacedWatcher();
        loadNextAdaptiveQuestion();
    }

    // ===================== TEACHER-PACED QUIZZES - STUDENT SIDE =====================

    private void startPacedWatcher() {
        stopPacedWatcher();
        pacedTimer = new Timer(1000, e -> pacedTick());
        pacedTimer.start();
    }

    /** Every second: what has the teacher opened? Show it, close the current question, or move on. */
    private void pacedTick() {
        if (!quizActive || !isPaced()) {
            stopPacedWatcher();
            return;
        }
        QuizSession fresh = DataStore.loadSession(activeSession.code);
        if (fresh == null) return;
        if (Status.ENDED.equals(fresh.status)) {
            finishBecauseTeacherEnded(answerLocked && !waitingForTeacher);
            return;
        }
        int open = fresh.currentIndex; // 0-based question the teacher has opened
        if (waitingForTeacher) {
            if (open >= questionCounter - 1) {
                waitingForTeacher = false;
                presentQuestion();
            }
        } else if (answerLocked && feedbackPanel.isVisible()) {
            if (open >= questionCounter) advanceFromTeacher();
        } else if (!answerLocked && open > questionCounter - 1) {
            // the teacher closed this question before the student answered it
            if (questionTimer != null) questionTimer.stop();
            processAnswerSelection(0);
        }
    }

    private void showWaitingForTeacher() {
        waitingForTeacher = true;
        answerLocked = true;
        lblQuestionCounter.setText("Question " + questionCounter + " of " + maxQuestions);
        lblQuestionText.setIcon(null);
        lblQuestionText.setText("<html><body style='width: 600px; text-align: center;'>⏳ Waiting for your teacher to open the next question…</body></html>");
        for (RoundedButton b : optionButtons) {
            b.setEnabled(false);
            b.setText(" ");
        }
        btnHint.setEnabled(false);
        btnFiftyFifty.setEnabled(false);
        lblTimerBadge.setText("⏳ WAIT");
        lblTimerBadge.setBackground(new Color(100, 116, 139));
        progressBar.setValue((int) (((double) (questionCounter - 1) / maxQuestions) * 100));
    }

    /** The teacher ended the quiz while this student was playing: finish with what was answered so far. */
    private void finishBecauseTeacherEnded(boolean currentQuestionAnswered) {
        stopAllTimers();
        JOptionPane.showMessageDialog(this, "Your teacher has ended this quiz. Here are your results so far.",
                "Quiz Ended", JOptionPane.INFORMATION_MESSAGE);
        if (!currentQuestionAnswered) questionCounter--; // this question was never answered
        maxQuestions = questionCounter;
        completeQuizSession();
    }

    // ===================== QUESTION FLOW =====================

    /** Lays out 4 coloured answers (2x2) or 2 big True/False answers (1x2) for the question on screen. */
    private void configureOptionButtons() {
        int n = currentQuestion.optionCount();
        boolean tf = currentQuestion.isTrueFalse();
        optionGrid.removeAll();
        optionGrid.setLayout(new GridLayout(n == 2 ? 1 : 2, 2, 14, 14));
        for (int i = 0; i < n && i < optionButtons.length; i++) {
            int colorIndex = tf ? (i == 0 ? 2 : 1) : i; // True = teal, False = pink
            optionButtons[i].setEnabled(true);
            optionButtons[i].setColors(OPTION_COLORS[colorIndex], OPTION_HOVERS[colorIndex]);
            // a shape before every answer, so the colour is never the only clue
            optionButtons[i].setText(SHAPES[i] + "   " + currentQuestion.options[i]);
            optionButtons[i].setToolTipText("Press " + (i + 1) + " on the keyboard");
            optionGrid.add(optionButtons[i]);
        }
        optionGrid.revalidate();
        optionGrid.repaint();
    }

    private void loadNextAdaptiveQuestion() {
        stopQuizTimers();
        hideFeedback();
        answerLocked = false;
        waitingForTeacher = false;

        questionCounter++;

        if (activeSession != null && questionCounter <= maxQuestions) {
            QuizSession fresh = DataStore.loadSession(activeSession.code);
            if (fresh != null && Status.ENDED.equals(fresh.status)) {
                finishBecauseTeacherEnded(false);
                return;
            }
        }

        if (questionCounter > maxQuestions) {
            completeQuizSession();
            return;
        }

        Question original;
        if (activeSession != null) original = activeSession.questions.get(questionCounter - 1);
        else if (fixedQueue != null) original = fixedQueue.get(questionCounter - 1);
        else original = pickQuestion();

        if (original == null) {
            // Ran out of unused questions: finish with what was answered so far.
            questionCounter--;
            maxQuestions = questionCounter;
            if (questionCounter == 0) {
                JOptionPane.showMessageDialog(this, "No questions found for this category!", "Warning", JOptionPane.WARNING_MESSAGE);
                quizActive = false;
                showDashboard();
            } else {
                completeQuizSession();
            }
            return;
        }

        currentOriginal = original;
        usedQuestions.add(original);
        // Every student sees the options in a different order (so "press 2" from a neighbour is useless).
        currentQuestion = original.canShuffle() ? original.shuffledCopy(random) : original;

        if (activeSession != null || fixedQueue != null) currentDifficulty = currentQuestion.difficulty;

        if (activeSession != null && myParticipant != null) {
            myParticipant.status = Status.PLAYING;
            myParticipant.answered = questionCounter - 1;
            myParticipant.updatedAt = System.currentTimeMillis();
            DataStore.saveParticipant(myParticipant);
        }

        // Teacher-paced: wait until the teacher has opened this question.
        if (isPaced()) {
            QuizSession fresh = DataStore.loadSession(activeSession.code);
            if (fresh != null && fresh.currentIndex < questionCounter - 1) {
                showWaitingForTeacher();
                return;
            }
        }
        presentQuestion();
    }

    /** Shows the question that loadNextAdaptiveQuestion picked and starts its timer. */
    private void presentQuestion() {
        answerLocked = false;
        waitingForTeacher = false;
        progressBar.setValue((int) (((double) questionCounter / maxQuestions) * 100));

        configureOptionButtons();
        btnHint.setEnabled(true);
        btnFiftyFifty.setEnabled(!fiftyFiftyUsed && currentQuestion.optionCount() == 4);

        if (bookmarkedQuestions.contains(currentOriginal)) {
            btnBookmark.setColors(new Color(234, 179, 8), new Color(202, 138, 4));
            btnBookmark.setText("⭐ Saved!");
        } else {
            btnBookmark.setColors(new Color(59, 130, 246), new Color(37, 99, 235));
            btnBookmark.setText("⭐ Bookmark");
        }

        lblQuestionCounter.setText("Question " + questionCounter + " of " + maxQuestions
                + (currentQuestion.isTrueFalse() ? "  •  True / False" : ""));
        lblScoreBadge.setText("SCORE: " + totalScore + " pts");
        updateDifficultyBadge(currentDifficulty);
        updateStreakBadge();

        lblQuestionText.setIcon(currentQuestion.image == null ? null : loadQuestionImage(currentQuestion.image, 420, 130));
        lblQuestionText.setText("<html><body style='width: 600px; text-align: center;'>" + escapeHtml(currentQuestion.text) + "</body></html>");

        questionShownAt = System.currentTimeMillis();

        if (selfPaced) {
            lblTimerBadge.setText("∞ SELF-PACED");
            lblTimerBadge.setBackground(new Color(13, 148, 136));
            return;
        }

        lblTimerBadge.setBackground(new Color(225, 29, 72));
        timeRemaining = secondsPerQuestion;
        lblTimerBadge.setText("⏱ " + timeRemaining + "s");

        questionTimer = new Timer(1000, e -> {
            timeRemaining--;
            lblTimerBadge.setText("⏱ " + timeRemaining + "s");

            // Every 3 seconds a student in a live quiz checks whether the teacher has ended it.
            if (activeSession != null && timeRemaining > 0 && timeRemaining % 3 == 0) {
                QuizSession fresh = DataStore.loadSession(activeSession.code);
                if (fresh != null && Status.ENDED.equals(fresh.status)) {
                    questionTimer.stop();
                    finishBecauseTeacherEnded(false);
                    return;
                }
            }

            if (timeRemaining <= 0) {
                questionTimer.stop();
                processAnswerSelection(0);
            }
        });
        questionTimer.start();
    }

    private void processAnswerSelection(int selectedIndex) {
        if (answerLocked || currentQuestion == null) return;
        answerLocked = true;
        if (questionTimer != null) questionTimer.stop();

        boolean live = (activeSession != null);
        boolean adaptive = (activeSession == null && fixedQueue == null);
        boolean timedOut = (selectedIndex == 0);
        boolean isCorrect = (selectedIndex == currentQuestion.correctOption);
        currentSessionReview.add(new UserAnswerRecord(currentQuestion, currentOriginal, selectedIndex, isCorrect));
        liveResults.append(isCorrect ? '1' : '0');

        // which answer was picked (in the ORIGINAL order) and how long it took: used by the teacher's reports
        liveChoices.append((char) ('0' + currentQuestion.toOriginalOption(selectedIndex)));
        double secondsSpent = Math.max(0, (System.currentTimeMillis() - questionShownAt) / 1000.0);
        if (liveTimes.length() > 0) liveTimes.append(',');
        liveTimes.append(String.format(Locale.US, "%.1f", secondsSpent));

        // lock the answers while the feedback strip is showing
        for (RoundedButton b : optionButtons) b.setEnabled(false);
        btnHint.setEnabled(false);
        btnFiftyFifty.setEnabled(false);

        String title;
        StringBuilder body = new StringBuilder();

        if (isCorrect) {
            streak++;
            bestStreak = Math.max(bestStreak, streak);

            int basePoints = (adaptive ? currentDifficulty : currentQuestion.difficulty) * 10;
            int streakBonus = (streak >= 2) ? Math.min(streak - 1, 5) * 5 : 0;
            int speedBonus = selfPaced ? 0 : (int) Math.round(5.0 * timeRemaining / Math.max(1, secondsPerQuestion));
            int gained = basePoints + streakBonus + speedBonus;
            totalScore += gained;
            correctCount++;

            lblScoreBadge.setText("SCORE: " + totalScore + " pts");
            updateStreakBadge();

            title = "✅ Correct!  +" + gained + " pts";
            body.append("Base ").append(basePoints).append(" pts");
            if (streakBonus > 0) body.append(" &nbsp;•&nbsp; 🔥 streak x").append(streak).append(" +").append(streakBonus);
            if (speedBonus > 0) body.append(" &nbsp;•&nbsp; ⚡ speed +").append(speedBonus);
            if (adaptive) {
                body.append("<br>").append(currentDifficulty < 3 ? "Difficulty increased." : "You're on the hardest level!");
                if (currentDifficulty < 3) currentDifficulty++;
            }
        } else {
            String correctText = currentQuestion.options[currentQuestion.correctOption - 1];
            title = timedOut ? "⏰ Time's up!" : "❌ Not quite";
            body.append("Correct answer: <b>").append(escapeHtml(correctText)).append("</b>");
            if ((selfPaced || live) && currentQuestion.hint != null && !currentQuestion.hint.isEmpty()) {
                body.append("<br>Study tip: ").append(escapeHtml(currentQuestion.hint));
            }
            if (streak >= 3) {
                body.append("<br>Your streak of ").append(streak).append(" has ended.");
            }
            streak = 0;
            updateStreakBadge();
            if (adaptive && currentDifficulty > 1) currentDifficulty--;
        }

        beep();
        syncParticipant();
        showFeedback(isCorrect, timedOut, title, body.toString());
    }

    private void completeQuizSession() {
        stopAllTimers();
        hideFeedback();
        answerLocked = false;
        waitingForTeacher = false;

        long timeTakenSeconds = (System.currentTimeMillis() - startTime) / 1000;
        double accuracy = (maxQuestions > 0) ? ((double) correctCount / maxQuestions) * 100 : 0;
        boolean live = (activeSession != null && myParticipant != null);
        String modeLabel = live ? "Live" : (fixedQueue != null ? "Retry" : (selfPaced ? "Self-Paced" : "Timed"));
        String rankLine = "";
        final String sessionCode = live ? activeSession.code : null;

        if (live) {
            copyProgressInto(myParticipant);
            myParticipant.status = Status.FINISHED;
            myParticipant.total = activeSession.questions.size();
            myParticipant.accuracy = accuracy;
            myParticipant.timeTakenSeconds = timeTakenSeconds;
            myParticipant.finishedAt = System.currentTimeMillis();
            myParticipant.updatedAt = myParticipant.finishedAt;
            DataStore.saveParticipant(myParticipant);
            DataStore.autoEndIfDone(activeSession.code);

            List<Participant> finished = new ArrayList<>();
            for (Participant x : DataStore.listParticipants(activeSession.code)) {
                if (Status.FINISHED.equals(x.status)) finished.add(x);
            }
            finished.sort((a, b) -> a.score != b.score ? Integer.compare(b.score, a.score)
                                                      : Long.compare(a.timeTakenSeconds, b.timeTakenSeconds));
            for (int i = 0; i < finished.size(); i++) {
                if (finished.get(i).username.equals(currentUser.username)) {
                    rankLine = "Rank: #" + (i + 1) + " of " + finished.size() + " finished so far\n";
                    break;
                }
            }
        }

        if (!currentSessionReview.isEmpty()) {
            // every result is its own file in the shared data folder, so nobody overwrites anybody
            StudentResult result = new StudentResult(currentStudentName, currentUser.username, selectedCategory, modeLabel,
                    totalScore, accuracy, timeTakenSeconds, maxQuestions, bestStreak);
            DataStore.saveResult(result);
            leaderboard.add(result);
        }

        // the questions this student got wrong (practice only): they can be retried right away
        List<Question> mistakes = new ArrayList<>();
        if (!live) {
            for (UserAnswerRecord r : currentSessionReview) {
                if (!r.isCorrect && r.original != null && !mistakes.contains(r.original)) mistakes.add(r.original);
            }
        }

        quizActive = false;
        awayStart = 0;
        if (live && myParticipant.awayCount > 0) {
            rankLine += "Switched away: " + myParticipant.awayCount + "× (" + myParticipant.awaySeconds + "s)\n";
        }

        List<String> choiceList = new ArrayList<>();
        choiceList.add("Review Answers");
        if (live) choiceList.add("🏆 Podium");
        if (!mistakes.isEmpty()) choiceList.add("🔁 Retry my mistakes (" + mistakes.size() + ")");
        choiceList.add("Back to Dashboard");
        String[] choices = choiceList.toArray(new String[0]);

        int userChoice = JOptionPane.showOptionDialog(this,
                "QUIZ COMPLETED!\n\n" +
                "Student: " + currentStudentName + "\n" +
                "Category: " + selectedCategory + "\n" +
                "Mode: " + modeLabel + "\n" +
                "Final Score: " + totalScore + " pts\n" +
                "Accuracy Rate: " + String.format("%.1f", accuracy) + "%\n" +
                "Best Streak: " + bestStreak + " 🔥\n" +
                rankLine +
                "Time Taken: " + timeTakenSeconds + " seconds",
                "Session Result",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                choices,
                choices[live ? 1 : 0]);

        String picked = (userChoice >= 0 && userChoice < choices.length) ? choices[userChoice] : "Back to Dashboard";

        if ("Review Answers".equals(picked)) {
            displayQuestionReviewModal();
        } else if (picked.startsWith("🏆")) {
            showPodiumDialog(this, sessionCode);
        }

        activeSession = null;
        myParticipant = null;

        if (picked.startsWith("🔁")) {
            startRetryMistakes(mistakes);
            return;
        }
        fixedQueue = null;
        showDashboard();
    }

    private void displayQuestionReviewModal() {
        JDialog dialog = new JDialog(this, "Quiz Performance & Question Review", true);
        dialog.setSize(720, 420);
        dialog.setLocationRelativeTo(this);

        String[] columns = {"#", "Question", "Your Answer", "Correct Answer", "Status"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };

        int idx = 1;
        for (UserAnswerRecord record : currentSessionReview) {
            String userChoiceText = (record.selectedOption == 0) ? "No Answer (Timeout)" : record.question.options[record.selectedOption - 1];
            String correctChoiceText = record.question.options[record.question.correctOption - 1];
            String status = record.isCorrect ? "CORRECT" : "INCORRECT";
            model.addRow(new Object[]{idx++, record.question.text, userChoiceText, correctChoiceText, status});
        }

        JTable table = new JTable(model);
        table.setRowHeight(35);
        table.setFont(f(Font.PLAIN, 12));
        styleTable(table);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(280);

        dialog.add(scrollOf(table));
        dialog.setVisible(true);
    }

    // ===================== HOSTED (LIVE) QUIZZES - TEACHER SIDE =====================

    /** The questions of a saved set that still exist in the bank, in difficulty order. */
    private List<Question> resolveSet(QuizSet set) {
        Map<Integer, Question> byId = new HashMap<>();
        for (Question q : allQuestionsSorted()) byId.put(q.id, q);
        List<Question> result = new ArrayList<>();
        for (int id : set.questionIds) {
            Question q = byId.get(id);
            if (q != null) result.add(q);
        }
        result.sort(Comparator.comparingInt((Question q) -> q.difficulty));
        return result;
    }

    private void displayHostQuizDialog(QuizSet preselect) {
        List<QuizSet> sets = new ArrayList<>(quizSets);
        String[] sourceItems = new String[sets.size() + 1];
        sourceItems[0] = "🎲 Random questions from the category below";
        for (int i = 0; i < sets.size(); i++) {
            sourceItems[i + 1] = "📦 " + sets.get(i).name + "  (" + resolveSet(sets.get(i)).size() + " questions)";
        }

        JTextField txtTitle = new JTextField(preselect != null ? preselect.name : "Quiz Time");
        JComboBox<String> cmbSource = new JComboBox<>(sourceItems);
        if (preselect != null && sets.indexOf(preselect) >= 0) cmbSource.setSelectedIndex(sets.indexOf(preselect) + 1);
        JComboBox<String> cmbCategory = new JComboBox<>(getCategoryNames().toArray(new String[0]));
        JSpinner spnCount = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));
        JComboBox<String> cmbMode = new JComboBox<>(new String[]{"⏱ Timed (per question)", "🧘 Self-paced (no timer)"});
        JSpinner spnSeconds = new JSpinner(new SpinnerNumberModel(15, 5, 120, 5));
        JCheckBox chkLobby = new JCheckBox("Hold students in a waiting room until I press Start", true);
        JCheckBox chkPaced = new JCheckBox("Teacher-paced: I press \"Next Question\" for the whole class", false);
        JCheckBox chkAutoEnd = new JCheckBox("End the quiz automatically when everyone has finished", true);

        Object[] fields = {
            "Quiz title:", txtTitle,
            "Questions come from:", cmbSource,
            "Category (random mode only):", cmbCategory,
            "Number of questions (random mode only):", spnCount,
            "Mode:", cmbMode,
            "Seconds per question (timed mode):", spnSeconds,
            chkLobby,
            chkPaced,
            chkAutoEnd
        };

        int option = JOptionPane.showConfirmDialog(this, fields, "Host a Live Quiz", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (option != JOptionPane.OK_OPTION) return;

        List<Question> chosen;
        String category;
        String title = Limits.clean(txtTitle.getText(), Limits.TITLE);
        if (title.isEmpty()) title = "Quiz Time";
        int source = cmbSource.getSelectedIndex();

        if (source > 0) {
            QuizSet set = sets.get(source - 1);
            chosen = resolveSet(set);
            if (chosen.isEmpty()) {
                JOptionPane.showMessageDialog(this, "That quiz set has no questions left in the bank.", "Empty Set", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Set<String> cats = new TreeSet<>();
            for (Question q : chosen) cats.add(q.category);
            category = (cats.size() == 1) ? cats.iterator().next() : "Mixed topics";
            if (title.equals("Quiz Time")) title = set.name;
        } else {
            category = (String) cmbCategory.getSelectedItem();
            List<Question> pool = new ArrayList<>();
            Map<Integer, List<Question>> bank = questionBank.get(category);
            if (bank != null) {
                for (int d = 1; d <= 3; d++) pool.addAll(bank.getOrDefault(d, Collections.emptyList()));
            }
            if (pool.isEmpty()) {
                JOptionPane.showMessageDialog(this, "That category has no questions yet.", "Empty Category", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Collections.shuffle(pool);
            int n = Math.min(((Number) spnCount.getValue()).intValue(), pool.size());
            chosen = new ArrayList<>(pool.subList(0, n));
            chosen.sort(Comparator.comparingInt((Question q) -> q.difficulty)); // easy -> hard, random within each level
        }

        QuizSession s = new QuizSession();
        s.code = DataStore.generateCode();
        s.hostUsername = currentUser.username;
        s.hostName = currentUser.displayName;
        s.title = title;
        s.category = category;
        s.timed = (cmbMode.getSelectedIndex() == 0);
        s.autoEnd = chkAutoEnd.isSelected();
        s.paced = chkPaced.isSelected();
        s.currentIndex = 0;
        s.secondsPerQuestion = ((Number) spnSeconds.getValue()).intValue();
        s.status = chkLobby.isSelected() ? Status.LOBBY : Status.LIVE;
        s.createdAt = System.currentTimeMillis();
        s.startedAt = chkLobby.isSelected() ? 0 : s.createdAt;
        s.questionStartedAt = s.startedAt;
        s.questions = chosen;
        DataStore.saveSession(s);

        openSessionMonitor(s.code);
    }

    private static class SessionItem {
        final String code;
        final String title;

        SessionItem(String code, String title) {
            this.code = code;
            this.title = title;
        }

        @Override
        public String toString() {
            return formatCode(code) + "  —  " + title;
        }
    }

    /** Giant join code, readable from the back of the classroom. */
    private void showCodeOnProjector(String code) {
        JDialog d = new JDialog(this, "Join Code", false);
        d.setSize(900, 480);
        d.setLocationRelativeTo(this);

        ModernGradientPanel bg = new ModernGradientPanel(new Color(30, 27, 75), new Color(126, 34, 206));
        bg.setLayout(new GridBagLayout());
        JLabel l = new JLabel("<html><div style='text-align:center'>Log in → Join a Quiz → type this code<br>"
                + "<span style='font-size:110px'>" + formatCode(code) + "</span></div></html>", SwingConstants.CENTER);
        l.setFont(f(Font.BOLD, 26));
        l.setForeground(Color.WHITE);
        bg.add(l);
        d.add(bg);
        bindKey(d.getRootPane(), KeyEvent.VK_ESCAPE, d::dispose);
        d.setVisible(true);
    }

    private void displayReportPicker() {
        List<QuizSession> sessions = DataStore.listSessionsByHost(currentUser.username);
        if (sessions.isEmpty()) {
            JOptionPane.showMessageDialog(this, "You haven't hosted a quiz yet.\nUse \"Host a Quiz\" to create one.",
                    "No Sessions", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<SessionItem> cmb = new JComboBox<>();
        for (QuizSession s : sessions) cmb.addItem(new SessionItem(s.code, s.title));
        int ok = JOptionPane.showConfirmDialog(this, new Object[]{"Choose a session:", cmb}, "Class Report",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        SessionItem item = (SessionItem) cmb.getSelectedItem();
        if (item != null) showClassReport(this, item.code);
    }

    private void showClassReport(Window owner, String code) {
        QuizSession s = DataStore.loadSession(code);
        if (s == null) {
            JOptionPane.showMessageDialog(owner, "This session no longer exists.");
            return;
        }
        new ReportDialog(owner, s, DataStore.listParticipants(code)).setVisible(true);
    }

    private void openSessionMonitor(String preselectCode) {
        List<QuizSession> sessions = DataStore.listSessionsByHost(currentUser.username);
        if (sessions.isEmpty()) {
            JOptionPane.showMessageDialog(this, "You haven't hosted a quiz yet.\nUse \"Host a Quiz\" to create one.",
                    "No Sessions", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (monitorDialog != null) monitorDialog.dispose();

        JDialog dialog = new JDialog(this, "Live Session Monitor", false);
        monitorDialog = dialog;
        dialog.setSize(1180, 740);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        JComboBox<SessionItem> cmbSessions = new JComboBox<>();
        for (QuizSession s : sessions) cmbSessions.addItem(new SessionItem(s.code, s.title));
        if (preselectCode != null) {
            for (int i = 0; i < cmbSessions.getItemCount(); i++) {
                if (cmbSessions.getItemAt(i).code.equals(preselectCode)) cmbSessions.setSelectedIndex(i);
            }
        }

        // ---- header with the big join code ----
        ModernGradientPanel header = new ModernGradientPanel(new Color(30, 27, 75), new Color(126, 34, 206));
        header.setBorder(new EmptyBorder(16, 28, 16, 28));

        JLabel lblCaption = new JLabel("JOIN CODE  •  students: log in → Join a Quiz");
        lblCaption.setFont(f(Font.BOLD, 11));
        lblCaption.setForeground(new Color(199, 210, 254));
        JLabel lblCode = new JLabel("--- ---");
        lblCode.setFont(f(Font.BOLD, 54));
        lblCode.setForeground(Color.WHITE);
        JLabel lblSessionTitle = new JLabel(" ");
        lblSessionTitle.setFont(f(Font.PLAIN, 13));
        lblSessionTitle.setForeground(new Color(224, 231, 255));
        JLabel lblStatus = chip("LOBBY", new Color(254, 243, 199), new Color(146, 64, 14));

        JPanel codeBox = new JPanel();
        codeBox.setOpaque(false);
        codeBox.setLayout(new BoxLayout(codeBox, BoxLayout.Y_AXIS));
        lblCaption.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblCode.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblSessionTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        codeBox.add(lblCaption);
        codeBox.add(lblCode);
        codeBox.add(lblSessionTitle);

        Color glass = new Color(255, 255, 255, 55);
        Color glassHover = new Color(255, 255, 255, 95);
        RoundedButton btnStart = new RoundedButton("▶ Start Quiz", new Color(16, 185, 129), new Color(5, 150, 105), 14);
        RoundedButton btnNextQ = new RoundedButton("⏭ Next Question", ACCENT_BLUE, ACCENT_HOVER, 14);
        RoundedButton btnEnd = new RoundedButton("⏹ End Quiz", DANGER_RED, DANGER_HOVER, 14);
        RoundedButton btnPodium = new RoundedButton("🏆 Podium", new Color(234, 179, 8), new Color(202, 138, 4), 14);
        RoundedButton btnProjector = new RoundedButton("📺 Projector", glass, glassHover, 14);
        RoundedButton btnReport = new RoundedButton("📋 Class Report", glass, glassHover, 14);
        RoundedButton btnExport = new RoundedButton("📁 Export CSV", glass, glassHover, 14);
        RoundedButton btnAttendance = new RoundedButton("🗓 Attendance", glass, glassHover, 14);
        RoundedButton btnCopy = new RoundedButton("📋 Copy Code", glass, glassHover, 14);
        RoundedButton btnBig = new RoundedButton("🖥 Big Code", glass, glassHover, 14);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        statusRow.setOpaque(false);
        statusRow.add(lblStatus);

        JPanel buttons = new JPanel(new GridLayout(3, 4, 8, 8));
        buttons.setOpaque(false);
        buttons.setPreferredSize(new Dimension(640, 118));
        buttons.add(btnStart);
        buttons.add(btnNextQ);
        buttons.add(btnEnd);
        buttons.add(btnPodium);
        buttons.add(btnProjector);
        buttons.add(btnReport);
        buttons.add(btnExport);
        buttons.add(btnAttendance);
        buttons.add(btnCopy);
        buttons.add(btnBig);

        JPanel rightBox = new JPanel(new BorderLayout(0, 8));
        rightBox.setOpaque(false);
        rightBox.add(statusRow, BorderLayout.NORTH);
        rightBox.add(buttons, BorderLayout.CENTER);

        header.add(codeBox, BorderLayout.WEST);
        header.add(rightBox, BorderLayout.EAST);

        // ---- counters bar + action bar ----
        JLabel lblJoined = chip("⏳ In lobby: 0", new Color(254, 243, 199), new Color(146, 64, 14));
        JLabel lblPlaying = chip("▶ Playing: 0", new Color(220, 252, 231), new Color(22, 101, 52));
        JLabel lblFinished = chip("✅ Finished: 0", new Color(224, 231, 255), new Color(55, 48, 163));
        JLabel lblLeft = chip("⚠ Left: 0", new Color(254, 226, 226), new Color(153, 27, 27));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        bar.setBackground(Theme.bg);
        JLabel lblPick = themedLabel("Session:", Font.BOLD, 12);
        bar.add(lblPick);
        bar.add(cmbSessions);
        bar.add(lblJoined);
        bar.add(lblPlaying);
        bar.add(lblFinished);
        bar.add(lblLeft);

        RoundedButton btnDetail = new RoundedButton("🔍 Details", new Color(13, 148, 136), new Color(15, 118, 110), 12);
        RoundedButton btnRejoin = new RoundedButton("↩ Allow Rejoin", ACCENT_BLUE, ACCENT_HOVER, 12);
        RoundedButton btnRemove = new RoundedButton("✖ Remove Student", new Color(217, 119, 6), new Color(180, 83, 9), 12);
        RoundedButton btnStats = new RoundedButton("📊 Question Stats", new Color(13, 148, 136), new Color(15, 118, 110), 12);
        RoundedButton btnDeleteSession = new RoundedButton("🗑 Delete Session", new Color(100, 116, 139), new Color(71, 85, 105), 12);
        btnDetail.setPreferredSize(new Dimension(120, 30));
        btnRejoin.setPreferredSize(new Dimension(140, 30));
        btnRemove.setPreferredSize(new Dimension(160, 30));
        btnStats.setPreferredSize(new Dimension(160, 30));
        btnDeleteSession.setPreferredSize(new Dimension(150, 30));

        JPanel actionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        actionBar.setBackground(Theme.bg);
        actionBar.add(themedLabel("Selected student:", Font.BOLD, 12));
        actionBar.add(btnDetail);
        actionBar.add(btnRejoin);
        actionBar.add(btnRemove);
        actionBar.add(Box.createHorizontalStrut(18));
        actionBar.add(btnStats);
        actionBar.add(btnDeleteSession);

        JPanel north = new JPanel(new GridLayout(2, 1));
        north.setBackground(Theme.bg);
        north.add(bar);
        north.add(actionBar);

        // ---- participants table ----
        String[] cols = {"#", "Student", "Status", "Progress", "Score", "Correct", "Best Streak", "Switched Away", "Left Quiz", "Last Activity"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(34);
        table.setFont(f(Font.PLAIN, 13));
        table.setShowVerticalLines(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        styleTable(table);

        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 0; i < table.getColumnCount(); i++) table.getColumnModel().getColumn(i).setCellRenderer(center);
        table.getColumnModel().getColumn(2).setCellRenderer(new StatusCellRenderer());
        table.getColumnModel().getColumn(7).setCellRenderer(new FlagCellRenderer(new Color(255, 237, 213), new Color(154, 52, 18)));
        table.getColumnModel().getColumn(8).setCellRenderer(new FlagCellRenderer(new Color(254, 226, 226), new Color(153, 27, 27)));
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(170);
        table.getColumnModel().getColumn(7).setPreferredWidth(120);
        table.getColumnModel().getColumn(8).setPreferredWidth(190);

        JScrollPane scroll = scrollOf(table);
        scroll.setBorder(new EmptyBorder(0, 14, 6, 14));

        JLabel note = new JLabel("<html>Auto-refreshes every 2 seconds. <b>Switched Away</b> = the student left the quiz window (how many times, total seconds). "
                + "<b>Left Quiz</b> = the student pressed Leave or closed the app (and which question they were on). "
                + "Select a row (or double-click it) to see every answer, let that student rejoin, or remove them.</html>");
        note.setFont(f(Font.PLAIN, 11));
        note.setForeground(Theme.textMuted);
        note.setBorder(new EmptyBorder(4, 18, 12, 18));

        JPanel center2 = new JPanel(new BorderLayout());
        center2.setBackground(Theme.bg);
        center2.add(north, BorderLayout.NORTH);
        center2.add(scroll, BorderLayout.CENTER);
        center2.add(note, BorderLayout.SOUTH);

        dialog.add(header, BorderLayout.NORTH);
        dialog.add(center2, BorderLayout.CENTER);

        // ---- refresh logic ----
        final List<Participant> shown = new ArrayList<>(); // same order as the table rows
        Runnable refresh = () -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item == null) return;
            DataStore.autoEndIfDone(item.code);
            QuizSession s = DataStore.loadSession(item.code);
            if (s == null) {
                lblStatus.setText("DELETED");
                return;
            }
            lblCode.setText(formatCode(s.code));
            lblSessionTitle.setText(s.title + "  •  " + s.category + "  •  " + s.questions.size() + " questions  •  "
                    + (s.timed ? s.secondsPerQuestion + "s each" : "self-paced") + (s.paced ? "  •  teacher-paced" : "")
                    + (s.autoEnd ? "  •  auto-end" : ""));

            if (Status.LOBBY.equals(s.status)) {
                lblStatus.setText("🟡  WAITING ROOM");
                lblStatus.setBackground(new Color(254, 243, 199));
                lblStatus.setForeground(new Color(146, 64, 14));
            } else if (Status.LIVE.equals(s.status)) {
                String where = s.paced
                        ? "  •  Q" + Math.min(s.currentIndex + 1, s.questions.size()) + " / " + s.questions.size()
                          + (s.currentIndex >= s.questions.size() ? " (all closed)" : "")
                        : "";
                lblStatus.setText("🟢  LIVE" + where);
                lblStatus.setBackground(new Color(220, 252, 231));
                lblStatus.setForeground(new Color(22, 101, 52));
            } else {
                lblStatus.setText("⚫  ENDED");
                lblStatus.setBackground(new Color(226, 232, 240));
                lblStatus.setForeground(new Color(51, 65, 85));
            }
            btnStart.setEnabled(Status.LOBBY.equals(s.status));
            btnEnd.setEnabled(!Status.ENDED.equals(s.status));
            btnDeleteSession.setEnabled(Status.ENDED.equals(s.status));
            btnNextQ.setEnabled(s.paced && Status.LIVE.equals(s.status) && s.currentIndex < s.questions.size());
            btnNextQ.setText(s.paced && s.currentIndex >= s.questions.size() - 1 ? "🏁 Close Last Q" : "⏭ Next Question");

            // remember the selected student, because the table is rebuilt every refresh
            int selectedRow = table.getSelectedRow();
            String selectedUser = (selectedRow >= 0 && selectedRow < shown.size()) ? shown.get(selectedRow).username : null;

            List<Participant> parts = DataStore.listParticipants(s.code);
            parts.sort(Comparator.comparingInt((Participant p) -> -p.score).thenComparing((Participant p) -> p.displayName));
            shown.clear();
            shown.addAll(parts);

            int inLobby = 0, playing = 0, finished = 0, left = 0;
            model.setRowCount(0);
            int rank = 1;
            for (Participant p : parts) {
                String status;
                String progress;
                switch (p.status) {
                    case Status.PLAYING:
                        playing++;
                        status = "▶ Playing";
                        progress = "Q" + Math.min(p.answered + 1, Math.max(1, p.total)) + " / " + p.total;
                        break;
                    case Status.FINISHED:
                        finished++;
                        status = "✅ Finished";
                        progress = p.answered + " / " + p.total;
                        break;
                    case Status.LEFT:
                        left++;
                        status = "⚠ Left quiz";
                        progress = p.answered + " / " + p.total + " answered";
                        break;
                    default:
                        inLobby++;
                        status = "⏳ In lobby";
                        progress = "—";
                        break;
                }
                String away = p.awayCount > 0 ? p.awayCount + "× (" + p.awaySeconds + "s)" : "—";
                String leftText = p.leftCount > 0
                        ? p.leftCount + "× (last at " + (p.leftAtQuestion == 0 ? "lobby" : "Q" + p.leftAtQuestion) + ")"
                          + (p.rejoinCount > 0 ? " • rejoined " + p.rejoinCount + "×" : "")
                        : "—";
                model.addRow(new Object[]{rank++, p.displayName, status, progress, p.score, p.correct, p.bestStreak, away, leftText, ago(p.updatedAt)});
            }
            lblJoined.setText("⏳ In lobby: " + inLobby);
            lblPlaying.setText("▶ Playing: " + playing);
            lblFinished.setText("✅ Finished: " + finished);
            lblLeft.setText("⚠ Left: " + left);

            if (selectedUser != null) {
                for (int i = 0; i < shown.size(); i++) {
                    if (shown.get(i).username.equals(selectedUser)) {
                        table.setRowSelectionInterval(i, i);
                        break;
                    }
                }
            }
        };

        Runnable openDetail = () -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            int row = table.getSelectedRow();
            if (item == null || row < 0 || row >= shown.size()) {
                JOptionPane.showMessageDialog(dialog, "Select a student in the table first.");
                return;
            }
            QuizSession s = DataStore.loadSession(item.code);
            Participant fresh = DataStore.loadParticipant(item.code, shown.get(row).username);
            if (s == null || fresh == null) return;
            new StudentDetailDialog(dialog, s, fresh).setVisible(true);
        };

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.rowAtPoint(e.getPoint()) >= 0) openDetail.run();
            }
        });

        Timer autoRefresh = new Timer(2000, e -> refresh.run());
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                autoRefresh.stop();
                if (monitorDialog == dialog) monitorDialog = null;
            }
        });

        cmbSessions.addActionListener(e -> refresh.run());

        btnCopy.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(item.code), null);
                JOptionPane.showMessageDialog(dialog, "Code " + formatCode(item.code) + " copied to the clipboard.", "Copied", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        btnBig.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item != null) showCodeOnProjector(item.code);
        });

        btnProjector.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item != null) new ProjectorDialog(dialog, item.code).setVisible(true);
        });

        btnReport.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item != null) showClassReport(dialog, item.code);
        });

        btnPodium.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item != null) showPodiumDialog(dialog, item.code);
        });

        btnStart.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item != null) {
                DataStore.setSessionStatus(item.code, Status.LIVE);
                refresh.run();
            }
        });

        btnNextQ.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item != null) {
                DataStore.advanceQuestion(item.code);
                refresh.run();
            }
        });

        btnEnd.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item == null) return;
            int confirm = JOptionPane.showConfirmDialog(dialog,
                    "End this quiz for everyone?\nStudents who are still playing will finish with the questions they have answered.",
                    "End Quiz", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                DataStore.setSessionStatus(item.code, Status.ENDED);
                refresh.run();
            }
        });

        btnDetail.addActionListener(e -> openDetail.run());

        btnRejoin.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            int row = table.getSelectedRow();
            if (item == null || row < 0 || row >= shown.size()) {
                JOptionPane.showMessageDialog(dialog, "Select a student in the table first.");
                return;
            }
            Participant p = shown.get(row);
            QuizSession s = DataStore.loadSession(item.code);
            if (s == null || Status.ENDED.equals(s.status)) {
                JOptionPane.showMessageDialog(dialog, "This session has already ended, so nobody can rejoin.");
                return;
            }
            if (!Status.LEFT.equals(p.status) && !Status.PLAYING.equals(p.status)) {
                JOptionPane.showMessageDialog(dialog, "Only students who left (or whose app crashed while playing) can be let back in.");
                return;
            }
            int ok = JOptionPane.showConfirmDialog(dialog,
                    "Let " + p.displayName + " rejoin?\nThey restart from question 1. The switched-away / left history is kept.",
                    "Allow Rejoin", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (ok == JOptionPane.YES_OPTION) {
                DataStore.allowRejoin(item.code, p.username);
                refresh.run();
            }
        });

        btnRemove.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            int row = table.getSelectedRow();
            if (item == null || row < 0 || row >= shown.size()) {
                JOptionPane.showMessageDialog(dialog, "Select a student in the table first.");
                return;
            }
            Participant p = shown.get(row);
            int ok = JOptionPane.showConfirmDialog(dialog,
                    "Remove " + p.displayName + " from this session?\nTheir progress here is deleted.",
                    "Remove Student", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok == JOptionPane.YES_OPTION) {
                DataStore.deleteParticipant(item.code, p.username);
                refresh.run();
            }
        });

        btnStats.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item != null) displayQuestionStatsDialog(dialog, item.code);
        });

        wireDeleteSession(btnDeleteSession, cmbSessions, dialog, refresh);

        btnAttendance.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            QuizSession s = (item == null) ? null : DataStore.loadSession(item.code);
            if (s == null) return;
            writeTextFile(dialog, new File("session_" + item.code + "_attendance.csv"),
                    ClassReport.attendanceCsv(s, DataStore.listParticipants(item.code)));
        });

        btnExport.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            QuizSession s = (item == null) ? null : DataStore.loadSession(item.code);
            if (s == null) return;
            StringBuilder sb = new StringBuilder();
            sb.append("Student,Username,Status,Answered,Total,Score,Correct,Accuracy,BestStreak,SwitchedAwayCount,SwitchedAwaySeconds,LeftCount,LastLeftAtQuestion,Rejoins,TimeTakenSeconds\n");
            List<Participant> parts = DataStore.listParticipants(item.code);
            for (Participant p : parts) {
                sb.append(csvEscape(p.displayName)).append(',').append(csvEscape(p.username)).append(',').append(p.status).append(',')
                  .append(p.answered).append(',').append(p.total).append(',').append(p.score).append(',').append(p.correct).append(',')
                  .append(String.format(Locale.US, "%.1f", p.accuracy)).append(',').append(p.bestStreak).append(',')
                  .append(p.awayCount).append(',').append(p.awaySeconds).append(',').append(p.leftCount).append(',')
                  .append(p.leftAtQuestion).append(',').append(p.rejoinCount).append(',').append(p.timeTakenSeconds).append('\n');
            }
            sb.append('\n').append("Question #,Topic,Level,Type,Answered,Correct,Percent Correct,Question\n");
            for (QuestionStat st : DataStore.questionStats(s, parts)) {
                sb.append(st.number).append(',').append(csvEscape(st.question.category)).append(',')
                  .append(difficultyLabel(st.question.difficulty)).append(',').append(typeLabel(st.question)).append(',')
                  .append(st.answered).append(',').append(st.correct).append(',')
                  .append(st.percent() < 0 ? "" : st.percent() + "%").append(',').append(csvEscape(st.question.text)).append('\n');
            }
            writeTextFile(dialog, new File("session_" + item.code + "_results.csv"), sb.toString());
        });

        refresh.run();
        autoRefresh.start();
        dialog.setVisible(true);
    }

    private void wireDeleteSession(RoundedButton btn, JComboBox<SessionItem> cmbSessions, JDialog dialog, Runnable refresh) {
        btn.addActionListener(e -> {
            SessionItem item = (SessionItem) cmbSessions.getSelectedItem();
            if (item == null) return;
            int confirm = JOptionPane.showConfirmDialog(dialog,
                    "Delete session " + formatCode(item.code) + " and all its student results?\nThis cannot be undone.",
                    "Delete Session", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
            DataStore.deleteSession(item.code);
            cmbSessions.removeItem(item);
            if (cmbSessions.getItemCount() == 0) {
                dialog.dispose();
            } else {
                refresh.run();
            }
        });
    }

    // ===================== QUESTION STATISTICS =====================

    /** Which questions (and which topics) the class struggled with, for one hosted quiz. */
    private void displayQuestionStatsDialog(Window owner, String code) {
        JDialog dialog = new JDialog(owner, "Question Statistics — " + formatCode(code), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setSize(900, 520);
        dialog.setLocationRelativeTo(owner);
        dialog.setLayout(new BorderLayout(10, 10));

        DefaultTableModel model = new DefaultTableModel(new String[]{"#", "Question", "Topic", "Level", "Answered", "Correct %"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(34);
        table.setFont(f(Font.PLAIN, 13));
        styleTable(table);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(360);
        table.getColumnModel().getColumn(4).setMaxWidth(80);
        table.getColumnModel().getColumn(5).setPreferredWidth(200);
        table.getColumnModel().getColumn(5).setCellRenderer(new PercentBarRenderer());

        JLabel lblSummary = new JLabel(" ");
        lblSummary.setFont(f(Font.BOLD, 13));
        lblSummary.setForeground(Theme.textDark);
        lblSummary.setBorder(new EmptyBorder(10, 16, 4, 16));

        Runnable reload = () -> {
            QuizSession s = DataStore.loadSession(code);
            model.setRowCount(0);
            if (s == null) {
                lblSummary.setText("This session no longer exists.");
                return;
            }
            List<QuestionStat> stats = DataStore.questionStats(s, DataStore.listParticipants(code));
            Map<String, int[]> topics = new TreeMap<>(); // topic -> {answered, correct}
            StringBuilder weakQuestions = new StringBuilder();
            for (QuestionStat st : stats) {
                model.addRow(new Object[]{st.number, st.question.text, st.question.category,
                        difficultyLabel(st.question.difficulty), st.answered, st});
                int[] t = topics.computeIfAbsent(st.question.category, k -> new int[2]);
                t[0] += st.answered;
                t[1] += st.correct;
                if (st.percent() >= 0 && st.percent() < 50) {
                    if (weakQuestions.length() > 0) weakQuestions.append(", ");
                    weakQuestions.append("Q").append(st.number);
                }
            }
            String weakestTopic = null;
            int weakestPct = 101;
            for (Map.Entry<String, int[]> en : topics.entrySet()) {
                if (en.getValue()[0] == 0) continue;
                int pct = (int) Math.round(en.getValue()[1] * 100.0 / en.getValue()[0]);
                if (pct < weakestPct) {
                    weakestPct = pct;
                    weakestTopic = en.getKey();
                }
            }
            if (weakestTopic == null) {
                lblSummary.setText("No answers yet — the statistics appear as students answer.");
            } else {
                lblSummary.setText("<html>Weakest topic: <span style='color:#ef4444'>" + escapeHtml(weakestTopic) + " (" + weakestPct + "% correct)</span>"
                        + (weakQuestions.length() > 0 ? "  •  Needs review (under 50%): " + weakQuestions : "  •  No question is under 50%") + "</html>");
            }
        };
        reload.run();

        RoundedButton btnRefresh = new RoundedButton("🔄 Refresh", ACCENT_BLUE, ACCENT_HOVER, 12);
        btnRefresh.setPreferredSize(new Dimension(120, 34));
        btnRefresh.addActionListener(e -> reload.run());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
        bar.add(btnRefresh);

        dialog.add(lblSummary, BorderLayout.NORTH);
        dialog.add(scrollOf(table), BorderLayout.CENTER);
        dialog.add(bar, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    // ===================== PODIUM =====================

    /** Podium of the top 3 finishers of a hosted quiz. Refreshes every 2s while others are still playing. */
    private void showPodiumDialog(Window owner, String code) {
        JDialog d = new JDialog(owner, "Podium — " + formatCode(code), Dialog.ModalityType.APPLICATION_MODAL);
        d.setSize(780, 640);
        d.setLocationRelativeTo(owner);

        ModernGradientPanel bg = new ModernGradientPanel(new Color(30, 27, 75), new Color(126, 34, 206));
        bg.setLayout(new BorderLayout(0, 12));
        bg.setBorder(new EmptyBorder(20, 30, 20, 30));

        JLabel lblTitle = new JLabel("🏆 Podium", SwingConstants.CENTER);
        lblTitle.setFont(f(Font.BOLD, 30));
        lblTitle.setForeground(Color.WHITE);
        JLabel lblState = new JLabel(" ", SwingConstants.CENTER);
        lblState.setFont(f(Font.PLAIN, 13));
        lblState.setForeground(new Color(199, 210, 254));
        JPanel top = new JPanel(new GridLayout(2, 1, 0, 2));
        top.setOpaque(false);
        top.add(lblTitle);
        top.add(lblState);

        JPanel podium = new JPanel(new GridLayout(1, 3, 14, 0));
        podium.setOpaque(false);
        podium.setPreferredSize(new Dimension(100, 270));

        DefaultTableModel model = new DefaultTableModel(new String[]{"Rank", "Student", "Score", "Correct", "Time (s)"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(28);
        table.setFont(f(Font.PLAIN, 13));
        styleTable(table);
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 0; i < table.getColumnCount(); i++) table.getColumnModel().getColumn(i).setCellRenderer(center);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(250);
        JScrollPane scroll = scrollOf(table);
        scroll.setPreferredSize(new Dimension(100, 200));

        final Color[] medalColors = {new Color(250, 204, 21), new Color(203, 213, 225), new Color(217, 119, 6)};
        final String[] medals = {"🥇", "🥈", "🥉"};
        final int[] heights = {220, 175, 140};

        Runnable refresh = () -> {
            DataStore.autoEndIfDone(code);
            QuizSession s = DataStore.loadSession(code);
            List<Participant> finished = new ArrayList<>();
            int stillPlaying = 0;
            for (Participant p : DataStore.listParticipants(code)) {
                if (Status.FINISHED.equals(p.status)) finished.add(p);
                else if (Status.PLAYING.equals(p.status)) stillPlaying++;
            }
            finished.sort((a, b) -> a.score != b.score ? Integer.compare(b.score, a.score)
                                                      : Long.compare(a.timeTakenSeconds, b.timeTakenSeconds));

            boolean ended = (s == null || Status.ENDED.equals(s.status));
            lblState.setText(finished.isEmpty() ? "Nobody has finished yet..."
                    : (stillPlaying > 0 && !ended) ? "Still playing: " + stillPlaying + " — the podium updates live"
                    : "Final results");

            podium.removeAll();
            int[] visualOrder = {1, 0, 2}; // 2nd | 1st | 3rd
            for (int idx : visualOrder) {
                JPanel column = new JPanel(new BorderLayout());
                column.setOpaque(false);
                if (idx < finished.size()) {
                    Participant p = finished.get(idx);
                    boolean me = currentUser != null && p.username.equals(currentUser.username);

                    RoundedPanel block = new RoundedPanel(20, medalColors[idx], false);
                    block.setLayout(new GridLayout(4, 1, 0, 2));
                    block.setBorder(new EmptyBorder(10, 8, 10, 8));
                    block.setPreferredSize(new Dimension(100, heights[idx]));

                    JLabel lblMedal = new JLabel(medals[idx], SwingConstants.CENTER);
                    lblMedal.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 40));
                    JLabel lblName = new JLabel("<html><div style='text-align:center'>" + escapeHtml(p.displayName)
                            + (me ? " (you)" : "") + "</div></html>", SwingConstants.CENTER);
                    lblName.setFont(f(Font.BOLD, 15));
                    lblName.setForeground(new Color(30, 41, 59));
                    JLabel lblScore = new JLabel(p.score + " pts", SwingConstants.CENTER);
                    lblScore.setFont(f(Font.BOLD, 20));
                    lblScore.setForeground(new Color(30, 41, 59));
                    JLabel lblInfo = new JLabel(p.correct + "/" + p.total + " correct • " + p.timeTakenSeconds + "s", SwingConstants.CENTER);
                    lblInfo.setFont(f(Font.PLAIN, 11));
                    lblInfo.setForeground(new Color(51, 65, 85));

                    block.add(lblMedal);
                    block.add(lblName);
                    block.add(lblScore);
                    block.add(lblInfo);
                    column.add(block, BorderLayout.SOUTH);
                }
                podium.add(column);
            }
            podium.revalidate();
            podium.repaint();

            model.setRowCount(0);
            for (int i = 3; i < finished.size(); i++) {
                Participant p = finished.get(i);
                boolean me = currentUser != null && p.username.equals(currentUser.username);
                model.addRow(new Object[]{i + 1, p.displayName + (me ? " (you)" : ""), p.score, p.correct + "/" + p.total, p.timeTakenSeconds});
            }
        };
        refresh.run();

        RoundedButton btnClose = new RoundedButton("Close", new Color(255, 255, 255, 55), new Color(255, 255, 255, 95), 14);
        btnClose.setPreferredSize(new Dimension(120, 38));
        btnClose.addActionListener(e -> d.dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        bottom.setOpaque(false);
        bottom.add(btnClose);

        JPanel middle = new JPanel(new BorderLayout(0, 12));
        middle.setOpaque(false);
        middle.add(podium, BorderLayout.NORTH);
        middle.add(scroll, BorderLayout.CENTER);

        bg.add(top, BorderLayout.NORTH);
        bg.add(middle, BorderLayout.CENTER);
        bg.add(bottom, BorderLayout.SOUTH);
        d.add(bg);

        Timer auto = new Timer(2000, e -> refresh.run());
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { auto.stop(); }
        });
        bindKey(d.getRootPane(), KeyEvent.VK_ESCAPE, d::dispose);
        auto.start();
        d.setVisible(true);
    }

    // ===================== FLASHCARD STUDY MODE =====================

    private void displayFlashcardModal() {
        List<String> sources = new ArrayList<>(getCategoryNames());
        sources.add(BOOKMARK_SOURCE);
        JComboBox<String> cmbSource = new JComboBox<>(sources.toArray(new String[0]));
        JCheckBox chkShuffle = new JCheckBox("Shuffle cards", true);

        Object[] fields = {"Study from:", cmbSource, chkShuffle};
        int option = JOptionPane.showConfirmDialog(this, fields, "Flashcard Study Mode", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (option != JOptionPane.OK_OPTION) return;

        String source = (String) cmbSource.getSelectedItem();
        List<Question> cards = new ArrayList<>();
        if (BOOKMARK_SOURCE.equals(source)) {
            cards.addAll(bookmarkedQuestions);
        } else {
            Map<Integer, List<Question>> pool = questionBank.get(source);
            if (pool != null) {
                for (int d = 1; d <= 3; d++) cards.addAll(pool.getOrDefault(d, Collections.emptyList()));
            }
        }

        if (cards.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no cards in that deck yet!", "Empty Deck", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (chkShuffle.isSelected()) Collections.shuffle(cards);

        JDialog dialog = new JDialog(this, "Flashcard Study Mode", true);
        dialog.setSize(700, 500);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(Theme.bg);

        final int[] index = {0};
        final boolean[] showingAnswer = {false};

        JLabel lblProgress = themedLabel("", Font.BOLD, 14);
        JLabel lblMeta = new JLabel("", SwingConstants.RIGHT);
        lblMeta.setFont(f(Font.PLAIN, 12));
        lblMeta.setForeground(Theme.textMuted);

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);
        topBar.setBorder(new EmptyBorder(12, 22, 0, 22));
        topBar.add(lblProgress, BorderLayout.WEST);
        topBar.add(lblMeta, BorderLayout.EAST);

        JLabel lblSide = new JLabel("QUESTION", SwingConstants.CENTER);
        lblSide.setFont(f(Font.BOLD, 12));

        JLabel lblCardText = new JLabel("", SwingConstants.CENTER);
        lblCardText.setFont(f(Font.BOLD, 20));
        lblCardText.setForeground(Theme.textDark);
        lblCardText.setHorizontalTextPosition(SwingConstants.CENTER);
        lblCardText.setVerticalTextPosition(SwingConstants.BOTTOM);
        lblCardText.setIconTextGap(10);

        RoundedPanel cardPanel = new RoundedPanel(26, Theme.card, true);
        cardPanel.setLayout(new BorderLayout(0, 10));
        cardPanel.setBorder(new EmptyBorder(22, 28, 30, 34));
        cardPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        cardPanel.add(lblSide, BorderLayout.NORTH);
        cardPanel.add(lblCardText, BorderLayout.CENTER);

        JPanel cardWrapper = new JPanel(new BorderLayout());
        cardWrapper.setOpaque(false);
        cardWrapper.setBorder(new EmptyBorder(5, 22, 5, 22));
        cardWrapper.add(cardPanel, BorderLayout.CENTER);

        RoundedButton btnPrev = new RoundedButton("◀ Previous", new Color(71, 85, 105), new Color(51, 65, 85), 14);
        RoundedButton btnFlip = new RoundedButton("🔄 Flip Card", new Color(124, 58, 237), new Color(109, 40, 217), 14);
        RoundedButton btnNextCard = new RoundedButton("Next ▶", new Color(71, 85, 105), new Color(51, 65, 85), 14);
        RoundedButton btnMark = new RoundedButton("⭐ Bookmark", new Color(59, 130, 246), new Color(37, 99, 235), 14);
        for (RoundedButton b : new RoundedButton[]{btnPrev, btnFlip, btnNextCard, btnMark}) {
            b.setPreferredSize(new Dimension(140, 38));
            b.setFocusable(false);
        }

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        buttonBar.setOpaque(false);
        buttonBar.setBorder(new EmptyBorder(0, 0, 12, 0));
        buttonBar.add(btnPrev);
        buttonBar.add(btnFlip);
        buttonBar.add(btnNextCard);
        buttonBar.add(btnMark);

        Runnable refresh = () -> {
            Question q = cards.get(index[0]);
            lblProgress.setText("Card " + (index[0] + 1) + " of " + cards.size());
            lblMeta.setText(q.category + "  •  " + difficultyLabel(q.difficulty));

            if (!showingAnswer[0]) {
                cardPanel.setBackground(Theme.card);
                lblSide.setText("QUESTION  (click the card, press Space, or Flip to reveal)");
                lblSide.setForeground(Theme.textMuted);
                lblCardText.setForeground(Theme.textDark);
                lblCardText.setIcon(q.image == null ? null : loadQuestionImage(q.image, 360, 130));
                lblCardText.setText("<html><body style='width: 500px; text-align: center;'>"
                        + escapeHtml(q.text) + "</body></html>");
            } else {
                cardPanel.setBackground(Theme.answerBg);
                lblSide.setText("ANSWER");
                lblSide.setForeground(Theme.answerFg);
                lblCardText.setForeground(Theme.dark ? Theme.answerFg : Theme.textDark);
                lblCardText.setIcon(null);
                String answer = escapeHtml(q.options[q.correctOption - 1]);
                String hintHtml = (q.hint != null && !q.hint.isEmpty())
                        ? "<br><br><span style='font-size:12px; color:gray;'><i>Hint: " + escapeHtml(q.hint) + "</i></span>"
                        : "";
                lblCardText.setText("<html><body style='width: 500px; text-align: center;'>"
                        + answer + hintHtml + "</body></html>");
            }
            btnMark.setBackground(bookmarkedQuestions.contains(q) ? new Color(234, 179, 8) : new Color(59, 130, 246));
            cardPanel.repaint();
        };

        Runnable flip = () -> { showingAnswer[0] = !showingAnswer[0]; refresh.run(); };
        Runnable next = () -> { index[0] = (index[0] + 1) % cards.size(); showingAnswer[0] = false; refresh.run(); };
        Runnable prev = () -> { index[0] = (index[0] - 1 + cards.size()) % cards.size(); showingAnswer[0] = false; refresh.run(); };

        btnFlip.addActionListener(e -> flip.run());
        btnNextCard.addActionListener(e -> next.run());
        btnPrev.addActionListener(e -> prev.run());
        btnMark.addActionListener(e -> { toggleBookmark(cards.get(index[0])); refresh.run(); });
        cardPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) { flip.run(); }
        });

        bindKey(dialog.getRootPane(), KeyEvent.VK_SPACE, flip);
        bindKey(dialog.getRootPane(), KeyEvent.VK_RIGHT, next);
        bindKey(dialog.getRootPane(), KeyEvent.VK_LEFT, prev);

        refresh.run();

        dialog.add(topBar, BorderLayout.NORTH);
        dialog.add(cardWrapper, BorderLayout.CENTER);
        dialog.add(buttonBar, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    // ===================== BOOKMARKS / ANALYTICS =====================

    private void displayBookmarksModal() {
        if (bookmarkedQuestions.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No questions bookmarked yet!", "Bookmarks Empty", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(this, "Bookmarked Questions for Review", true);
        dialog.setSize(740, 400);
        dialog.setLocationRelativeTo(this);

        String[] columns = {"Category", "Difficulty", "Question Text", "Correct Answer"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (Question q : bookmarkedQuestions) {
            model.addRow(new Object[]{q.category, difficultyLabel(q.difficulty), q.text, q.options[q.correctOption - 1]});
        }

        JTable table = new JTable(model);
        table.setRowHeight(35);
        table.setFont(f(Font.PLAIN, 12));
        styleTable(table);

        dialog.add(scrollOf(table));
        dialog.setVisible(true);
    }

    private void displayAnalyticsModal() {
        JDialog dialog = new JDialog(this, "Visual Analytics Dashboard", true);
        dialog.setSize(520, 420);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(Theme.card);

        loadLeaderboardData();
        int totalAttempts = leaderboard.size();
        int totalQuestionsAll = 0;
        int totalCorrectEstimated = 0;
        int bestStreakEver = 0;
        for (StudentResult r : leaderboard) {
            int tq = (r.totalQuestions > 0) ? r.totalQuestions : 4; // older records had a fixed 4 questions
            totalQuestionsAll += tq;
            totalCorrectEstimated += (int) Math.round((r.accuracy / 100.0) * tq);
            bestStreakEver = Math.max(bestStreakEver, r.bestStreak);
        }
        int totalIncorrectEstimated = totalQuestionsAll - totalCorrectEstimated;

        AnalyticsChartPanel chartPanel = new AnalyticsChartPanel(totalCorrectEstimated, Math.max(0, totalIncorrectEstimated));

        JPanel infoPanel = new JPanel(new GridLayout(3, 1, 5, 5));
        infoPanel.setBorder(new EmptyBorder(10, 20, 15, 20));
        infoPanel.setBackground(Theme.card);

        double avgScore = leaderboard.stream().mapToInt(r -> r.score).average().orElse(0.0);
        double avgAccuracy = leaderboard.stream().mapToDouble(r -> r.accuracy).average().orElse(0.0);

        JLabel lblSummary1 = new JLabel("👥 Total Attempts: " + totalAttempts + " | ⭐ Avg Score: " + String.format("%.1f pts", avgScore));
        JLabel lblSummary2 = new JLabel("🎯 Overall System Accuracy: " + String.format("%.1f%%", avgAccuracy));
        JLabel lblSummary3 = new JLabel("🔥 Best Streak Ever: " + bestStreakEver);
        for (JLabel l : new JLabel[]{lblSummary1, lblSummary2, lblSummary3}) {
            l.setFont(f(Font.BOLD, 13));
            l.setForeground(Theme.textDark);
            infoPanel.add(l);
        }

        dialog.add(chartPanel, BorderLayout.CENTER);
        dialog.add(infoPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    // ===================== LEADERBOARD =====================

    /** mineOnly = the logged-in student's own history. Only teachers get the delete / clear / export tools. */
    private void displayLeaderboardModal(boolean mineOnly) {
        loadLeaderboardData();
        final boolean manage = currentUser != null && currentUser.isTeacher() && !mineOnly;

        JDialog dialog = new JDialog(this,
                mineOnly ? "My Results" : (manage ? "Leaderboard Rankings & Management" : "Leaderboard"), true);
        dialog.setSize(980, 480);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        topPanel.setBorder(new EmptyBorder(5, 10, 0, 10));

        JComboBox<String> cmbFilter = new JComboBox<>(getCategoryNames().toArray(new String[0]));
        topPanel.add(new JLabel("Category:"));
        topPanel.add(cmbFilter);

        RoundedButton btnExportCSV = new RoundedButton("📁 Export CSV", new Color(16, 185, 129), new Color(5, 150, 105), 8);
        RoundedButton btnDeleteSelected = new RoundedButton("🗑 Delete Selected", DANGER_RED, DANGER_HOVER, 8);
        RoundedButton btnClearAll = new RoundedButton("⚠️ Clear All", new Color(153, 27, 27), new Color(127, 29, 29), 8);
        btnExportCSV.setPreferredSize(new Dimension(120, 32));
        btnDeleteSelected.setPreferredSize(new Dimension(150, 32));
        btnClearAll.setPreferredSize(new Dimension(110, 32));
        if (manage) {
            topPanel.add(btnExportCSV);
            topPanel.add(btnDeleteSelected);
            topPanel.add(btnClearAll);
        }

        String[] columns = {"Rank", "Student Name", "Category", "Mode", "Score (pts)", "Accuracy (%)", "Best Streak", "Time (sec)", "Date/Time"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(32);
        table.setFont(f(Font.PLAIN, 12));
        styleTable(table);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }

        List<StudentResult> currentFilteredList = new ArrayList<>();

        Runnable updateTableData = () -> {
            model.setRowCount(0);
            currentFilteredList.clear();
            Collections.sort(leaderboard);
            String selectedFilter = (String) cmbFilter.getSelectedItem();

            int rank = 1;
            for (StudentResult res : leaderboard) {
                if (mineOnly && !currentUser.username.equals(res.username)) continue;
                if (selectedFilter.equals(ALL_CATEGORIES) || res.category.equalsIgnoreCase(selectedFilter)) {
                    currentFilteredList.add(res);
                    model.addRow(new Object[]{
                        rank++, res.name, res.category, modeOf(res), res.score,
                        String.format("%.1f%%", res.accuracy), res.bestStreak, res.timeTakenSeconds,
                        res.dateFormatted != null ? res.dateFormatted : "N/A"
                    });
                }
            }
        };

        cmbFilter.addActionListener(e -> updateTableData.run());
        updateTableData.run();

        btnDeleteSelected.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow != -1) {
                StudentResult itemToDelete = currentFilteredList.get(selectedRow);
                int confirm = JOptionPane.showConfirmDialog(dialog,
                        "Are you sure you want to delete the record for: " + itemToDelete.name + "?",
                        "Confirm Deletion", JOptionPane.YES_NO_OPTION);

                if (confirm == JOptionPane.YES_OPTION) {
                    DataStore.deleteResult(itemToDelete.id);
                    leaderboard.remove(itemToDelete);
                    updateTableData.run();
                    JOptionPane.showMessageDialog(dialog, "Record successfully deleted.");
                }
            } else {
                JOptionPane.showMessageDialog(dialog, "Please select a student row from the table first.", "Selection Required", JOptionPane.WARNING_MESSAGE);
            }
        });

        btnClearAll.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(dialog,
                    "WARNING: This will delete ALL student records in the leaderboard!\nDo you wish to proceed?",
                    "Clear All Leaderboard", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                DataStore.clearResults();
                leaderboard.clear();
                updateTableData.run();
                JOptionPane.showMessageDialog(dialog, "Leaderboard has been completely cleared.");
            }
        });

        btnExportCSV.addActionListener(e -> {
            StringBuilder sb = new StringBuilder("Rank,Student Name,Category,Mode,Score,Accuracy,BestStreak,TimeTakenSeconds,Date\n");
            int rIdx = 1;
            for (StudentResult res : currentFilteredList) {
                sb.append(rIdx++).append(',').append(csvEscape(res.name)).append(',').append(csvEscape(res.category)).append(',')
                  .append(csvEscape(modeOf(res))).append(',').append(res.score).append(',')
                  .append(String.format(Locale.US, "%.1f%%", res.accuracy)).append(',')
                  .append(res.bestStreak).append(',').append(res.timeTakenSeconds).append(',')
                  .append(csvEscape(res.dateFormatted)).append('\n');
            }
            writeTextFile(dialog, new File("leaderboard_export.csv"), sb.toString());
        });

        JScrollPane scrollPane = scrollOf(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        dialog.add(topPanel, BorderLayout.NORTH);
        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.setVisible(true);
    }

    // ===================== QUIZ SETS (save & reuse) =====================

    private void displayQuizSetsDialog() {
        JDialog dialog = new JDialog(this, "Quiz Sets", true);
        dialog.setSize(780, 470);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(Theme.bg);

        JLabel info = new JLabel("<html>A quiz set is a saved list of questions. Host it again and again: "
                + "every class gets exactly the same questions (easy → hard).</html>");
        info.setFont(f(Font.PLAIN, 12));
        info.setForeground(Theme.textMuted);
        info.setBorder(new EmptyBorder(12, 16, 0, 16));

        DefaultTableModel model = new DefaultTableModel(new String[]{"Name", "Questions", "Topics", "Created"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(30);
        table.setFont(f(Font.PLAIN, 13));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        styleTable(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setMaxWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);

        final List<QuizSet> shown = new ArrayList<>();
        Runnable reload = () -> {
            model.setRowCount(0);
            shown.clear();
            for (QuizSet qs : quizSets) {
                shown.add(qs);
                List<Question> qq = resolveSet(qs);
                Set<String> cats = new TreeSet<>();
                for (Question q : qq) cats.add(q.category);
                model.addRow(new Object[]{qs.name, qq.size(), String.join(", ", cats), new Date(qs.createdAt)});
            }
        };
        reload.run();

        RoundedButton btnNew = new RoundedButton("➕ New Set", ACCENT_BLUE, ACCENT_HOVER, 12);
        RoundedButton btnEdit = new RoundedButton("✏ Edit", new Color(217, 119, 6), new Color(180, 83, 9), 12);
        RoundedButton btnDelete = new RoundedButton("🗑 Delete", DANGER_RED, DANGER_HOVER, 12);
        RoundedButton btnHost = new RoundedButton("🚀 Host This Set", ACCENT_PINK, ACCENT_PINK_HOVER, 12);
        btnNew.setPreferredSize(new Dimension(130, 36));
        btnEdit.setPreferredSize(new Dimension(110, 36));
        btnDelete.setPreferredSize(new Dimension(110, 36));
        btnHost.setPreferredSize(new Dimension(170, 36));

        btnNew.addActionListener(e -> {
            QuizSet created = showSetEditor(dialog, null);
            if (created != null) {
                quizSets.add(created);
                saveQuizSets();
                reload.run();
            }
        });

        btnEdit.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(dialog, "Select a set first."); return; }
            if (showSetEditor(dialog, shown.get(row)) != null) {
                saveQuizSets();
                reload.run();
            }
        });

        btnDelete.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(dialog, "Select a set first."); return; }
            QuizSet target = shown.get(row);
            int ok = JOptionPane.showConfirmDialog(dialog, "Delete the quiz set '" + target.name + "'?\n(The questions stay in the question bank.)",
                    "Delete Quiz Set", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok == JOptionPane.YES_OPTION) {
                quizSets.remove(target);
                saveQuizSets();
                reload.run();
            }
        });

        btnHost.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(dialog, "Select a set first."); return; }
            final QuizSet target = shown.get(row);
            dialog.dispose();
            SwingUtilities.invokeLater(() -> displayHostQuizDialog(target));
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        bar.setOpaque(false);
        bar.add(btnNew);
        bar.add(btnEdit);
        bar.add(btnDelete);
        bar.add(btnHost);

        JScrollPane sp = scrollOf(table);
        sp.setBorder(new EmptyBorder(8, 14, 0, 14));
        dialog.add(info, BorderLayout.NORTH);
        dialog.add(sp, BorderLayout.CENTER);
        dialog.add(bar, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    /** Pick questions with check boxes and name the set. Returns the new/changed set, or null if cancelled. */
    private QuizSet showSetEditor(Window owner, QuizSet existing) {
        final QuizSet[] result = {null};
        final Set<Integer> selected = new HashSet<>();
        if (existing != null) selected.addAll(existing.questionIds);

        JDialog d = new JDialog(owner, existing == null ? "New Quiz Set" : "Edit Quiz Set", Dialog.ModalityType.APPLICATION_MODAL);
        d.setSize(900, 560);
        d.setLocationRelativeTo(owner);
        d.setLayout(new BorderLayout(10, 10));
        d.getContentPane().setBackground(Theme.bg);

        JTextField txtName = new JTextField(existing == null ? "" : existing.name, 22);
        JComboBox<String> cmbFilter = new JComboBox<>(getCategoryNames().toArray(new String[0]));
        JLabel lblCount = themedLabel(" ", Font.BOLD, 13);
        JLabel lblErr = new JLabel(" ");
        lblErr.setForeground(new Color(220, 38, 38));
        lblErr.setFont(f(Font.BOLD, 12));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        top.setOpaque(false);
        top.add(themedLabel("Set name:", Font.BOLD, 12));
        top.add(txtName);
        top.add(themedLabel("Show:", Font.BOLD, 12));
        top.add(cmbFilter);
        top.add(lblCount);

        DefaultTableModel model = new DefaultTableModel(new String[]{"✔", "ID", "Category", "Level", "Type", "Question"}, 0) {
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(28);
        table.setFont(f(Font.PLAIN, 13));
        styleTable(table);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setMaxWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setMaxWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(100);
        table.getColumnModel().getColumn(5).setPreferredWidth(420);

        final boolean[] filling = {false};
        Runnable updateCount = () -> lblCount.setText("  " + selected.size() + " question(s) selected");
        Runnable fill = () -> {
            filling[0] = true;
            model.setRowCount(0);
            String filter = (String) cmbFilter.getSelectedItem();
            for (Question q : allQuestionsSorted()) {
                if (!ALL_CATEGORIES.equals(filter) && !q.category.equals(filter)) continue;
                model.addRow(new Object[]{selected.contains(q.id), q.id, q.category, difficultyLabel(q.difficulty),
                        typeLabel(q) + (q.image != null ? " 📷" : ""), q.text});
            }
            filling[0] = false;
            updateCount.run();
        };
        fill.run();

        model.addTableModelListener(ev -> {
            if (filling[0] || ev.getColumn() != 0) return;
            for (int r = Math.max(0, ev.getFirstRow()); r <= ev.getLastRow() && r < model.getRowCount(); r++) {
                int id = (Integer) model.getValueAt(r, 1);
                if (Boolean.TRUE.equals(model.getValueAt(r, 0))) selected.add(id);
                else selected.remove(id);
            }
            updateCount.run();
        });
        cmbFilter.addActionListener(e -> fill.run());

        RoundedButton btnAll = new RoundedButton("Select all shown", new Color(71, 85, 105), new Color(51, 65, 85), 12);
        RoundedButton btnNone = new RoundedButton("Clear shown", new Color(71, 85, 105), new Color(51, 65, 85), 12);
        RoundedButton btnSave = new RoundedButton("💾 Save Set", ACCENT_BLUE, ACCENT_HOVER, 12);
        RoundedButton btnCancel = new RoundedButton("Cancel", new Color(100, 116, 139), new Color(71, 85, 105), 12);
        btnAll.setPreferredSize(new Dimension(150, 34));
        btnNone.setPreferredSize(new Dimension(130, 34));
        btnSave.setPreferredSize(new Dimension(130, 36));
        btnCancel.setPreferredSize(new Dimension(100, 36));

        btnAll.addActionListener(e -> { for (int r = 0; r < model.getRowCount(); r++) model.setValueAt(true, r, 0); });
        btnNone.addActionListener(e -> { for (int r = 0; r < model.getRowCount(); r++) model.setValueAt(false, r, 0); });
        btnCancel.addActionListener(e -> d.dispose());

        btnSave.addActionListener(e -> {
            String name = Limits.clean(txtName.getText(), Limits.TITLE);
            if (name.isEmpty()) { lblErr.setText("⚠ Please give the set a name."); return; }
            if (selected.isEmpty()) { lblErr.setText("⚠ Tick at least one question."); return; }
            for (QuizSet other : quizSets) {
                if (other != existing && other.name.equalsIgnoreCase(name)) {
                    lblErr.setText("⚠ A set with that name already exists.");
                    return;
                }
            }
            QuizSet target = (existing != null) ? existing : new QuizSet();
            target.name = name;
            target.owner = (currentUser != null) ? currentUser.username : "";
            if (target.createdAt == 0) target.createdAt = System.currentTimeMillis();
            target.questionIds = new ArrayList<>();
            for (Question q : allQuestionsSorted()) {
                if (selected.contains(q.id)) target.questionIds.add(q.id);
            }
            result[0] = target;
            d.dispose();
        });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        left.setOpaque(false);
        left.add(btnAll);
        left.add(btnNone);
        left.add(lblErr);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        right.setOpaque(false);
        right.add(btnCancel);
        right.add(btnSave);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(left, BorderLayout.WEST);
        bottom.add(right, BorderLayout.EAST);

        JScrollPane sp = scrollOf(table);
        sp.setBorder(new EmptyBorder(0, 14, 0, 14));
        d.add(top, BorderLayout.NORTH);
        d.add(sp, BorderLayout.CENTER);
        d.add(bottom, BorderLayout.SOUTH);
        d.setVisible(true);
        return result[0];
    }

    private void removeQuestionFromSets(int questionId) {
        boolean changed = false;
        for (QuizSet qs : quizSets) {
            if (qs.questionIds.remove(Integer.valueOf(questionId))) changed = true;
        }
        if (changed) saveQuizSets();
    }

    // ===================== QUESTION EDITOR (add / edit / delete) =====================

    private void displayQuestionEditorModal() {
        JDialog dialog = new JDialog(this, "Question Bank Editor", true);
        dialog.setSize(960, 500);
        dialog.setLocationRelativeTo(this);

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));

        String[] columns = {"ID", "Category", "Difficulty", "Type", "Question Text", "Source"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(30);
        table.setFont(f(Font.PLAIN, 13));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        styleTable(table);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(420);
        table.getColumnModel().getColumn(5).setMaxWidth(80);

        final List<Question> shown = new ArrayList<>(); // same order as the table rows
        Runnable reload = () -> {
            model.setRowCount(0);
            shown.clear();
            for (String cat : getCategoryNames()) {
                if (cat.equals(ALL_CATEGORIES)) continue;
                for (int diff = 1; diff <= 3; diff++) {
                    for (Question q : questionBank.get(cat).get(diff)) {
                        shown.add(q);
                        model.addRow(new Object[]{q.id, q.category, difficultyLabel(diff),
                                typeLabel(q) + (q.image != null ? " 📷" : ""), q.text,
                                customQuestions.contains(q) ? "Custom" : "Built-in"});
                    }
                }
            }
        };
        reload.run();

        RoundedButton btnAdd = new RoundedButton("➕ Add Question", ACCENT_BLUE, ACCENT_HOVER, 12);
        RoundedButton btnEdit = new RoundedButton("✏ Edit Selected", new Color(217, 119, 6), new Color(180, 83, 9), 12);
        RoundedButton btnDelete = new RoundedButton("🗑 Delete Selected", DANGER_RED, DANGER_HOVER, 12);
        btnAdd.setPreferredSize(new Dimension(170, 36));
        btnEdit.setPreferredSize(new Dimension(170, 36));
        btnDelete.setPreferredSize(new Dimension(170, 36));

        btnAdd.addActionListener(e -> {
            Question q = showQuestionForm(dialog, null);
            if (q == null) return;
            String error = addCustomQuestion(q);
            if (error != null) {
                JOptionPane.showMessageDialog(dialog, "Could not add question: " + error, "Invalid Question", JOptionPane.ERROR_MESSAGE);
                return;
            }
            reload.run();
        });

        btnEdit.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(dialog, "Select a question first."); return; }
            Question target = shown.get(row);
            if (!customQuestions.contains(target)) {
                JOptionPane.showMessageDialog(dialog, "Built-in questions cannot be edited.\nUse \"Add Question\" to make your own version.");
                return;
            }
            Question edited = showQuestionForm(dialog, target);
            if (edited == null) return;
            applyEdit(target, edited);
            reload.run();
        });

        btnDelete.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(dialog, "Select a question first."); return; }
            Question target = shown.get(row);
            if (!customQuestions.contains(target)) {
                JOptionPane.showMessageDialog(dialog, "Built-in questions cannot be deleted.");
                return;
            }
            int ok = JOptionPane.showConfirmDialog(dialog, "Delete this question?\n\n" + target.text,
                    "Delete Question", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return;
            removeQuestionFromBank(target);
            bookmarkedQuestions.remove(target);
            customQuestions.remove(target);
            removeQuestionFromSets(target.id);
            saveCustomQuestions();
            reload.run();
        });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnPanel.add(btnAdd);
        btnPanel.add(btnEdit);
        btnPanel.add(btnDelete);
        panel.add(scrollOf(table), BorderLayout.CENTER);
        panel.add(btnPanel, BorderLayout.SOUTH);

        dialog.add(panel);
        dialog.setVisible(true);
    }

    private void addFormRow(JPanel form, int row, String label, JComponent comp) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        JLabel l = new JLabel(label);
        l.setFont(f(Font.BOLD, 12));
        form.add(l, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(comp, c);
    }

    /**
     * Shows the add/edit form (multiple choice or True/False, optional picture).
     * Returns the new question (same id when editing) or null if cancelled.
     */
    private Question showQuestionForm(Component parent, Question existing) {
        final boolean edit = existing != null;
        List<String> cats = new ArrayList<>(getCategoryNames());
        cats.remove(ALL_CATEGORIES);

        JComboBox<String> cmbType = new JComboBox<>(new String[]{"Multiple choice (4 options)", "True / False"});
        cmbType.setSelectedIndex(edit && existing.isTrueFalse() ? 1 : 0);
        JComboBox<String> cmbCategory = new JComboBox<>(cats.toArray(new String[0]));
        cmbCategory.setEditable(true);
        cmbCategory.setSelectedItem(edit ? existing.category : "Java Core");
        JComboBox<String> cmbDiff = new JComboBox<>(new String[]{"1 - Easy", "2 - Medium", "3 - Hard"});
        cmbDiff.setSelectedIndex(edit ? existing.difficulty - 1 : 1);
        JTextField txtText = new JTextField(edit ? existing.text : "", 36);
        JTextField[] txtOptions = new JTextField[4];
        for (int i = 0; i < 4; i++) {
            txtOptions[i] = new JTextField(edit && !existing.isTrueFalse() && i < existing.options.length ? existing.options[i] : "");
        }
        JComboBox<String> cmbCorrect = new JComboBox<>();
        JTextField txtHint = new JTextField(edit && existing.hint != null ? existing.hint : "");
        JLabel lblErr = new JLabel(" ");
        lblErr.setForeground(new Color(220, 38, 38));
        lblErr.setFont(f(Font.BOLD, 12));

        // ---- optional picture ----
        final String[] imageName = {edit ? existing.image : null};
        final File[] pending = {null};
        JLabel lblImage = new JLabel(imageName[0] == null ? "(no picture)" : imageName[0]);
        JButton btnChoose = new JButton("Choose picture...");
        JButton btnRemoveImage = new JButton("Remove");
        btnChoose.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(new File("."));
            chooser.setFileFilter(new FileNameExtensionFilter("Pictures (png, jpg, gif)", "png", "jpg", "jpeg", "gif"));
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                pending[0] = chooser.getSelectedFile();
                lblImage.setText(pending[0].getName());
            }
        });
        btnRemoveImage.addActionListener(e -> {
            pending[0] = null;
            imageName[0] = null;
            lblImage.setText("(no picture)");
        });
        JPanel imageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        imageRow.add(btnChoose);
        imageRow.add(btnRemoveImage);
        imageRow.add(lblImage);

        // ---- switching between multiple choice and True/False ----
        Runnable applyType = () -> {
            boolean tf = cmbType.getSelectedIndex() == 1;
            int previous = Math.max(0, cmbCorrect.getSelectedIndex());
            for (JTextField t : txtOptions) t.setEnabled(!tf);
            cmbCorrect.setModel(new DefaultComboBoxModel<>(tf ? new String[]{"True", "False"} : new String[]{"1", "2", "3", "4"}));
            cmbCorrect.setSelectedIndex(Math.min(previous, tf ? 1 : 3));
        };
        applyType.run();
        if (edit) cmbCorrect.setSelectedIndex(Math.min(existing.correctOption - 1, cmbCorrect.getItemCount() - 1));
        cmbType.addActionListener(e -> applyType.run());

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addFormRow(form, row++, "Question type:", cmbType);
        addFormRow(form, row++, "Category (pick or type a new one):", cmbCategory);
        addFormRow(form, row++, "Difficulty:", cmbDiff);
        addFormRow(form, row++, "Question text (max " + Limits.QUESTION + "):", txtText);
        addFormRow(form, row++, "Picture (optional):", imageRow);
        for (int i = 0; i < 4; i++) addFormRow(form, row++, "Option " + (i + 1) + ":", txtOptions[i]);
        addFormRow(form, row++, "Correct answer:", cmbCorrect);
        addFormRow(form, row++, "Hint (optional):", txtHint);
        GridBagConstraints ec = new GridBagConstraints();
        ec.gridy = row;
        ec.gridx = 0;
        ec.gridwidth = 2;
        ec.anchor = GridBagConstraints.WEST;
        form.add(lblErr, ec);

        int id = edit ? existing.id : nextQuestionId();
        while (true) {
            int result = JOptionPane.showConfirmDialog(parent, form, edit ? "Edit Question #" + id : "Add New Question",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return null;

            boolean tf = cmbType.getSelectedIndex() == 1;
            String[] opts = tf
                    ? new String[]{"True", "False"}
                    : new String[]{txtOptions[0].getText(), txtOptions[1].getText(), txtOptions[2].getText(), txtOptions[3].getText()};
            Question q = new Question(id, String.valueOf(cmbCategory.getEditor().getItem()).trim(), txtText.getText().trim(),
                    opts, cmbCorrect.getSelectedIndex() + 1, cmbDiff.getSelectedIndex() + 1, txtHint.getText().trim(),
                    tf ? Question.TYPE_TF : Question.TYPE_MC, imageName[0]);
            String error = validateQuestion(q);
            if (error != null) {
                lblErr.setText("⚠ " + error);
                continue;
            }
            if (pending[0] != null) {
                String stored = DataStore.importImage(pending[0]);
                if (stored == null) {
                    lblErr.setText("⚠ Could not copy that picture into the images folder");
                    continue;
                }
                q.image = stored;
                imageName[0] = stored;
                pending[0] = null;
                lblImage.setText(stored);
            }
            return q;
        }
    }

    /** Copies the edited values into the existing object (so bookmarks and quiz sets keep working) and re-files it in the bank. */
    private void applyEdit(Question target, Question edited) {
        removeQuestionFromBank(target);
        target.category = edited.category;
        target.text = edited.text;
        target.type = edited.type;
        target.image = edited.image;
        target.setOptions(edited.options);
        target.correctOption = edited.correctOption;
        target.difficulty = edited.difficulty;
        target.hint = edited.hint;
        addQuestionToBank(target.category, target);
        saveCustomQuestions();
    }

    // ===================== IMPORT (CSV / Aiken) =====================

    private void displayImportQuestionsDialog() {
        String[] choices = {"Import CSV...", "Import Aiken (Moodle)...", "Save CSV Template", "Cancel"};
        int pick = JOptionPane.showOptionDialog(this,
                "<html>Import questions from a file.<br><br>"
                + "<b>CSV</b> columns (in order):<br>"
                + "<b>category, difficulty, question, option1, option2, option3, option4, correct_option, hint</b><br>"
                + "• difficulty: 1-3 or Easy / Medium / Hard &nbsp; • correct_option: 1-4<br>"
                + "• <b>True/False</b>: leave option3 and option4 empty (option1 = True, option2 = False, correct_option 1 or 2)<br>"
                + "• hint is optional; a header row is allowed.<br><br>"
                + "<b>Aiken</b> (text format that Moodle can export):<br>"
                + "question line, then A. B. C. D. option lines, then <b>ANSWER: B</b>; a blank line between questions.</html>",
                "Import Questions", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, choices, choices[0]);

        if (pick == 0) importCsvFile();
        else if (pick == 1) importAikenFile();
        else if (pick == 2) saveSampleTemplate();
    }

    private void importCsvFile() {
        JFileChooser chooser = new JFileChooser(new File("."));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        int imported = 0;
        int skipped = 0;
        List<String> problems = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            int nextId = nextQuestionId();
            int lineNo = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo == 1) line = line.replace("\uFEFF", "");
                if (line.trim().isEmpty()) continue;

                List<String> fields = Importers.parseCsvLine(line);
                if (lineNo == 1 && fields.get(0).trim().equalsIgnoreCase("category")) continue; // header row

                if (fields.size() < 8) {
                    skipped++;
                    if (problems.size() < 5) problems.add("Line " + lineNo + ": needs at least 8 columns");
                    continue;
                }

                int diff = Importers.parseDifficulty(fields.get(1));
                int correct;
                try {
                    correct = Integer.parseInt(fields.get(7).trim());
                } catch (NumberFormatException ex) {
                    skipped++;
                    if (problems.size() < 5) problems.add("Line " + lineNo + ": correct_option is not a number");
                    continue;
                }

                String hint = (fields.size() > 8) ? Limits.clean(fields.get(8), Limits.HINT) : "";
                boolean tf = fields.get(5).trim().isEmpty() && fields.get(6).trim().isEmpty();
                String[] opts = tf
                        ? new String[]{Limits.clean(fields.get(3), Limits.OPTION), Limits.clean(fields.get(4), Limits.OPTION)}
                        : new String[]{Limits.clean(fields.get(3), Limits.OPTION), Limits.clean(fields.get(4), Limits.OPTION),
                                       Limits.clean(fields.get(5), Limits.OPTION), Limits.clean(fields.get(6), Limits.OPTION)};
                Question q = new Question(nextId, Limits.clean(fields.get(0), Limits.CATEGORY),
                        Limits.clean(fields.get(2), Limits.QUESTION), opts, correct, diff, hint,
                        tf ? Question.TYPE_TF : Question.TYPE_MC, null);

                String error = registerCustomQuestion(q);
                if (error != null) {
                    skipped++;
                    if (problems.size() < 5) problems.add("Line " + lineNo + ": " + error);
                } else {
                    imported++;
                    nextId++;
                }
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not read that file:\n" + ex.getMessage(), "Import Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (imported > 0) saveCustomQuestions();
        showImportSummary(imported, skipped, problems);
    }

    private void importAikenFile() {
        JFileChooser chooser = new JFileChooser(new File("."));
        chooser.setFileFilter(new FileNameExtensionFilter("Aiken text files (*.txt)", "txt"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        List<String> cats = new ArrayList<>(getCategoryNames());
        cats.remove(ALL_CATEGORIES);
        JComboBox<String> cmbCategory = new JComboBox<>(cats.toArray(new String[0]));
        cmbCategory.setEditable(true);
        JComboBox<String> cmbDiff = new JComboBox<>(new String[]{"1 - Easy", "2 - Medium", "3 - Hard"});
        cmbDiff.setSelectedIndex(1);
        int ok = JOptionPane.showConfirmDialog(this,
                new Object[]{"Category for these questions (pick or type a new one):", cmbCategory,
                             "Difficulty for all of them:", cmbDiff},
                "Aiken Import", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;

        String category = Limits.clean(String.valueOf(cmbCategory.getEditor().getItem()), Limits.CATEGORY);
        if (category.isEmpty() || category.equals(ALL_CATEGORIES)) {
            JOptionPane.showMessageDialog(this, "Please type a valid category name.", "Aiken Import", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not read that file:\n" + ex.getMessage(), "Import Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<String> problems = new ArrayList<>();
        List<Question> parsed = Importers.parseAiken(lines, category, cmbDiff.getSelectedIndex() + 1, nextQuestionId(), problems);
        int imported = 0;
        int skipped = 0;
        for (Question q : parsed) {
            String error = registerCustomQuestion(q);
            if (error != null) {
                skipped++;
                if (problems.size() < 5) problems.add("Question \"" + q.text + "\": " + error);
            } else {
                imported++;
            }
        }
        if (imported > 0) saveCustomQuestions();
        showImportSummary(imported, skipped + problems.size() - Math.min(problems.size(), skipped), problems);
    }

    private void showImportSummary(int imported, int skipped, List<String> problems) {
        StringBuilder summary = new StringBuilder();
        summary.append("Imported: ").append(imported).append(" question(s)\n");
        summary.append("Skipped: ").append(skipped).append("\n");
        if (!problems.isEmpty()) {
            summary.append("\nFirst problems found:\n");
            for (String p : problems) summary.append(" - ").append(p).append("\n");
        }
        JOptionPane.showMessageDialog(this, summary.toString(), "Import Result",
                imported > 0 ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    private void saveSampleTemplate() {
        File out = new File("sample_questions.csv");
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            w.println("category,difficulty,question,option1,option2,option3,option4,correct_option,hint");
            w.println("Java Core,Easy,Which keyword is used to inherit from a class?,extends,implements,inherits,super,1,Think about subclassing");
            w.println("Networking,Medium,\"Which layer is responsible for routing, and why?\",Transport,Network,Data Link,Application,2,IP lives here");
            w.println("Java Core,Easy,Java is platform independent.,True,False,,,1,Write once run anywhere");
            JOptionPane.showMessageDialog(this, "Template saved as:\n" + out.getAbsolutePath(), "Template Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save the template file.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ===================== QUESTION BANK =====================

    private void updateDifficultyBadge(int difficulty) {
        switch (difficulty) {
            case 1:
                lblDifficultyBadge.setText("DIFFICULTY: EASY");
                lblDifficultyBadge.setBackground(new Color(34, 197, 94));
                lblDifficultyBadge.setForeground(Color.WHITE);
                break;
            case 2:
                lblDifficultyBadge.setText("DIFFICULTY: MEDIUM");
                lblDifficultyBadge.setBackground(new Color(234, 179, 8));
                lblDifficultyBadge.setForeground(Color.BLACK);
                break;
            case 3:
                lblDifficultyBadge.setText("DIFFICULTY: HARD");
                lblDifficultyBadge.setBackground(new Color(239, 68, 68));
                lblDifficultyBadge.setForeground(Color.WHITE);
                break;
            default:
                break;
        }
    }

    private Map<Integer, List<Question>> newDifficultyMap() {
        Map<Integer, List<Question>> map = new HashMap<>();
        for (int d = 1; d <= 3; d++) map.put(d, new ArrayList<>());
        return map;
    }

    private void seed(int id, String category, String text, int correct, int difficulty, String hint, String... options) {
        addQuestionToBank(category, new Question(id, category, text, options, correct, difficulty, hint));
    }

    private void seedTrueFalse(int id, String category, String text, boolean answerIsTrue, int difficulty, String hint) {
        addQuestionToBank(category, new Question(id, category, text, new String[]{"True", "False"},
                answerIsTrue ? 1 : 2, difficulty, hint, Question.TYPE_TF, null));
    }

    // --- built-in questions (Java Core, Data Structures, Algorithms) ---
    private void seedQuestions() {
        for (String cat : new String[]{ALL_CATEGORIES, "Java Core", "Data Structures", "Algorithms"}) {
            questionBank.put(cat, newDifficultyMap());
        }
        final String jc = "Java Core", ds = "Data Structures", al = "Algorithms";

        // ---- Java Core ----
        seed(101, jc, "Which keyword is used to create a class in Java?", 2, 1, "It is the blueprint keyword for OOP.",
                "struct", "class", "define", "object");
        seed(102, jc, "What is the default value of a boolean variable in Java?", 3, 1, "Booleans start with a negative state by default.",
                "true", "null", "false", "0");
        seed(103, jc, "Which feature allows multiple methods with the same name but different parameters?", 2, 2,
                "Occurs within the same class with distinct parameters.", "Overriding", "Overloading", "Encapsulation", "Abstraction");
        seed(104, jc, "Which memory area in Java holds objects created with 'new'?", 2, 3, "Dynamic memory allocation area.",
                "Stack", "Heap", "Method Area", "Register");
        seed(105, jc, "Which of these access modifiers makes a member accessible only within its own class?", 3, 1,
                "Most restrictive modifier.", "public", "protected", "private", "default");
        seed(106, jc, "What is the parent class of all classes in Java?", 2, 2, "Root of the Java class hierarchy.",
                "System", "Object", "Class", "String");
        seed(107, jc, "Which exception is thrown when dividing by zero in integer arithmetic?", 2, 3, "Math error exception.",
                "NullPointerException", "ArithmeticException", "IndexOutOfBoundsException", "ClassCastException");
        seedTrueFalse(108, jc, "In Java, a String object can be changed after it is created.", false, 1, "Strings are immutable.");
        seedTrueFalse(109, jc, "A Java class can extend more than one class.", false, 2, "Java has single inheritance for classes.");

        // ---- Data Structures ----
        seed(201, ds, "Which data structure operates under FIFO?", 2, 1, "Like a line of people waiting in a ticket booth.",
                "Stack", "Queue", "Tree", "Graph");
        seed(202, ds, "Which data structure follows the LIFO principle?", 3, 2, "Like a stack of plates.",
                "Queue", "Array", "Stack", "Tree");
        seed(203, ds, "What is the average time complexity of a Hash Table lookup?", 1, 2, "Direct key-value hash mapping access.",
                "O(1)", "O(n)", "O(log n)", "O(n^2)");
        seed(204, ds, "What is the worst-case time complexity of searching in an unbalanced BST?", 3, 3,
                "Resembles a linked list structure in worst-case.", "O(1)", "O(log n)", "O(n)", "O(n log n)");
        seed(205, ds, "Which data structure uses nodes with pointers to both next and previous elements?", 2, 1, "Linked in two directions.",
                "Singly Linked List", "Doubly Linked List", "Array", "Stack");
        seed(206, ds, "In a max-heap, for any given node i, the value of the node is:", 2, 2, "Parent is largest in max-heap.",
                "Less than its children", "Greater than or equal to its children", "Equal to its children", "Unrelated to children");
        seed(207, ds, "What is the height of a complete binary tree with N nodes?", 3, 3, "Logarithmic depth property.",
                "O(N)", "O(N log N)", "O(log N)", "O(1)");
        seedTrueFalse(208, ds, "Accessing an array element by its index takes constant time.", true, 1, "Direct address calculation.");

        // ---- Algorithms ----
        seed(301, al, "What is the time complexity of Binary Search?", 2, 1, "Divide and conquer by half each step.",
                "O(n)", "O(log n)", "O(n^2)", "O(1)");
        seed(302, al, "Which algorithm finds the shortest path in a weighted graph?", 2, 3,
                "Named after a famous Dutch computer scientist starting with D.", "Kruskal", "Dijkstra", "KMP", "Floyd-Warshall");
        seed(303, al, "Which pattern matching algorithm is commonly used in Plagiarism Detection?", 1, 3, "Uses rolling hashing techniques.",
                "Rabin-Karp", "Bubble Sort", "DFS", "Prim's Algorithm");
        seed(304, al, "What is the worst-case time complexity of Bubble Sort?", 3, 2, "Quadratic sorting comparison.",
                "O(n)", "O(n log n)", "O(n^2)", "O(log n)");
        seed(305, al, "Which sorting algorithm divides the array into halves, sorts them, and merges them?", 2, 2,
                "Classic divide and conquer sorting.", "Quick Sort", "Merge Sort", "Selection Sort", "Insertion Sort");
        seed(306, al, "Which graph traversal algorithm uses a Queue data structure?", 2, 2, "Level-order exploration.",
                "Depth-First Search (DFS)", "Breadth-First Search (BFS)", "Dijkstra", "Bellman-Ford");
        seed(307, al, "What design paradigm does Dynamic Programming use to solve problems?", 3, 3, "Storing results of subproblems.",
                "Divide and Conquer", "Greedy approach", "Overlapping subproblems and optimal substructure", "Random search");
        seedTrueFalse(308, al, "Binary Search only works on a sorted collection.", true, 1, "It discards half of the items each step.");
    }

    private void addQuestionToBank(String category, Question q) {
        questionBank.computeIfAbsent(category, k -> newDifficultyMap());
        questionBank.get(category).get(q.difficulty).add(q);
        questionBank.get(ALL_CATEGORIES).get(q.difficulty).add(q);
    }

    private void removeQuestionFromBank(Question q) {
        Map<Integer, List<Question>> byDifficulty = questionBank.get(q.category);
        if (byDifficulty != null) byDifficulty.get(q.difficulty).remove(q);
        questionBank.get(ALL_CATEGORIES).get(q.difficulty).remove(q);
    }

    // Returns null when the question is OK, otherwise a short reason.
    private String validateQuestion(Question q) {
        if (q.category == null || q.category.trim().isEmpty()) return "category is empty";
        if (q.category.equals(ALL_CATEGORIES)) return "'All Categories' is a reserved name";
        if (q.category.length() > Limits.CATEGORY) return "category is too long (max " + Limits.CATEGORY + " characters)";
        if (q.text == null || q.text.trim().isEmpty()) return "question text is empty";
        if (q.text.length() > Limits.QUESTION) return "question text is too long (max " + Limits.QUESTION + " characters)";
        int expected = q.isTrueFalse() ? 2 : 4;
        if (q.options == null || q.options.length != expected) return "needs exactly " + expected + " options";
        for (String o : q.options) {
            if (o == null || o.trim().isEmpty()) return "an option is empty";
            if (o.length() > Limits.OPTION) return "an option is too long (max " + Limits.OPTION + " characters)";
        }
        if (q.hint != null && q.hint.length() > Limits.HINT) return "hint is too long (max " + Limits.HINT + " characters)";
        if (q.correctOption < 1 || q.correctOption > expected) return "correct option must be 1-" + expected;
        if (q.difficulty < 1 || q.difficulty > 3) return "difficulty must be 1 (Easy), 2 (Medium) or 3 (Hard)";
        return null;
    }

    /** Cuts over-long text and removes control characters (used for files that were saved before the limits existed). */
    private void sanitizeQuestion(Question q) {
        q.category = Limits.clean(q.category, Limits.CATEGORY);
        q.text = Limits.clean(q.text, Limits.QUESTION);
        if (q.options != null) {
            for (int i = 0; i < q.options.length; i++) {
                q.options[i] = Limits.clean(Question.cleanOption(q.options[i]), Limits.OPTION);
            }
        }
        q.hint = Limits.clean(q.hint, Limits.HINT);
    }

    // Adds to the bank + the custom list WITHOUT saving (used by bulk import).
    private String registerCustomQuestion(Question q) {
        String error = validateQuestion(q);
        if (error != null) return error;
        addQuestionToBank(q.category, q);
        customQuestions.add(q);
        return null;
    }

    private String addCustomQuestion(Question q) {
        String error = registerCustomQuestion(q);
        if (error == null) saveCustomQuestions();
        return error;
    }

    // ===================== SAVE / LOAD =====================

    private File dataFileOf(String name) {
        return DataStore.dataFile(name).toFile();
    }

    private void saveCustomQuestions() {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(dataFileOf(QUESTIONS_FILE)), StandardCharsets.UTF_8)) {
            gson.toJson(customQuestions, writer);
        } catch (IOException e) {
            AppSupport.log(e);
        }
    }

    private void loadCustomQuestions() {
        File file = dataFileOf(QUESTIONS_FILE);
        if (!file.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Question[] array = gson.fromJson(reader, Question[].class);
            if (array != null) {
                for (Question q : array) {
                    if (q == null) continue;
                    if (q.type == null) q.type = Question.TYPE_MC; // files saved before True/False existed
                    sanitizeQuestion(q);
                    if (validateQuestion(q) == null) {
                        customQuestions.add(q);
                        addQuestionToBank(q.category, q);
                    }
                }
            }
        } catch (Exception e) {
            AppSupport.log(e);
        }
    }

    private void saveQuizSets() {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(dataFileOf(SETS_FILE)), StandardCharsets.UTF_8)) {
            gson.toJson(quizSets, writer);
        } catch (IOException e) {
            AppSupport.log(e);
        }
    }

    private void loadQuizSets() {
        File file = dataFileOf(SETS_FILE);
        if (!file.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            QuizSet[] array = gson.fromJson(reader, QuizSet[].class);
            if (array != null) {
                for (QuizSet s : array) {
                    if (s == null || s.name == null) continue;
                    if (s.questionIds == null) s.questionIds = new ArrayList<>();
                    quizSets.add(s);
                }
            }
        } catch (Exception e) {
            AppSupport.log(e);
        }
    }

    /** Results now live in the shared data folder (one file per result). Old leaderboard.json files are moved over once. */
    private void loadLeaderboardData() {
        migrateLegacyLeaderboard();
        leaderboard = DataStore.listResults();
        Collections.sort(leaderboard);
    }

    private void migrateLegacyLeaderboard() {
        File old = new File(LEGACY_LEADERBOARD);
        if (!old.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(old), StandardCharsets.UTF_8)) {
            StudentResult[] array = gson.fromJson(reader, StudentResult[].class);
            if (array != null) {
                for (StudentResult r : array) {
                    if (r == null) continue;
                    r.id = null; // DataStore gives it a new id
                    if (r.createdAt == 0) r.createdAt = System.currentTimeMillis();
                    DataStore.saveResult(r);
                }
            }
        } catch (Exception e) {
            AppSupport.log(e);
            return; // keep the old file; try again next time
        }
        if (!old.renameTo(new File(LEGACY_LEADERBOARD + ".migrated"))) old.delete();
    }

    public static void main(String[] args) {
        AppSupport.installCrashHandler();
        SwingUtilities.invokeLater(() -> {
            EducationalTools app = new EducationalTools();
            app.setVisible(true);
        });
    }
}