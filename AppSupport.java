package com.mycompany.educationaltools;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

/*
 * ============================================================================
 *  APP SUPPORT - input limits + a global crash handler that writes a log file
 *  instead of letting the program freeze silently.
 * ============================================================================
 */

/** Maximum lengths for text typed or imported by users, plus a cleaner for control characters. */
final class Limits {
    private Limits() { }

    static final int NAME = 40;
    static final int TITLE = 60;
    static final int CATEGORY = 30;
    static final int QUESTION = 400;
    static final int OPTION = 120;
    static final int HINT = 200;

    /** Removes control characters, trims the text and cuts it to the maximum length. */
    static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").replace('\n', ' ').replace('\t', ' ').trim();
        return t.length() > max ? t.substring(0, max).trim() : t;
    }
}

final class AppSupport {
    private AppSupport() { }

    static final String LOG_FILE = "educationaltools_error.log";
    private static long lastDialog = 0;

    /** Call once from main(): uncaught errors are logged and shown in a small message instead of failing silently. */
    static void installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            log(error);
            long now = System.currentTimeMillis();
            if (now - lastDialog > 10_000) {
                lastDialog = now;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                        "Something went wrong, but the program is still running.\n"
                                + "The details were saved in " + LOG_FILE + ".\n\n"
                                + String.valueOf(error.getMessage()),
                        "Unexpected error", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    /** Appends an error with its stack trace to the log file. */
    static void log(Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        try (FileWriter w = new FileWriter(LOG_FILE, true)) {
            w.write("[" + new Date() + "]\n" + trace + "\n");
        } catch (IOException ignored) {
            // nothing else we can do
        }
    }
}