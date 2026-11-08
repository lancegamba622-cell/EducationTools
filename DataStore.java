package com.mycompany.educationaltools;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/*
 * ============================================================================
 *  DATA STORE - users, hosted sessions, live student progress and results.
 *  Everything is kept as small .properties files inside the data folder
 *  (default: "quiz_data"), so several copies of the app that share the folder
 *  see each other.
 *
 *  WHERE IS THE DATA FOLDER?
 *    settings.properties (next to the program) can contain:  dataFolder=D:\\shared\\quiz_data
 *    If it is missing, "quiz_data" next to the program is used.
 *    On a classroom network EVERY computer must point to the SAME shared folder.
 *    The login screen has a "Data folder" button that writes this setting for you.
 *
 *  Inside the data folder:
 *    users/ sessions/ participants/ results/ images/   custom_questions.json   quiz_sets.json
 *
 *  Default teacher account (first run): "teacher" / "teacher123"
 *  -> change it with the "Change Password" tile after the first login.
 * ============================================================================
 */
class DataStore {
    static final Path SETTINGS_FILE = Paths.get("settings.properties");

    static Path ROOT;
    static Path USERS_DIR;
    static Path SESSIONS_DIR;
    static Path PARTICIPANTS_DIR;
    static Path RESULTS_DIR;
    static Path IMAGES_DIR;

    static final String DEFAULT_TEACHER_USER = "teacher";
    static final String DEFAULT_TEACHER_PASSWORD = "teacher123";

    private static final SecureRandom RNG = new SecureRandom();
    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final String USERNAME_PATTERN = "[a-z0-9_.]{3,20}";
    private static final String RESULT_ID_PATTERN = "[A-Za-z0-9_]{1,60}";

    static {
        useRoot(Paths.get(configuredFolder()));
    }

    /** Points the store at another folder (used by the unit tests). Call init() afterwards. */
    static void useRoot(Path root) {
        ROOT = root;
        USERS_DIR = root.resolve("users");
        SESSIONS_DIR = root.resolve("sessions");
        PARTICIPANTS_DIR = root.resolve("participants");
        RESULTS_DIR = root.resolve("results");
        IMAGES_DIR = root.resolve("images");
    }

    // ---------- settings (which folder holds the data) ----------

    /** The data folder written in settings.properties, or "quiz_data" when nothing is set. */
    static String configuredFolder() {
        Properties p = readProps(SETTINGS_FILE);
        String v = (p == null) ? null : p.getProperty("dataFolder");
        return (v == null || v.trim().isEmpty()) ? "quiz_data" : v.trim();
    }

    /** Remembers the data folder for the NEXT start of the program. */
    static void saveDataFolder(String folder) {
        Properties p = readProps(SETTINGS_FILE);
        if (p == null) p = new Properties();
        p.setProperty("dataFolder", folder);
        writeProps(SETTINGS_FILE, p);
    }

    /** A file that lives directly inside the data folder (custom_questions.json, quiz_sets.json ...). */
    static Path dataFile(String name) {
        return ROOT.resolve(name);
    }

    static void init() {
        try {
            Files.createDirectories(USERS_DIR);
            Files.createDirectories(SESSIONS_DIR);
            Files.createDirectories(PARTICIPANTS_DIR);
            Files.createDirectories(RESULTS_DIR);
            Files.createDirectories(IMAGES_DIR);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Older versions kept these two files next to the program; bring them into the shared data folder once.
        migrateLegacyFile("custom_questions.json");
        migrateLegacyFile("quiz_sets.json");

        // Re-create the default teacher whenever NO teacher exists (also when students already signed up).
        boolean hasTeacher = false;
        for (UserAccount u : listUsers()) {
            if (u.isTeacher()) hasTeacher = true;
        }
        if (!hasTeacher) {
            Path file = userFile(DEFAULT_TEACHER_USER);
            Properties old = readProps(file);
            if (old != null) {
                // A student account once took the name "teacher", so the default teacher could not be created.
                // With no teacher at all, turn that account into the teacher account (password: teacher123).
                old.setProperty("role", Status.TEACHER);
                old.setProperty("displayName", "Teacher");
                storePassword(old, DEFAULT_TEACHER_PASSWORD.toCharArray());
                writeProps(file, old);
            } else {
                createUser(DEFAULT_TEACHER_USER, "Teacher", Status.TEACHER, DEFAULT_TEACHER_PASSWORD.toCharArray());
            }
        }
    }

    private static void migrateLegacyFile(String name) {
        try {
            Path old = Paths.get(name).toAbsolutePath().normalize();
            Path target = dataFile(name).toAbsolutePath().normalize();
            if (old.equals(target) || !Files.exists(old) || Files.exists(target)) return;
            Files.copy(old, target);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ---------- tiny .properties helpers (UTF-8, written atomically) ----------

    static Properties readProps(Path file) {
        if (!Files.exists(file)) return null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Properties p = new Properties();
                p.load(r);
                return p;
            } catch (IOException e) {
                try { Thread.sleep(30); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return null;
    }

    static void writeProps(Path file, Properties props) {
        try {
            file = file.toAbsolutePath(); // a bare file name such as "settings.properties" has no parent folder
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp" + System.nanoTime());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(w, null);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveFailed) {
                    Files.deleteIfExists(tmp);
                    throw moveFailed;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static List<Path> listPropertyFiles(Path dir, String glob) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path p : stream) result.add(p);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    private static String str(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return v == null ? def : v;
    }

    private static int intOf(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, "").trim()); } catch (NumberFormatException e) { return def; }
    }

    private static long longOf(Properties p, String key, long def) {
        try { return Long.parseLong(p.getProperty(key, "").trim()); } catch (NumberFormatException e) { return def; }
    }

    private static double doubleOf(Properties p, String key, double def) {
        try { return Double.parseDouble(p.getProperty(key, "").trim()); } catch (NumberFormatException e) { return def; }
    }

    // ---------- question images ----------

    /** Copies a picture into the images folder and returns the stored file name (null if copying failed). */
    static String importImage(File source) {
        try {
            Files.createDirectories(IMAGES_DIR);
            String name = source.getName().toLowerCase(Locale.ROOT);
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".png";
            if (!ext.matches("\\.[a-z0-9]{1,5}")) ext = ".png";
            String stored = "img_" + System.currentTimeMillis() + "_" + RNG.nextInt(10000) + ext;
            Files.copy(source.toPath(), IMAGES_DIR.resolve(stored), StandardCopyOption.REPLACE_EXISTING);
            return stored;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /** The picture file for a stored name, or null when there is none (path tricks like "../" are ignored). */
    static File imageFile(String storedName) {
        if (storedName == null || storedName.isEmpty()) return null;
        File f = IMAGES_DIR.resolve(Paths.get(storedName).getFileName().toString()).toFile();
        return f.exists() ? f : null;
    }

    // ---------- users ----------

    private static String hashPassword(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing is not available", e);
        }
    }

    static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static Path userFile(String normalizedUsername) {
        return USERS_DIR.resolve(normalizedUsername + ".properties");
    }

    /** Puts a fresh random salt + hash for the password into the given properties. */
    private static void storePassword(Properties p, char[] password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        p.setProperty("salt", Base64.getEncoder().encodeToString(salt));
        p.setProperty("hash", hashPassword(password, salt));
    }

    /** Returns null on success, otherwise a message explaining what is wrong. */
    static String createUser(String username, String displayName, String role, char[] password) {
        String u = normalizeUsername(username);
        if (!u.matches(USERNAME_PATTERN)) {
            return "Username must be 3-20 characters: letters, numbers, dot or underscore.";
        }
        String dn = displayName == null ? "" : displayName.trim();
        if (dn.isEmpty()) return "Please enter your full name.";
        if (dn.length() > Limits.NAME) return "Name is too long (max " + Limits.NAME + " characters).";
        if (password == null || password.length < 6) return "Password must be at least 6 characters.";

        if (Status.STUDENT.equals(role) && DEFAULT_TEACHER_USER.equals(u)) {
            return "That username is reserved. Please choose another one.";
        }

        Path file = userFile(u);
        if (Files.exists(file)) return "That username is already taken.";

        Properties p = new Properties();
        p.setProperty("username", u);
        p.setProperty("displayName", dn);
        p.setProperty("role", role);
        storePassword(p, password);
        p.setProperty("createdAt", String.valueOf(System.currentTimeMillis()));
        writeProps(file, p);
        return null;
    }

    private static UserAccount accountFrom(Properties p) {
        UserAccount a = new UserAccount();
        a.username = str(p, "username", "");
        a.displayName = str(p, "displayName", a.username);
        a.role = str(p, "role", Status.STUDENT);
        a.createdAt = longOf(p, "createdAt", 0);
        return a;
    }

    static UserAccount authenticate(String username, char[] password) {
        String u = normalizeUsername(username);
        if (!u.matches(USERNAME_PATTERN)) return null;
        Properties p = readProps(userFile(u));
        if (p == null) return null;
        try {
            byte[] salt = Base64.getDecoder().decode(str(p, "salt", ""));
            String expected = str(p, "hash", "");
            String actual = hashPassword(password, salt);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
                return accountFrom(p);
            }
        } catch (IllegalArgumentException e) {
            // corrupted salt -> treat as failed login
        }
        return null;
    }

    /** Returns null on success, otherwise an error message. */
    static String changePassword(String username, char[] oldPassword, char[] newPassword) {
        if (authenticate(username, oldPassword) == null) return "Your current password is incorrect.";
        if (newPassword == null || newPassword.length < 6) return "New password must be at least 6 characters.";
        Path file = userFile(normalizeUsername(username));
        Properties p = readProps(file);
        if (p == null) return "Account not found.";
        storePassword(p, newPassword);
        writeProps(file, p);
        return null;
    }

    /** Teacher tool: sets a new password without knowing the old one. Returns null on success. */
    static String resetPassword(String username, char[] newPassword) {
        String u = normalizeUsername(username);
        if (!u.matches(USERNAME_PATTERN)) return "Account not found.";
        if (newPassword == null || newPassword.length < 6) return "Password must be at least 6 characters.";
        Path file = userFile(u);
        Properties p = readProps(file);
        if (p == null) return "Account not found.";
        storePassword(p, newPassword);
        writeProps(file, p);
        return null;
    }

    /**
     * Deletes a STUDENT account together with everything that belongs to it
     * (live-session progress and saved results). Teacher accounts can never be deleted this way.
     */
    static boolean deleteUser(String username) {
        String u = normalizeUsername(username);
        if (!u.matches(USERNAME_PATTERN)) return false;
        Properties p = readProps(userFile(u));
        if (p == null || Status.TEACHER.equals(str(p, "role", Status.STUDENT))) return false;
        try {
            boolean deleted = Files.deleteIfExists(userFile(u));
            if (deleted) {
                deleteParticipantsOf(u);
                deleteResultsOf(u);
            }
            return deleted;
        } catch (IOException e) {
            return false;
        }
    }

    static List<UserAccount> listUsers() {
        List<UserAccount> users = new ArrayList<>();
        for (Path f : listPropertyFiles(USERS_DIR, "*.properties")) {
            Properties p = readProps(f);
            if (p != null) users.add(accountFrom(p));
        }
        return users;
    }

    static List<UserAccount> listStudents() {
        List<UserAccount> students = new ArrayList<>();
        for (UserAccount u : listUsers()) {
            if (!u.isTeacher()) students.add(u);
        }
        students.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        return students;
    }

    // ---------- results (the leaderboard): one small file per finished quiz ----------

    static String newResultId() {
        return System.currentTimeMillis() + "_" + RNG.nextInt(1_000_000);
    }

    /** Saves one result. Because every result is its own file, simultaneous saves never overwrite each other. */
    static void saveResult(StudentResult r) {
        if (r.id == null || !r.id.matches(RESULT_ID_PATTERN)) r.id = newResultId();
        Properties p = new Properties();
        p.setProperty("id", r.id);
        p.setProperty("name", r.name == null ? "" : r.name);
        p.setProperty("username", r.username == null ? "" : r.username);
        p.setProperty("category", r.category == null ? "" : r.category);
        p.setProperty("mode", r.mode == null ? "" : r.mode);
        p.setProperty("score", String.valueOf(r.score));
        p.setProperty("accuracy", String.valueOf(r.accuracy));
        p.setProperty("timeTaken", String.valueOf(r.timeTakenSeconds));
        p.setProperty("totalQuestions", String.valueOf(r.totalQuestions));
        p.setProperty("bestStreak", String.valueOf(r.bestStreak));
        p.setProperty("date", r.dateFormatted == null ? "" : r.dateFormatted);
        p.setProperty("createdAt", String.valueOf(r.createdAt));
        writeProps(RESULTS_DIR.resolve(r.id + ".properties"), p);
    }

    private static StudentResult resultFrom(Properties p) {
        String mode = str(p, "mode", "");
        StudentResult r = new StudentResult(
                str(p, "name", ""), str(p, "username", ""), str(p, "category", ""),
                mode.isEmpty() ? null : mode,
                intOf(p, "score", 0), doubleOf(p, "accuracy", 0), longOf(p, "timeTaken", 0),
                intOf(p, "totalQuestions", 0), intOf(p, "bestStreak", 0));
        r.id = str(p, "id", null);
        r.dateFormatted = str(p, "date", "N/A");
        r.createdAt = longOf(p, "createdAt", 0);
        return r;
    }

    static List<StudentResult> listResults() {
        List<StudentResult> list = new ArrayList<>();
        for (Path f : listPropertyFiles(RESULTS_DIR, "*.properties")) {
            Properties p = readProps(f);
            if (p == null) continue;
            StudentResult r = resultFrom(p);
            if (r.id != null && r.id.matches(RESULT_ID_PATTERN)) list.add(r);
        }
        return list;
    }

    static boolean deleteResult(String id) {
        if (id == null || !id.matches(RESULT_ID_PATTERN)) return false;
        try {
            return Files.deleteIfExists(RESULTS_DIR.resolve(id + ".properties"));
        } catch (IOException e) {
            return false;
        }
    }

    static void clearResults() {
        for (Path f : listPropertyFiles(RESULTS_DIR, "*.properties")) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    static void deleteResultsOf(String username) {
        for (Path f : listPropertyFiles(RESULTS_DIR, "*.properties")) {
            Properties p = readProps(f);
            if (p != null && username.equals(str(p, "username", ""))) {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // ---------- sessions ----------

    /*
     * The correct answer is NOT written to the session file in plain text: only a salted SHA-256 "key"
     * per question is stored, and the app recovers the right option by checking every possibility
     * (4 for multiple choice, 2 for True/False).
     * This stops a student from simply opening the sessions folder and reading the answers,
     * but it is a deterrent, not real security: anybody who knows the format can still brute-force the options
     * (and anybody who can write to the data folder can edit accounts too).
     * Truly secret answers need a server that does the grading.
     */
    private static String answerKey(String salt, int questionId, int option) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((salt + ":" + questionId + ":" + option).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Properties sessionToProps(QuizSession s) {
        byte[] saltBytes = new byte[16];
        RNG.nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);

        Properties p = new Properties();
        p.setProperty("code", s.code);
        p.setProperty("host", s.hostUsername);
        p.setProperty("hostName", s.hostName);
        p.setProperty("title", s.title);
        p.setProperty("category", s.category);
        p.setProperty("status", s.status);
        p.setProperty("timed", String.valueOf(s.timed));
        p.setProperty("autoEnd", String.valueOf(s.autoEnd));
        p.setProperty("paced", String.valueOf(s.paced));
        p.setProperty("currentIndex", String.valueOf(s.currentIndex));
        p.setProperty("secondsPerQuestion", String.valueOf(s.secondsPerQuestion));
        p.setProperty("createdAt", String.valueOf(s.createdAt));
        p.setProperty("startedAt", String.valueOf(s.startedAt));
        p.setProperty("endedAt", String.valueOf(s.endedAt));
        p.setProperty("questionStartedAt", String.valueOf(s.questionStartedAt));
        p.setProperty("answerSalt", salt);
        p.setProperty("q.count", String.valueOf(s.questions.size()));
        for (int i = 0; i < s.questions.size(); i++) {
            Question q = s.questions.get(i);
            String k = "q." + i + ".";
            p.setProperty(k + "id", String.valueOf(q.id));
            p.setProperty(k + "cat", q.category == null ? "" : q.category);
            p.setProperty(k + "text", q.text == null ? "" : q.text);
            p.setProperty(k + "type", q.type == null ? Question.TYPE_MC : q.type);
            p.setProperty(k + "image", q.image == null ? "" : q.image);
            p.setProperty(k + "n", String.valueOf(q.options.length));
            for (int o = 0; o < q.options.length; o++) p.setProperty(k + "o" + (o + 1), q.options[o]);
            p.setProperty(k + "key", answerKey(salt, q.id, q.correctOption));
            p.setProperty(k + "diff", String.valueOf(q.difficulty));
            p.setProperty(k + "hint", q.hint == null ? "" : q.hint);
        }
        return p;
    }

    private static QuizSession sessionFromProps(Properties p) {
        QuizSession s = new QuizSession();
        s.code = str(p, "code", "");
        s.hostUsername = str(p, "host", "");
        s.hostName = str(p, "hostName", "");
        s.title = str(p, "title", "Quiz Time");
        s.category = str(p, "category", "All Categories");
        s.status = str(p, "status", Status.LOBBY);
        s.timed = Boolean.parseBoolean(str(p, "timed", "true"));
        s.autoEnd = Boolean.parseBoolean(str(p, "autoEnd", "false"));
        s.paced = Boolean.parseBoolean(str(p, "paced", "false"));
        s.currentIndex = intOf(p, "currentIndex", 0);
        s.secondsPerQuestion = intOf(p, "secondsPerQuestion", 15);
        s.createdAt = longOf(p, "createdAt", 0);
        s.startedAt = longOf(p, "startedAt", 0);
        s.endedAt = longOf(p, "endedAt", 0);
        s.questionStartedAt = longOf(p, "questionStartedAt", 0);
        String salt = str(p, "answerSalt", "");
        int count = intOf(p, "q.count", 0);
        for (int i = 0; i < count; i++) {
            String k = "q." + i + ".";
            int id = intOf(p, k + "id", 0);
            int n = intOf(p, k + "n", 4); // session files from before True/False have no count: always 4
            if (n < 2) n = 2;
            if (n > 4) n = 4;
            String[] opts = new String[n];
            for (int o = 0; o < n; o++) opts[o] = str(p, k + "o" + (o + 1), "");

            int correct = intOf(p, k + "correct", 0); // older session files still hold the plain answer
            if (correct < 1) {
                String key = str(p, k + "key", "");
                for (int option = 1; option <= n; option++) {
                    if (answerKey(salt, id, option).equals(key)) correct = option;
                }
            }
            if (correct < 1) correct = 1;
            s.questions.add(new Question(
                    id, str(p, k + "cat", ""), str(p, k + "text", ""), opts,
                    correct, intOf(p, k + "diff", 2), str(p, k + "hint", ""),
                    str(p, k + "type", Question.TYPE_MC), str(p, k + "image", "")));
        }
        return s;
    }

    static String generateCode() {
        for (int attempt = 0; attempt < 200; attempt++) {
            String code = String.valueOf(100000 + RNG.nextInt(900000));
            if (!Files.exists(SESSIONS_DIR.resolve(code + ".properties"))) return code;
        }
        return String.valueOf(100000 + RNG.nextInt(900000));
    }

    static void saveSession(QuizSession s) {
        writeProps(SESSIONS_DIR.resolve(s.code + ".properties"), sessionToProps(s));
    }

    static QuizSession loadSession(String code) {
        if (code == null || !code.matches("\\d{6}")) return null;
        Properties p = readProps(SESSIONS_DIR.resolve(code + ".properties"));
        return p == null ? null : sessionFromProps(p);
    }

    static List<QuizSession> listSessionsByHost(String hostUsername) {
        List<QuizSession> list = new ArrayList<>();
        for (Path f : listPropertyFiles(SESSIONS_DIR, "*.properties")) {
            Properties p = readProps(f);
            if (p != null && hostUsername.equals(str(p, "host", ""))) list.add(sessionFromProps(p));
        }
        list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return list;
    }

    static void setSessionStatus(String code, String status) {
        QuizSession s = loadSession(code);
        if (s == null) return;
        s.status = status;
        long now = System.currentTimeMillis();
        if (Status.LIVE.equals(status) && s.startedAt == 0) {
            s.startedAt = now;
            s.questionStartedAt = now;
        }
        if (Status.ENDED.equals(status)) s.endedAt = now;
        saveSession(s);
    }

    /** Teacher-paced quizzes: opens the next question for the whole class. Returns false when there is nothing to open. */
    static boolean advanceQuestion(String code) {
        QuizSession s = loadSession(code);
        if (s == null || !s.paced || !Status.LIVE.equals(s.status)) return false;
        if (s.currentIndex >= s.questions.size()) return false;
        s.currentIndex++;
        s.questionStartedAt = System.currentTimeMillis();
        saveSession(s);
        return true;
    }

    static void deleteSession(String code) {
        if (code == null || !code.matches("\\d{6}")) return;
        try {
            Files.deleteIfExists(SESSIONS_DIR.resolve(code + ".properties"));
            for (Path f : listPropertyFiles(PARTICIPANTS_DIR, code + "_*.properties")) {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Ends a LIVE session by itself when every student is done. Students who left in the middle of the quiz
     * (they might be let back in) keep it open, so the teacher decides in that case. Returns true if it ended.
     */
    static boolean autoEndIfDone(String code) {
        QuizSession s = loadSession(code);
        if (s == null || !s.autoEnd || !Status.LIVE.equals(s.status)) return false;
        List<Participant> parts = listParticipants(code);
        boolean anyFinished = false;
        for (Participant p : parts) {
            if (Status.FINISHED.equals(p.status)) {
                anyFinished = true;
            } else if (!(Status.LEFT.equals(p.status) && p.leftAtQuestion == 0)) {
                return false; // someone is still joining, playing or may come back
            }
        }
        if (!anyFinished) return false;
        setSessionStatus(code, Status.ENDED);
        return true;
    }

    /** Per-question results for a hosted quiz: how many students answered and how many got it right. */
    static List<QuestionStat> questionStats(QuizSession s, List<Participant> participants) {
        List<QuestionStat> stats = new ArrayList<>();
        for (int i = 0; i < s.questions.size(); i++) {
            QuestionStat st = new QuestionStat(i + 1, s.questions.get(i));
            for (Participant p : participants) {
                if (p.results != null && p.results.length() > i) {
                    st.answered++;
                    if (p.results.charAt(i) == '1') st.correct++;
                }
            }
            stats.add(st);
        }
        return stats;
    }

    // ---------- participants ----------

    private static Path participantFile(String code, String username) {
        return PARTICIPANTS_DIR.resolve(code + "_" + username + ".properties");
    }

    static void saveParticipant(Participant x) {
        Properties p = new Properties();
        p.setProperty("session", x.sessionCode);
        p.setProperty("username", x.username);
        p.setProperty("displayName", x.displayName);
        p.setProperty("status", x.status);
        p.setProperty("answered", String.valueOf(x.answered));
        p.setProperty("total", String.valueOf(x.total));
        p.setProperty("score", String.valueOf(x.score));
        p.setProperty("correct", String.valueOf(x.correct));
        p.setProperty("bestStreak", String.valueOf(x.bestStreak));
        p.setProperty("awayCount", String.valueOf(x.awayCount));
        p.setProperty("awaySeconds", String.valueOf(x.awaySeconds));
        p.setProperty("leftCount", String.valueOf(x.leftCount));
        p.setProperty("leftAtQuestion", String.valueOf(x.leftAtQuestion));
        p.setProperty("rejoinCount", String.valueOf(x.rejoinCount));
        p.setProperty("results", x.results == null ? "" : x.results);
        p.setProperty("choices", x.choices == null ? "" : x.choices);
        p.setProperty("times", x.times == null ? "" : x.times);
        p.setProperty("accuracy", String.valueOf(x.accuracy));
        p.setProperty("timeTaken", String.valueOf(x.timeTakenSeconds));
        p.setProperty("joinedAt", String.valueOf(x.joinedAt));
        p.setProperty("updatedAt", String.valueOf(x.updatedAt));
        p.setProperty("leftAt", String.valueOf(x.leftAt));
        p.setProperty("finishedAt", String.valueOf(x.finishedAt));
        writeProps(participantFile(x.sessionCode, x.username), p);
    }

    private static Participant participantFrom(Properties p) {
        Participant x = new Participant();
        x.sessionCode = str(p, "session", "");
        x.username = str(p, "username", "");
        x.displayName = str(p, "displayName", x.username);
        x.status = str(p, "status", Status.JOINED);
        x.answered = intOf(p, "answered", 0);
        x.total = intOf(p, "total", 0);
        x.score = intOf(p, "score", 0);
        x.correct = intOf(p, "correct", 0);
        x.bestStreak = intOf(p, "bestStreak", 0);
        x.awayCount = intOf(p, "awayCount", 0);
        x.awaySeconds = longOf(p, "awaySeconds", 0);
        x.leftCount = intOf(p, "leftCount", 0);
        x.leftAtQuestion = intOf(p, "leftAtQuestion", 0);
        x.rejoinCount = intOf(p, "rejoinCount", 0);
        x.results = str(p, "results", "");
        x.choices = str(p, "choices", "");
        x.times = str(p, "times", "");
        x.accuracy = doubleOf(p, "accuracy", 0);
        x.timeTakenSeconds = longOf(p, "timeTaken", 0);
        x.joinedAt = longOf(p, "joinedAt", 0);
        x.updatedAt = longOf(p, "updatedAt", 0);
        x.leftAt = longOf(p, "leftAt", 0);
        x.finishedAt = longOf(p, "finishedAt", 0);
        return x;
    }

    static Participant loadParticipant(String code, String username) {
        Properties p = readProps(participantFile(code, username));
        return p == null ? null : participantFrom(p);
    }

    static List<Participant> listParticipants(String code) {
        List<Participant> list = new ArrayList<>();
        for (Path f : listPropertyFiles(PARTICIPANTS_DIR, code + "_*.properties")) {
            Properties p = readProps(f);
            if (p != null) list.add(participantFrom(p));
        }
        return list;
    }

    /** Removes a student from a session completely (their progress is lost). */
    static boolean deleteParticipant(String code, String username) {
        try {
            return Files.deleteIfExists(participantFile(code, username));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Removes the progress of one student from EVERY session (used when the account is deleted).
     * The username is read from inside each file, because a file-name pattern such as "*_sam.properties"
     * would also match a different student called "mary_sam".
     */
    private static void deleteParticipantsOf(String username) {
        for (Path f : listPropertyFiles(PARTICIPANTS_DIR, "*.properties")) {
            Properties p = readProps(f);
            if (p != null && username.equals(str(p, "username", ""))) {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Lets a student whose app crashed (or who left by mistake) join again. Progress restarts from question 1,
     * but the "switched away" / "left" counters stay so the teacher keeps the history.
     */
    static boolean allowRejoin(String code, String username) {
        Participant x = loadParticipant(code, username);
        if (x == null) return false;
        x.status = Status.JOINED;
        x.answered = 0;
        x.score = 0;
        x.correct = 0;
        x.bestStreak = 0;
        x.accuracy = 0;
        x.timeTakenSeconds = 0;
        x.results = "";
        x.choices = "";
        x.times = "";
        x.rejoinCount++;
        x.updatedAt = System.currentTimeMillis();
        saveParticipant(x);
        return true;
    }
}