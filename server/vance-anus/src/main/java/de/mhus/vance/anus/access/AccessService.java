package de.mhus.vance.anus.access;

import de.mhus.vance.shared.audit.AuditService;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
 */
@Service
@Slf4j
public class AccessService {

    /**
     * v1 default plaintext password. Used when no hash is configured —
     * see the note on the constructor below. Kept as a constant so tests
     * can pin it without copy-pasting.
     */
    public static final String DEFAULT_PASSWORD = "vance-anus-login";

    private final AccessProperties properties;
    private final AuditService auditService;
    private final BCryptPasswordEncoder encoder;
    /** Effective hash — either the configured one, or a freshly generated one for {@link #DEFAULT_PASSWORD}. */
    private final String effectiveHash;
    private final boolean usingDefault;

    @Nullable private volatile Instant authorizedUntil;
    /**
     * Marks the window as armed by {@code --sudo} rather than a password
     * login. Suppresses the default-password warning (irrelevant in one-shot
     * mode — the process exits after the requested commands) and lets the
     * audit trail distinguish unattended sudo runs from interactive logins.
     */
    private volatile boolean sudoMode;

    public AccessService(AccessProperties properties, AuditService auditService) {
        this.properties = properties;
        this.auditService = auditService;
        this.encoder = new BCryptPasswordEncoder();
        if (StringUtils.isBlank(properties.getPasswordHash())) {
            // v1 fallback: no hash configured → accept the well-known
            // default. The hash is regenerated per process so a leak of
            // an old hash is meaningless. Operators are loudly told to
            // override this in production via VANCE_ANUS_PASSWORD_HASH.
            this.effectiveHash = encoder.encode(DEFAULT_PASSWORD);
            this.usingDefault = true;
            log.warn("vance.anus.access.password-hash is not set — falling back to "
                    + "the v1 default password. Set VANCE_ANUS_PASSWORD_HASH "
                    + "(e.g. in confidential/anus.env) for anything beyond local dev.");
        } else {
            this.effectiveHash = properties.getPasswordHash();
            this.usingDefault = false;
        }
    }

    /**
     * {@code true} iff the service is running on the built-in v1 default
     * password AND the shell is in interactive mode. In {@code --sudo}
     * one-shot mode the warning is meaningless: the process exits after the
     * requested commands, there is no shell to leave open.
     */
    public boolean isUsingDefaultPassword() {
        return usingDefault && !sudoMode;
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
     * Verifies {@code plainPassword} against the configured BCrypt hash.
     * On success, arms the sliding window and returns {@code true}; on
     * mismatch, leaves the window untouched and returns {@code false}.
     * Blank passwords are rejected without consulting BCrypt.
     */
    public synchronized boolean login(String plainPassword) {
        if (StringUtils.isBlank(plainPassword)) {
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
}
