package de.mhus.vance.shared.user;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The external sign-in (SSO) an account is bound to — embedded in
 * {@link UserDocument#getExternalIdentity()}. One per account.
 *
 * <p>Two states:
 * <ul>
 *   <li><b>Prepared</b> — {@link #subject} is {@code null}, {@link #expectedEmail}
 *       is set. An admin said "the person signing in as this address is this
 *       account". The first sign-in whose verified address matches binds the
 *       subject.</li>
 *   <li><b>Linked</b> — {@link #subject} is set. From now on the identity is
 *       {@code (issuer, subject)}; the address no longer matters.</li>
 * </ul>
 *
 * <p>An account carrying this record has no password and cannot get one —
 * {@link UserService#setPasswordHash} refuses. "Has no password" alone is not
 * the prepared state: passwordless accounts exist for other reasons
 * (service accounts, {@code --no-password}), and none of them may be taken
 * over by a sign-in that happens to carry a matching name.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalIdentity {

    /** Google's issuer in its canonical spelling. */
    public static final String GOOGLE_ISSUER = "https://accounts.google.com";

    /** The scheme-less spelling Google may also put into {@code iss}. */
    static final String GOOGLE_ISSUER_BARE = "accounts.google.com";

    /** Provider key from the SSO config, e.g. {@code google}. */
    private String provider = "";

    /** OIDC issuer, e.g. {@code https://accounts.google.com}. */
    private String issuer = "";

    /** OIDC {@code sub}; {@code null} while only prepared. */
    private @Nullable String subject;

    /**
     * Address the first sign-in must carry, in normal form (see
     * {@code SsoEmails}); set by the admin when preparing the account.
     */
    private @Nullable String expectedEmail;

    /** Address from the last ID token — display only. */
    private @Nullable String email;

    private @Nullable Instant preparedAt;

    private @Nullable Instant linkedAt;

    private @Nullable Instant lastLoginAt;

    /**
     * The one spelling of an issuer that is stored and looked up. Google may
     * send {@code iss} with or without scheme (OIDC Core §3.1.3.7 allows both
     * for Google); both name the same identity provider, and since the issuer
     * is half of the identity key, two spellings would be two identities. A
     * trailing slash is dropped for the same reason.
     */
    public static String canonicalIssuer(String issuer) {
        String trimmed = issuer.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return GOOGLE_ISSUER_BARE.equals(trimmed) ? GOOGLE_ISSUER : trimmed;
    }

    /** Whether the subject is bound — the account can sign in only this way. */
    public boolean isLinked() {
        return subject != null;
    }
}
