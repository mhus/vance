package de.mhus.vance.api.settings;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Value type of a setting. Two of them are encrypted at rest and masked on read
 * through the public API ({@link #encrypted()}); all others round-trip as plain
 * text.
 *
 * <h2>Two dimensions in one enum</h2>
 * A deliberate compromise, and the reason the constant names do not form one
 * clean series. This enum carries a <b>value type</b> ({@link #STRING},
 * {@link #INT}, {@link #LONG}, {@link #DOUBLE}, {@link #BOOLEAN} — how the value
 * is parsed) <em>and</em> a <b>protection class</b>:
 *
 * <pre>
 *   STRING  →  HIDDEN  →  PASSWORD        increasing protection
 * </pre>
 *
 * {@link #HIDDEN} and {@link #PASSWORD} are not value types at all — they are
 * properties of a string, and modelled cleanly they would be a flag on any type.
 * A flag would have cost a DTO field, a UI checkbox, a kit round-trip case and a
 * "who may raise it" rule; a level on the existing enum fits the concept the rest
 * of the settings code already has. The price is that there is <b>no protected
 * {@code INT} or {@code BOOLEAN}</b> — irrelevant in practice, but it is the
 * boundary.
 *
 * <p>The two predicates below are exactly the two thresholds on that scale, and
 * both are monotone along it:
 *
 * <table border="1">
 *   <caption>Predicate values along the protection scale</caption>
 *   <tr><th>Level</th><th>{@code encrypted()}</th><th>{@code referenceReadable()}</th></tr>
 *   <tr><td>{@code STRING} (and the other value types)</td><td>false</td><td>true</td></tr>
 *   <tr><td>{@code HIDDEN}</td><td>true</td><td>true</td></tr>
 *   <tr><td>{@code PASSWORD}</td><td>true</td><td>false</td></tr>
 * </table>
 *
 * <p>So a future level is placed by saying <em>between which thresholds</em> it
 * sits — append the constant, set the two predicates, done. No third comparison
 * anywhere in the tree.
 *
 * <h2>PASSWORD vs. HIDDEN</h2>
 * Both are stored encrypted with the server key and are never returned through
 * the generic string-read path. They differ in <b>which channel</b> may resolve
 * them:
 *
 * <ul>
 *   <li>{@link #PASSWORD} — a real secret. Only compiled server code reads it
 *       by a fixed key (LLM provider API keys, {@code vault.clientSecret},
 *       search-provider keys, OAuth client secrets). It can neither be read nor
 *       overwritten through an agent-reachable path.</li>
 *   <li>{@link #HIDDEN} — merely concealed, not a real secret. Same encryption,
 *       but resolvable through an authored {@code {{secret:…}}} reference, so
 *       tool documents (REST/SMTP/IMAP templates), compose {@code secrets:}
 *       blocks and scripts ({@code vance.secret(…)}) can use it.</li>
 * </ul>
 *
 * <p>The distinction is deliberately <em>not</em> a permission: an agent runs
 * with the human's own {@code SecurityContext}, so no role check can tell
 * "the user typed this value" from "the model called a tool in the user's
 * session". See {@code planning/setting-type-hidden.md} §13.
 *
 * <p>New encrypted types must be appended, never inserted, and must be added to
 * {@link #encrypted()} — see the sweep rationale in that plan §4.1.
 */
@GenerateTypeScript("settings")
public enum SettingType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    PASSWORD,
    HIDDEN;

    /**
     * Whether values of this type are encrypted at rest — they must be written
     * through the dedicated encrypted-write path and are never handed out by the
     * generic string getters.
     *
     * <p>This predicate exists so no call site compares against a specific
     * constant: {@code == SettingType.PASSWORD} silently excludes HIDDEN, and a
     * missed exclusion in a write path would persist a secret in cleartext.
     */
    public boolean encrypted() {
        return this == PASSWORD || this == HIDDEN;
    }

    /**
     * Whether a value of this type may be resolved through an authored
     * {@code {{secret:…}}} reference — i.e. from a tool document, a compose
     * manifest or a script, all of which an agent can write.
     *
     * <p>True for every plaintext type (those carry no secret to leak) and for
     * {@link #HIDDEN}; false only for {@link #PASSWORD}.
     */
    public boolean referenceReadable() {
        return this != PASSWORD;
    }
}
