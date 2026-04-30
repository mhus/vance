package de.mhus.vance.brain.bootstrap;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.bootstrap.*} — feature flags for the boot-time bootstrap.
 *
 * <p>The defaults match the current behaviour for fresh checkouts (the
 * Acme demo tenant gets seeded on first start). Flip to {@code false}
 * in {@code application.yml} or via env var ({@code VANCE_BOOTSTRAP_ACME=false})
 * for a clean production deployment.
 */
@Data
@ConfigurationProperties(prefix = "vance.bootstrap")
public class BootstrapProperties {

    /**
     * Whether to seed the Acme demo tenant — users, project groups,
     * projects, demo documents, the {@code _vance} system project, and
     * the {@code InitSettingsLoader}-driven LLM key import.
     * Idempotent when {@code true}; complete no-op when {@code false}.
     */
    private boolean acme = true;
}
