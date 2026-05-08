package de.mhus.vance.anus.access;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code vance.anus.access.*}.
 *
 * <p>{@code passwordHash} is the BCrypt hash the {@code login} command checks
 * the entered plaintext against. It is required at boot time — if blank,
 * {@link AccessProperties#getPasswordHash()} forces operators to provide it
 * via the {@code VANCE_ANUS_PASSWORD_HASH} environment variable. The {@code
 * hash} command (no auth required) prints a fresh BCrypt hash for any plain
 * password.
 *
 * <p>{@code timeout} is the sliding window of inactivity after which the
 * authorisation auto-expires. Each protected command call resets the clock.
 */
@ConfigurationProperties(prefix = "vance.anus.access")
public class AccessProperties {

    private String passwordHash = "";
    private Duration timeout = Duration.ofMinutes(10);

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
