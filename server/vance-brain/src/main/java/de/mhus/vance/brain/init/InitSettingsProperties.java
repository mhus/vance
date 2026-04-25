package de.mhus.vance.brain.init;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.init.*} — configuration for the startup settings loader.
 */
@Data
@ConfigurationProperties(prefix = "vance.init")
public class InitSettingsProperties {

    /**
     * Path to the YAML file with bootstrap settings (LLM keys, provider
     * defaults, etc.). Resolved against the JVM working directory; if it
     * doesn't exist there, the loader walks parent directories looking
     * for {@code confidential/init-settings.yaml} so the brain can be
     * started from anywhere within the workbench tree.
     */
    private String settingsFile = "confidential/init-settings.yaml";
}
