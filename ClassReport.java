package com.mycompany.educationaltools;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.mycompany.educationaltools.UiKit.csvEscape;
import static com.mycompany.educationaltools.UiKit.difficultyLabel;

/*
 * ============================================================================
 *  CLASS REPORT - pure logic (no Swing): score statistics, histogram, hardest
 *  questions, most-picked wrong answers, students who need help, and soft
 *  "alerts" (very fast answers, identical wrong-answer patterns).
 *  The alerts are HINTS for the teacher, never proof of cheating.
 * ============================================================================
 */
final class ClassReport {
    static final String[] BUCKETS = {"0-49%", "50-59%", "60-69%", "70-79%", "80-89%", "90-100%"};
    static final double FAST_SECONDS = 1.0;      // faster than this on a long question is suspicious
    static final int LONG_QUESTION_CHARS = 60;   // what counts as a "long" question
    static final int SHARED_WRONG_MIN = 3;       // identical wrong answers needed to raise an alert

    static final class StudentRow {
        final Participant p;
        final int percent;
        final boolean needsHelp;
        final int fastCount;

        StudentRow(Participant p, int percent, boolean needsHelp, int fastCount) {
            this.p = p;
            this.percent = percent;
            this.needsHelp = needsHelp;
            this.fastCount = fastCount;
        }
    }

    final QuizSession session;
    int students;
    int finished;
    double avgScore;
    double medianScore;
    double avgPercent;
    int highScore;
    int lowScore;
    final int[] histogram = new int[BUCKETS.length];
    final List<QuestionStat> questions;
    final int[][] optionCounts;   // [question][option 0-3] = how many picked it (original option order)
    final int[] noAnswer;         // [question] = how many ran out of time / were closed
    final List<StudentRow> rows = new ArrayList<>();
    final List<Integer> hardest = new ArrayList<>(); // 0-based question indexes, hardest first (max 3)
    final List<String> alerts = new ArrayList<>();

    private ClassReport(QuizSession session, List<Participant> all) {
        this.session = session;
        this.questions = DataStore.questionStats(session, all);
        int qn = session.questions.size();
        this.optionCounts = new int[qn][4];
        this.noAnswer = new int[qn];

        List<Participant> counted = new ArrayList<>();
        for (Participant p : all) {
            if (Status.FINISHED.equals(p.status) || p.answered > 0) counted.add(p);
        }
        students = counted.size();

        List<Integer> scores = new ArrayList<>();
        double percentSum = 0;
        for (Participant p : counted) {
            if (Status.FINISHED.equals(p.status)) finished++;
            int pct = percentOf(p);
            percentSum += pct;
            scores.add(p.score);
            histogram[bucketOf(pct)]++;
            int fast = fastCount(session, p);
            rows.add(new StudentRow(p, pct, pct < 50, fast));

            String c = p.choices == null ? "" : p.choices;
            for (int i = 0; i < qn && i < c.length(); i++) {
                char ch = c.charAt(i);
                if (ch >= '1' && ch <= '4') optionCounts[i][ch - '1']++;
                else if (ch == '0') noAnswer[i]++;
            }
        }
        rows.sort((a, b) -> a.p.score != b.p.score ? Integer.compare(b.p.score, a.p.score)
                                                   : a.p.displayName.compareToIgnoreCase(b.p.displayName));

        if (!scores.isEmpty()) {
            Collections.sort(scores);
            int n = scores.size();
            double sum = 0;
            for (int s : scores) sum += s;
            avgScore = sum / n;
            avgPercent = percentSum / n;
            lowScore = scores.get(0);
            highScore = scores.get(n - 1);
            medianScore = (n % 2 == 1) ? scores.get(n / 2) : (scores.get(n / 2 - 1) + scores.get(n / 2)) / 2.0;
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).answered > 0) order.add(i);
        }
        order.sort((a, b) -> Integer.compare(questions.get(a).percent(), questions.get(b).percent()));
        for (int i = 0; i < Math.min(3, order.size()); i++) hardest.add(order.get(i));

        buildAlerts(counted);
    }

    static ClassReport build(QuizSession session, List<Participant> participants) {
        return new ClassReport(session, participants);
    }

    // ---------- small helpers ----------

    static int percentOf(Participant p) {
        int total = Math.max(1, p.total);
        return (int) Math.round(p.correct * 100.0 / total);
    }

    static int bucketOf(int pct) {
        if (pct < 50) return 0;
        if (pct >= 90) return 5;
        return (pct - 50) / 10 + 1;
    }

    /** "3.2,7.9,1.0" -> {3.2, 7.9, 1.0}. Bad pieces become -1. */
    static double[] parseTimes(String s) {
        if (s == null || s.isEmpty()) return new double[0];
        String[] parts = s.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = -1;
            }
        }
        return out;
    }

    /** True when a long question was answered (not timed out) faster than FAST_SECONDS. */
    static boolean isFast(Question q, double seconds, char choice) {
        return choice != '0' && seconds >= 0 && seconds < FAST_SECONDS
                && q.text != null && q.text.length() >= LONG_QUESTION_CHARS;
    }

    static int fastCount(QuizSession s, Participant p) {
        double[] t = parseTimes(p.times);
        String c = p.choices == null ? "" : p.choices;
        int count = 0;
        for (int i = 0; i < s.questions.size() && i < t.length && i < c.length(); i++) {
            if (isFast(s.questions.get(i), t[i], c.charAt(i))) count++;
        }
        return count;
    }

    /** The wrong option (0-based) most students picked for a question, or -1 when nobody picked a wrong one. */
    int topWrongOption(int question) {
        int correct = session.questions.get(question).correctOption - 1;
        int best = -1;
        int bestCount = 0;
        for (int o = 0; o < 4; o++) {
            if (o == correct) continue;
            if (optionCounts[question][o] > bestCount) {
                bestCount = optionCounts[question][o];
                best = o;
            }
        }
        return best;
    }

    private void buildAlerts(List<Participant> counted) {
        for (StudentRow r : rows) {
            if (r.fastCount >= 2) {
                alerts.add("⚡ " + r.p.displayName + " answered " + r.fastCount
                        + " long questions in under " + (int) FAST_SECONDS + " second. They may have seen the answers or guessed.");
            }
        }
        for (int a = 0; a < counted.size(); a++) {
            for (int b = a + 1; b < counted.size(); b++) {
                Participant x = counted.get(a);
                Participant y = counted.get(b);
                List<Integer> shared = sharedWrong(x, y);
                if (shared.size() >= SHARED_WRONG_MIN) {
                    StringBuilder qs = new StringBuilder();
                    for (int q : shared) {
                        if (qs.length() > 0) qs.append(", ");
                        qs.append("Q").append(q + 1);
                    }
                    alerts.add("👥 " + x.displayName + " and " + y.displayName + " gave the same wrong answer on "
                            + shared.size() + " questions (" + qs + "). This is only a hint, not proof.");
                }
            }
        }
        for (StudentRow r : rows) {
            if (r.needsHelp) {
                alerts.add("🆘 " + r.p.displayName + " scored " + r.percent + "% and may need help.");
            }
        }
    }

    private List<Integer> sharedWrong(Participant x, Participant y) {
        List<Integer> shared = new ArrayList<>();
        String cx = x.choices == null ? "" : x.choices;
        String cy = y.choices == null ? "" : y.choices;
        for (int i = 0; i < session.questions.size() && i < cx.length() && i < cy.length(); i++) {
            char a = cx.charAt(i);
            char b = cy.charAt(i);
            char right = (char) ('0' + session.questions.get(i).correctOption);
            if (a == b && a != '0' && a != right) shared.add(i);
        }
        return shared;
    }

    // ---------- CSV exports ----------

    private static String date(long millis) {
        return millis <= 0 ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }

    /** Who joined the session: one row per student. */
    static String attendanceCsv(QuizSession s, List<Participant> parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Session,Title,Student,Username,Status,Joined,Finished,Answered,Total,SwitchedAwayCount,SwitchedAwaySeconds,LeftCount,Rejoins\n");
        List<Participant> sorted = new ArrayList<>(parts);
        sorted.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        for (Participant p : sorted) {
            sb.append(s.code).append(',').append(csvEscape(s.title)).append(',')
              .append(csvEscape(p.displayName)).append(',').append(csvEscape(p.username)).append(',')
              .append(p.status).append(',').append(date(p.joinedAt)).append(',').append(date(p.finishedAt)).append(',')
              .append(p.answered).append(',').append(p.total).append(',')
              .append(p.awayCount).append(',').append(p.awaySeconds).append(',')
              .append(p.leftCount).append(',').append(p.rejoinCount).append('\n');
        }
        return sb.toString();
    }

    /** The whole class report as CSV: summary, per-question table (with wrong-answer analysis), students, alerts. */
    String reportCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("Class report,").append(csvEscape(session.title)).append(',').append(session.code).append('\n');
        sb.append("Students,").append(students).append('\n');
        sb.append("Finished,").append(finished).append('\n');
        sb.append("Average score,").append(String.format(Locale.US, "%.1f", avgScore)).append('\n');
        sb.append("Median score,").append(String.format(Locale.US, "%.1f", medianScore)).append('\n');
        sb.append("Average accuracy,").append(String.format(Locale.US, "%.1f%%", avgPercent)).append('\n');
        sb.append("Highest score,").append(highScore).append('\n');
        sb.append("Lowest score,").append(lowScore).append("\n\n");

        sb.append("Score range,Students\n");
        for (int i = 0; i < BUCKETS.length; i++) sb.append(BUCKETS[i]).append(',').append(histogram[i]).append('\n');

        sb.append("\nQuestion #,Topic,Level,Answered,Correct %,Picked 1,Picked 2,Picked 3,Picked 4,No answer,Most picked wrong answer,Question\n");
        for (int i = 0; i < questions.size(); i++) {
            QuestionStat st = questions.get(i);
            int w = topWrongOption(i);
            String wrongText = (w < 0) ? "" : st.question.options[w] + " (" + optionCounts[i][w] + ")";
            sb.append(st.number).append(',').append(csvEscape(st.question.category)).append(',')
              .append(difficultyLabel(st.question.difficulty)).append(',').append(st.answered).append(',')
              .append(st.percent() < 0 ? "" : st.percent() + "%").append(',')
              .append(optionCounts[i][0]).append(',').append(optionCounts[i][1]).append(',')
              .append(optionCounts[i][2]).append(',').append(optionCounts[i][3]).append(',')
              .append(noAnswer[i]).append(',').append(csvEscape(wrongText)).append(',')
              .append(csvEscape(st.question.text)).append('\n');
        }

        sb.append("\nStudent,Status,Score,Correct,Total,Accuracy %,Needs help,Very fast answers,Switched away,Left\n");
        for (StudentRow r : rows) {
            Participant p = r.p;
            sb.append(csvEscape(p.displayName)).append(',').append(p.status).append(',').append(p.score).append(',')
              .append(p.correct).append(',').append(p.total).append(',').append(r.percent).append(',')
              .append(r.needsHelp ? "yes" : "").append(',').append(r.fastCount).append(',')
              .append(p.awayCount).append(',').append(p.leftCount).append('\n');
        }

        sb.append("\nAlerts (hints only)\n");
        for (String a : alerts) sb.append(csvEscape(a)).append('\n');
        return sb.toString();
    }
}