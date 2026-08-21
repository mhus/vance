package de.mhus.vance.brain.kit.provisioning;

/**
 * Where a desired-list is being assembled for, and out of which entry.
 *
 * <p>A record rather than three parameters so a mechanism that later
 * needs one more thing does not change every implementation's signature.
 *
 * @param tenantId whose project
 * @param projectId which project — never null here, unlike on the fetch
 *        path: provisioning only happens into a project
 * @param entry the line that named this mechanism, including its
 *        credential and authority
 */
public record KitProvisioningContext(
        String tenantId,
        String projectId,
        KitProvisioningEntry entry) {}
