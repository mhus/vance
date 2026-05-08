package de.mhus.vance.anus.access;

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

    private final AccessProperties properties;
    private final BCryptPasswordEncoder encoder;

    @Nullable private volatile Instant authorizedUntil;

    public AccessService(AccessProperties properties) {
        this.properties = properties;
        if (StringUtils.isBlank(properties.getPasswordHash())) {
            throw new IllegalStateException(
                    "vance.anus.access.password-hash is not set. "
                            + "Provide it via the VANCE_ANUS_PASSWORD_HASH "
                            + "environment variable. Use the 'hash' command "
                            + "to generate a fresh BCrypt hash.");
        }
        this.encoder = new BCryptPasswordEncoder();
    }

    /**
     * Verifies {@code plainPassword} against the configured BCrypt hash.
     * On success, arms the sliding window and returns {@code true}; on
     * mismatch, leaves the window untouched and returns {@code false}.
     * Blank passwords are rejected without consulting BCrypt.
     */
    public synchronized boolean login(String plainPassword) {
        if (StringUtils.isBlank(plainPassword)) {
            return false;
        }
        boolean ok = encoder.matches(plainPassword, properties.getPasswordHash());
        if (ok) {
            extendWindow();
            log.info("Anus login succeeded — window armed for {}", properties.getTimeout());
        } else {
            log.warn("Anus login failed");
        }
        return ok;
    }

    /** Drops the authorisation window. Idempotent. */
    public synchronized void logout() {
        if (authorizedUntil != null) {
            log.info("Anus logout");
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
