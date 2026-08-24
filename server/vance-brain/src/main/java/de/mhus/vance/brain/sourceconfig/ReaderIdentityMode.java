package de.mhus.vance.brain.sourceconfig;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * How much a foreign source learns about the person on whose behalf we are
 * calling it.
 *
 * <p>This is <b>our</b> policy about <b>our</b> users, which is why it lives in
 * the source-configuration document and never in a capability the source
 * declares: a foreign system does not get to state what it would like to know
 * about the people reading it.
 *
 * <p>One vocabulary for every subsystem rather than a boolean per subsystem.
 * A {@code sendUser: true} would have meant a salted pseudonym in Centauri and
 * a resolvable login in Jaglan — the same word for two very different
 * transfers, which is exactly the kind of thing nobody separates again later.
 * With three named values the escalation is explicit, and "which sources
 * receive a real identity" is one grep across all configuration documents.
 *
 * <p><b>The order is meaningful</b> and the enum is declared in it:
 * {@code NONE < PSEUDONYM < IDENTITY} is monotone in what leaves the house.
 * That is what makes {@link #atMost} a plain minimum and lets a future value
 * be placed by saying which two it sits between — the same shape the setting
 * protection classes use.
 *
 * <p>Not every subsystem supports every value; each declares its own ceiling
 * (see {@link #atMost}) and refuses what it cannot transport. A configuration
 * that asks for more than the subsystem can deliver is an error at load time,
 * never a silent downgrade — a source told it would be given an identity, and
 * then quietly not given one, fails in a way nobody can see.
 */
public enum ReaderIdentityMode {

    /** Nothing about the reader travels. The default, deliberately. */
    NONE,

    /**
     * A per-source salted pseudonym: stable within one source, uncorrelatable
     * across sources, not reversible. Enough for reader-specific presentation
     * (selection, read marks, language), never enough to authorise.
     */
    PSEUDONYM,

    /**
     * The resolvable user identity. Needed when the source has to decide
     * <em>whether</em> this person may read, or <em>whose</em> data to serve —
     * a personal drive or mailbox cannot be addressed by a pseudonym.
     */
    IDENTITY;

    /** Config value; shared by every subsystem that reads a source document. */
    public static final String FIELD = "readerIdentity";

    /**
     * The boolean this replaced. Kept as a constant, not as a fallback: the
     * point of the rename was that one word meant a salted pseudonym in one
     * subsystem and a resolvable login in another, so honouring the old key
     * would reinstate exactly the ambiguity. It is named here so a document
     * that still carries it can be reported ({@code SourceConfigLoader}) and
     * so it does not travel on to a protocol as an extra.
     */
    public static final String RETIRED_FIELD = "sendActor";

    /**
     * Parse a configured value, falling back when absent, blank or unknown.
     *
     * <p>An unknown word falls back rather than throwing, and always
     * <em>downwards</em>: this is a privacy control, so a typo has to end up
     * more restrictive than intended, never less. The caller logs it.
     */
    public static ReaderIdentityMode parse(@Nullable Object raw, ReaderIdentityMode fallback) {
        if (raw == null) {
            return fallback;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (StringUtils.isBlank(text)) {
            return fallback;
        }
        for (ReaderIdentityMode mode : values()) {
            if (mode.name().toLowerCase(Locale.ROOT).equals(text)) {
                return mode;
            }
        }
        return fallback;
    }

    /** Whether {@code raw} names a known mode; for reporting a typo. */
    public static boolean isKnown(@Nullable Object raw) {
        if (raw == null) {
            return false;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        for (ReaderIdentityMode mode : values()) {
            if (mode.name().toLowerCase(Locale.ROOT).equals(text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * This mode capped by {@code ceiling} — the minimum of the two.
     *
     * <p>Used for both ceilings that exist: the tenant-level document over the
     * project-level one, and a subsystem over what it can actually transport.
     * A ceiling only ever restricts; there is no operation that widens, and
     * that asymmetry is the point.
     */
    public ReaderIdentityMode atMost(ReaderIdentityMode ceiling) {
        return compareTo(ceiling) <= 0 ? this : ceiling;
    }
}
