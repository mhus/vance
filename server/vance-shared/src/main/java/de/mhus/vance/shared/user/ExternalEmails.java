package de.mhus.vance.shared.user;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Normal form of an e-mail address for matching an external sign-in against
 * {@link ExternalIdentity#getExpectedEmail()}.
 *
 * <p>Lowercase and trimmed everywhere. For Gmail additionally: dots in the
 * local part are dropped and {@code googlemail.com} becomes {@code gmail.com} —
 * Google delivers {@code mike.x@gmail.com} and {@code mikex@googlemail.com} to
 * the same mailbox, so a prepared address must match whichever spelling the ID
 * token carries. Other domains keep their dots: whether they are significant
 * is the domain owner's business.
 *
 * <p>Plus addressing is not normalised away but refused by the callers
 * ({@link #hasPlusTag}) — {@code wile+x} must not become a second way to
 * {@code wile}.
 */
public final class ExternalEmails {

    public static final String GMAIL = "gmail.com";
    private static final String GOOGLEMAIL = "googlemail.com";

    private ExternalEmails() {}

    /**
     * Normal form, or {@code null} when {@code email} is not of the shape
     * {@code local@domain}.
     */
    public static @Nullable String normalize(@Nullable String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at != trimmed.indexOf('@') || at == trimmed.length() - 1) {
            return null;
        }
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at + 1);
        if (GOOGLEMAIL.equals(domain)) {
            domain = GMAIL;
        }
        if (GMAIL.equals(domain)) {
            local = local.replace(".", "");
            if (local.isEmpty()) {
                return null;
            }
        }
        return local + "@" + domain;
    }

    /** Domain part of an already normalised address. */
    public static String domain(String normalized) {
        return normalized.substring(normalized.indexOf('@') + 1);
    }

    /** Local part of an already normalised address. */
    public static String localPart(String normalized) {
        return normalized.substring(0, normalized.indexOf('@'));
    }

    /** Whether the address uses plus addressing ({@code name+tag@…}). */
    public static boolean hasPlusTag(String email) {
        int at = email.indexOf('@');
        return email.substring(0, at < 0 ? email.length() : at).contains("+");
    }

    /** Whether the domain is Google's consumer mail domain (either spelling). */
    public static boolean isGmail(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        return GMAIL.equals(d) || GOOGLEMAIL.equals(d);
    }
}
