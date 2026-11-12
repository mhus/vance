package de.mhus.vance.brain.ai;

/**
 * Which bundled model catalog a tenant works with: the one shipped on the classpath, or the
 * central one an operator published (the Iris catalog). Decided for the whole tenant, there is
 * no cascade; see {@link SettingTenantModelSource}.
 */
public interface TenantModelSource {

    /** Whether {@code tenantId} reads the classpath catalog even though a central one is in force. */
    boolean usesClasspathCatalog(String tenantId);

    /** Every tenant follows the central catalog (what runs without the setting store). */
    static TenantModelSource central() {
        return tenantId -> false;
    }
}
