package de.mhus.vance.brain.wonko;

/** Lifecycle of one register topic (plan §5.2). */
public enum WonkoTopicStatus {
    /** Found, not asked yet. */
    OPEN,
    /** In the question set currently waiting for an answer. */
    ASKED,
    /** Answered — asked again only on a new contradiction. */
    ANSWERED,
    /** "Good as it is" — closed together with all sub-topics. */
    SETTLED,
    /** Wonko decided it within its mandate — reopened only by the user. */
    DECIDED,
    /** The user wants to answer later. */
    LATER,
    /** The user does not know — reported as open, not asked again. */
    UNKNOWN,
    /** Came back as a contradiction after it was answered again — reported, never asked a third time. */
    CONFLICT,
    /** The model could not determine it (escape outcome) — reported with its reason, not asked. */
    UNCLEAR
}
