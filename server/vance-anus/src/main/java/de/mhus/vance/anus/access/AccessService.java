package de.mhus.vance.anus.access;

import de.mhus.vance.shared.audit.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * In-memory authorisation state for the Anus shell.
 *
 * <p>Holds a single sliding-window {@code authorizedUntil} timestamp.
 * {@link #login(String)} verifies the BCrypt-hashed password from
 * {@link AccessProperties} and arms the window;
 * {@link #requireAuthorized()} both checks the window and refreshes it
 * (every protected call counts as activity); {@link #logout()} clears it.
 *
 * <p>Anus is a single-user tool — there is no per-session, per-user or
 * per-IP scoping. The whole JVM has exactly one auth state.
 *
 * <p>The configured credential ({@code vance.anus.access.password-hash})
 * uses Spring's {@link DelegatingPasswordEncoder} {@code {id}} prefix
 * convention so operators can pick their friction/security trade-off:
 * <ul>
 *   <li>{@code {noop}my-secret} — plaintext. Zero friction: no hashing tool
 *       needed, just drop the password into the Kubernetes/Docker secret.
 *       Deliberately supported because forcing a BCrypt hash is friction
 *       enough that people leave it unset and ship the v1 default instead.</li>
 *   <li>{@code {bcrypt}$2a$…} — a BCrypt hash minted via the {@code hash}
 *       shell command, for those who want it hashed at rest.</li>
 *   <li>A bare {@code $2a$…} (no prefix) is still accepted as BCrypt for
 *       backward compatibility with credentials minted before the prefix
 *       convention existed.</li>
 * </ul>
 */
@Service
@Slf4j
public class AccessService {

    private final AccessProperties properties;
    private final AuditService auditService;
    private final PasswordEncoder encoder;
    /** The configured credential, or blank if none is set. Blank ⇒ login disabled. */
    private final String effectiveHash;
    /** {@code true} iff a non-blank credential was configured. */
    private final boolean configured;

    @Nullable private volatile Instant authorizedUntil;
    /**
     * Marks the window as armed by {@code --sudo} rather than a password
     * login. Suppresses the "login disabled" warning (irrelevant in one-shot
     * mode — the process exits after the requested commands) and lets the
     * audit trail distinguish unattended sudo runs from interactive logins.
     */
    private volatile boolean sudoMode;

    public AccessService(AccessProperties properties, AuditService auditService) {
        this.properties = properties;
        this.auditService = auditService;
        this.encoder = buildEncoder();
        this.effectiveHash = StringUtils.defaultString(properties.getPasswordHash());
        this.configured = StringUtils.isNotBlank(this.effectiveHash);
        if (!configured) {
            // No credential configured → login is disabled entirely. There is
            // no built-in default password: an unset secret means nobody can
            // authenticate, which is safer than shipping a well-known default.
            // Boot still succeeds; the operator sets VANCE_ANUS_PASSWORD_HASH
            // (e.g. {noop}<password> or a {bcrypt} hash) to enable login.
            log.warn("vance.anus.access.password-hash is not set — Anus login is "
                    + "DISABLED. Set VANCE_ANUS_PASSWORD_HASH (e.g. {noop}<password> "
                    + "or a {bcrypt} hash from `vance-anus hash`) to enable it.");
        }
    }

    /**
     * {@code true} iff a login credential is configured. When {@code false},
     * every {@link #login(String)} fails — there is no default fallback.
     */
    public boolean isLoginConfigured() {
        return configured;
    }

    /**
     * {@code true} iff login is disabled (no credential configured) AND the
     * shell is interactive. In {@code --sudo} one-shot mode the warning is
     * meaningless: the process exits after the requested commands, and sudo
     * arms the window without a login anyway.
     */
    public boolean isLoginDisabledWarning() {
        return !configured && !sudoMode;
    }

    /** {@code true} iff the current authorisation window was armed by {@code --sudo}. */
    public boolean isSudoMode() {
        return sudoMode;
    }

    /**
     * Arms the authorisation window without a password check, for the
     * {@code --sudo} one-shot mode. The caller is the Anus bootstrap, which
     * has already proven it can launch the process — no further credential
     * gate is meaningful here. Recorded in the audit log under
     * {@code anus.sudo.arm} so unattended runs are distinguishable from
     * interactive logins.
     */
    public synchronized void armForSudo() {
        sudoMode = true;
        extendWindow();
        log.info("Anus armed for --sudo execution — window armed for {}", properties.getTimeout());
        auditService.anusSudoArm();
    }

    /**
     * Verifies {@code plainPassword} against the configured credential.
     * On success, arms the sliding window and returns {@code true}; on
     * mismatch, leaves the window untouched and returns {@code false}.
     * Blank passwords — and every attempt when no credential is configured —
     * are rejected without consulting the encoder.
     */
    public synchronized boolean login(String plainPassword) {
        if (StringUtils.isBlank(plainPassword)) {
            auditService.anusLoginFailure();
            return false;
        }
        if (!configured) {
            log.warn("Anus login rejected — no credential configured "
                    + "(VANCE_ANUS_PASSWORD_HASH unset); login is disabled");
            auditService.anusLoginFailure();
            return false;
        }
        boolean ok = encoder.matches(plainPassword, effectiveHash);
        if (ok) {
            extendWindow();
            log.info("Anus login succeeded — window armed for {}", properties.getTimeout());
            auditService.anusLoginSuccess();
        } else {
            log.warn("Anus login failed");
            auditService.anusLoginFailure();
        }
        return ok;
    }

    /** Drops the authorisation window. Idempotent. */
    public synchronized void logout() {
        if (authorizedUntil != null) {
            log.info("Anus logout");
            auditService.authLogout(null, null);
        }
        authorizedUntil = null;
    }

    /**
     * Returns {@code true} if the window is still open. Does NOT extend it —
     * read-only check, used by {@code StatusCommand}.
     */
    public synchronized boolean isAuthorized() {
        return authorizedUntil != null && Instant.now().isBefore(authorizedUntil);
    }

    /** Remaining time on the window, or {@link Duration#ZERO} if expired/not set. */
    public synchronized Duration remaining() {
        if (authorizedUntil == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(Instant.now(), authorizedUntil);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * Verifies the window is open AND extends it. Throws if not authorised.
     * Called by the {@link RequiresAuth} aspect before every protected
     * command. Auto-clears the field on expiry so {@link #isAuthorized()}
     * stays consistent.
     */
    public synchronized void requireAuthorized() {
        if (authorizedUntil == null || !Instant.now().isBefore(authorizedUntil)) {
            authorizedUntil = null;
            throw new NotAuthorizedException(
                    "Not authorized. Run 'login' first.");
        }
        extendWindow();
    }

    private void extendWindow() {
        authorizedUntil = Instant.now().plus(properties.getTimeout());
    }

    /**
     * Builds the delegating encoder that understands the {@code {noop}} and
     * {@code {bcrypt}} prefixes. Unprefixed values are matched as BCrypt so a
     * bare {@code $2a$…} keeps working.
     */
    @SuppressWarnings("deprecation") // NoOpPasswordEncoder is discouraged in
    // general but is exactly the point here: it is the standard backing for
    // the opt-in {noop} prefix that lets operators store a plaintext password.
    private static PasswordEncoder buildEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        encoders.put("noop", NoOpPasswordEncoder.getInstance());
        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("bcrypt", encoders);
        delegating.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return delegating;
    }
}
