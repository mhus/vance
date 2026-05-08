package de.mhus.vance.brain.eddie.triage;

/**
 * What Eddie's Channel-Adapter does with a worker's voice-relevant
 * frame — see {@code planning/eddie-moderator-erweiterung.md}.
 *
 * <ul>
 *   <li>{@link #VERBATIM} — pass the worker text through 1:1 as Eddie's
 *       voice. Allowed only for short prose without Markdown / code.</li>
 *   <li>{@link #REFORMULATE} — let Eddie rephrase. Mid-length output
 *       where the meaning carries over but the wording isn't
 *       speak-friendly.</li>
 *   <li>{@link #INBOX} — too long / too structural for voice; route
 *       the original to the user's inbox + a short spoken note.</li>
 * </ul>
 */
public enum TriageDecision {
    VERBATIM,
    REFORMULATE,
    INBOX
}
