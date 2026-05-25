package de.mhus.vance.anus.devmode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Eager guard that prints a loud warning on boot when dev-mode is on and
 * refuses to start the application if a {@code prod} / {@code production}
 * Spring profile is active at the same time.
 *
 * <p>Only registered when {@code vance.anus.dev-mode.enabled=true} — when
 * the flag is unset/false, this bean does not exist and the shell behaves
 * exactly as before.
 */
@Component
@ConditionalOnProperty(name = "vance.anus.dev-mode.enabled", havingValue = "true")
public class DevModeBootCheck {

    private static final Logger log = LoggerFactory.getLogger(DevModeBootCheck.class);

    public DevModeBootCheck(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            String normalised = profile.toLowerCase();
            if (normalised.equals("prod") || normalised.equals("production")) {
                throw new IllegalStateException(
                        "vance.anus.dev-mode.enabled=true is forbidden while Spring profile '"
                                + profile + "' is active. Remove the dev-mode flag or "
                                + "deactivate the production profile.");
            }
        }
        log.warn("================================================================");
        log.warn("  ANUS DEV-MODE IS ENABLED.");
        log.warn("  Additional shell commands print decrypted secrets to stdout.");
        log.warn("  Disable via vance.anus.dev-mode.enabled=false for normal use.");
        log.warn("================================================================");
    }
}
