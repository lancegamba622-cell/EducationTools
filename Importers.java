package com.mycompany.educationaltools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * ============================================================================
 *  IMPORTERS - pure parsing code (no Swing) for question files.
 *    * CSV    : category,difficulty,question,option1..option4,correct_option,hint
 *    * Aiken  : the plain-text format Moodle can export:
 *                 Question text
 *                 A. first option
 *                 B. second option
 *                 C. third option
 *                 D. fourth option
 *                 ANSWER: B
 *               (blank line between questions; 2 options "True"/"False" = True/False question)
 * ============================================================================
 */
final class Importers {
    private Importers() { }

    private static final Pattern OPTION_LINE = Pattern.compile("^([A-Za-z])[.)]\\s+(.*)$");
    private static final Pattern ANSWER_LINE = Pattern.compile("^(?i)ANSWER\\s*:\\s*([A-Za-z])\\s*$");

    /** Simple CSV line parser: supports "quoted, fields" and "" as an escaped quote. */
    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    /** "1"/"Easy" -> 1, "2"/"Medium" -> 2, "3"/"Hard" -> 3, anything else -> -1. */
    static int parseDifficulty(String s) {
        switch (s.trim().toLowerCase()) {
            case "1": case "easy":   return 1;
            case "2": case "medium": return 2;
            case "3": case "hard":   return 3;
            default: return -1;
        }
    }

    /**
     * Reads Aiken-format lines. Questions get ids firstId, firstId+1, ...
     * Problems (at most 5) are added to the list so the teacher knows what was skipped.
     */
    static List<Question> parseAiken(List<String> lines, String category, int difficulty, int firstId, List<String> problems) {
        List<Question> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<String> options = new ArrayList<>();
        int nextId = firstId;
        int lineNo = 0;

        for (String raw : lines) {
            lineNo++;
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty()) continue;

            Matcher answer = ANSWER_LINE.matcher(line);
            Matcher option = OPTION_LINE.matcher(line);

            if (answer.matches()) {
                String problem = null;
                Question q = null;
                int n = options.size();
                int idx = Character.toUpperCase(answer.group(1).charAt(0)) - 'A';
                if (text.length() == 0) {
                    problem = "answer without a question";
                } else if (idx < 0 || idx >= n) {
                    problem = "the answer letter does not match an option";
                } else if (n == 2 && isTrueFalse(options)) {
                    q = new Question(nextId, category, Limits.clean(text.toString(), Limits.QUESTION),
                            new String[]{"True", "False"}, idx + 1, difficulty, "", Question.TYPE_TF, null);
                } else if (n == 4) {
                    q = new Question(nextId, category, Limits.clean(text.toString(), Limits.QUESTION),
                            cleanAll(options), idx + 1, difficulty, "", Question.TYPE_MC, null);
                } else {
                    problem = "needs 4 options (or True/False), found " + n;
                }
                if (q != null) {
                    result.add(q);
                    nextId++;
                } else if (problems.size() < 5) {
                    problems.add("Line " + lineNo + ": " + problem);
                }
                text.setLength(0);
                options.clear();
            } else if (option.matches() && option.group(1).toUpperCase().charAt(0) - 'A' == options.size() && text.length() > 0) {
                options.add(option.group(2));
            } else {
                if (!options.isEmpty()) {
                    // text after options but no ANSWER line: the previous question is incomplete
                    if (problems.size() < 5) problems.add("Line " + lineNo + ": the previous question has no ANSWER line");
                    text.setLength(0);
                    options.clear();
                }
                if (text.length() > 0) text.append(' ');
                text.append(line);
            }
        }
        if (!options.isEmpty() && problems.size() < 5) problems.add("The last question has no ANSWER line");
        return result;
    }

    private static boolean isTrueFalse(List<String> options) {
        return options.get(0).trim().equalsIgnoreCase("true") && options.get(1).trim().equalsIgnoreCase("false");
    }

    private static String[] cleanAll(List<String> options) {
        String[] out = new String[options.size()];
        for (int i = 0; i < out.length; i++) out[i] = Limits.clean(options.get(i), Limits.OPTION);
        return out;
    }
}