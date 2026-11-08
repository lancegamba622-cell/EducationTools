package com.mycompany.educationaltools;

/*
 * ============================================================================
 *  STATUS - every status / role text in one place, so a typo becomes a
 *  compile error instead of a silent bug.
 *  The values are plain Strings on purpose: they are written to the
 *  .properties files, so old session / participant files keep working.
 * ============================================================================
 */
final class Status {
    private Status() { }

    // QuizSession.status:   LOBBY -> LIVE -> ENDED
    static final String LOBBY = "LOBBY";
    static final String LIVE = "LIVE";
    static final String ENDED = "ENDED";

    // Participant.status
    static final String JOINED = "JOINED";     // waiting in the lobby
    static final String PLAYING = "PLAYING";
    static final String FINISHED = "FINISHED";
    static final String LEFT = "LEFT";

    // UserAccount.role
    static final String TEACHER = "TEACHER";
    static final String STUDENT = "STUDENT";
}