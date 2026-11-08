package com.mycompany.educationaltools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/*
 * ============================================================================
 *  MODELS - plain data classes used by the whole app.
 *  (Several package-private classes share this file on purpose.)
 * ============================================================================
 */

class Question {
    static final String TYPE_MC = "MC";   // multiple choice, 4 options
    static final String TYPE_TF = "TF";   // True / False, 2 options

    /** Options such as "All of the above" only make sense in a fixed position, so those questions are never shuffled. */
    private static final Pattern NO_SHUFFLE =
            Pattern.compile("(?i)(all|none|both)\\s+of\\s+(the\\s+)?(above|these)|\\bboth\\b");

    int id;
    String category;
    String text;
    String[] options;
    int correctOption;   // 1-based (1-4 for multiple choice, 1-2 for True/False)
    int difficulty;      // 1 = Easy, 2 = Medium, 3 = Hard
    String hint;
    String type;         // TYPE_MC or TYPE_TF; null in files saved before True/False existed = multiple choice
    String image;        // file name inside quiz_data/images, or null when the question has no picture
    transient boolean isBookmarked = false; // runtime-only flag, never written to JSON
    transient int[] shownOrder;             // only on shuffled copies: shownOrder[i] = original index of the option shown at slot i

    public Question(int id, String category, String text, String[] options, int correctOption, int difficulty, String hint) {
        this(id, category, text, options, correctOption, difficulty, hint, TYPE_MC, null);
    }

    public Question(int id, String category, String text, String[] options, int correctOption, int difficulty,
                    String hint, String type, String image) {
        this.id = id;
        this.category = category;
        this.text = text;
        this.type = (type == null || type.isEmpty()) ? TYPE_MC : type;
        this.image = (image == null || image.trim().isEmpty()) ? null : image.trim();
        setOptions(options);
        this.correctOption = correctOption;
        this.difficulty = difficulty;
        this.hint = hint;
    }

    /** Options are stored WITHOUT the "1. " prefix; the UI adds the numbering when displaying them. */
    static String cleanOption(String option) {
        return option == null ? "" : option.trim().replaceFirst("^\\d+\\.\\s+", "");
    }

    void setOptions(String[] raw) {
        this.options = new String[raw.length];
        for (int i = 0; i < raw.length; i++) this.options[i] = cleanOption(raw[i]);
    }

    boolean isTrueFalse() { return TYPE_TF.equals(type); }

    int optionCount() { return options == null ? 0 : options.length; }

    /** True/False keeps its fixed order; so do 4-option questions with answers like "All of the above". */
    boolean canShuffle() {
        if (isTrueFalse() || options == null || options.length != 4) return false;
        for (String o : options) {
            if (NO_SHUFFLE.matcher(o).find()) return false;
        }
        return true;
    }

    /** Returns a copy with the options in random order; correctOption is updated to follow the right answer. */
    Question shuffledCopy(Random rnd) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < options.length; i++) order.add(i);
        Collections.shuffle(order, rnd);

        String[] newOptions = new String[options.length];
        int[] mapping = new int[options.length];
        int newCorrect = correctOption;
        for (int i = 0; i < options.length; i++) {
            newOptions[i] = options[order.get(i)];
            mapping[i] = order.get(i);
            if (order.get(i) == correctOption - 1) newCorrect = i + 1;
        }
        Question copy = new Question(id, category, text, newOptions, newCorrect, difficulty, hint, type, image);
        copy.isBookmarked = isBookmarked;
        copy.shownOrder = mapping;
        return copy;
    }

    /** Converts the option number the student SAW (1-based) into the option number in the original question. */
    int toOriginalOption(int shownOption) {
        if (shownOption < 1) return 0;
        if (shownOrder == null || shownOption > shownOrder.length) return shownOption;
        return shownOrder[shownOption - 1] + 1;
    }
}

class UserAnswerRecord {
    Question question;      // what the student saw (may be a shuffled copy)
    Question original;      // the real question in the bank (used to retry mistakes)
    int selectedOption;
    boolean isCorrect;

    public UserAnswerRecord(Question question, Question original, int selectedOption, boolean isCorrect) {
        this.question = question;
        this.original = original;
        this.selectedOption = selectedOption;
        this.isCorrect = isCorrect;
    }
}

class StudentResult implements Comparable<StudentResult> {
    String id;           // file id used by DataStore.saveResult (null until saved)
    String name;
    String category;
    int score;
    double accuracy;
    long timeTakenSeconds;
    String dateFormatted;
    long createdAt;
    // Fields added in later versions (older leaderboard.json records simply won't have them)
    String mode;
    int totalQuestions;
    int bestStreak;
    String username;

    public StudentResult(String name, String username, String category, String mode, int score, double accuracy,
                         long timeTakenSeconds, int totalQuestions, int bestStreak) {
        this.name = name;
        this.username = username;
        this.category = category;
        this.mode = mode;
        this.score = score;
        this.accuracy = accuracy;
        this.timeTakenSeconds = timeTakenSeconds;
        this.totalQuestions = totalQuestions;
        this.bestStreak = bestStreak;
        this.createdAt = System.currentTimeMillis();
        this.dateFormatted = new Date(createdAt).toString();
    }

    @Override
    public int compareTo(StudentResult other) {
        if (this.score != other.score) return Integer.compare(other.score, this.score);
        return Long.compare(this.timeTakenSeconds, other.timeTakenSeconds);
    }
}

class UserAccount {
    String username;
    String displayName;
    String role; // "TEACHER" or "STUDENT"
    long createdAt;

    boolean isTeacher() { return Status.TEACHER.equals(role); }
}

class QuizSession {
    String code = "";
    String hostUsername = "";
    String hostName = "";
    String title = "Quiz Time";
    String category = "All Categories";
    String status = Status.LOBBY; // LOBBY -> LIVE -> ENDED
    boolean timed = true;
    boolean autoEnd = true;  // end the session automatically once everybody has finished
    boolean paced = false;   // teacher-paced: the teacher opens each question for the whole class
    int currentIndex = 0;    // paced only: 0-based question that is open now (== questions.size() when all are closed)
    int secondsPerQuestion = 15;
    long createdAt;
    long startedAt;
    long endedAt;
    long questionStartedAt;  // paced only: when the current question was opened
    List<Question> questions = new ArrayList<>();
}

class Participant {
    String sessionCode = "";
    String username = "";
    String displayName = "";
    String status = Status.JOINED; // JOINED (lobby), PLAYING, FINISHED, LEFT
    int answered;
    int total;
    int score;
    int correct;
    int bestStreak;
    int awayCount;        // times the student switched away from the quiz window
    long awaySeconds;     // total seconds spent away from the quiz window
    int leftCount;        // times the student left the quiz
    int leftAtQuestion;   // question number they were on when they last left (0 = lobby)
    int rejoinCount;      // times the teacher let this student back in
    String results = "";  // one char per answered question: '1' = correct, '0' = wrong / timeout
    String choices = "";  // one char per answered question: the ORIGINAL option number picked ('1'-'4'), '0' = no answer
    String times = "";    // seconds spent on each answered question, comma separated ("3.2,7.9,...")
    double accuracy;
    long timeTakenSeconds;
    long joinedAt;
    long updatedAt;
    long leftAt;
    long finishedAt;
}

/** How the whole class did on one question of a hosted quiz. */
class QuestionStat {
    final int number;          // 1-based position in the quiz
    final Question question;
    int answered;
    int correct;

    QuestionStat(int number, Question question) {
        this.number = number;
        this.question = question;
    }

    /** Percentage of correct answers, or -1 when nobody has answered yet. */
    int percent() {
        return answered == 0 ? -1 : (int) Math.round(correct * 100.0 / answered);
    }
}

/** A teacher's saved list of questions (by id) that can be hosted again and again. */
class QuizSet {
    String name;
    String owner;
    long createdAt;
    List<Integer> questionIds = new ArrayList<>();
}