package de.mhus.vance.anus.devmode;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code vance.anus.dev-mode.*}. When {@link #isEnabled()} returns
 * {@code true} the shell gains commands that expose otherwise-masked data
 * (e.g. {@code setting show-password}). Off by default.
 */
@ConfigurationProperties(prefix = "vance.anus.dev-mode")
public class DevModeProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
