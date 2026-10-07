package de.mhus.vance.brain.admin.action;

import org.jspecify.annotations.Nullable;

/**
 * Where and for whom an action runs. {@code projectId} is {@code null}
 * in the tenant scope and a plain project name otherwise; actions use
 * it to scope their settings/cascade lookups exactly the way a spawn
 * would.
 *
 * @param tenantId  tenant the operator called from (never blank)
 * @param projectId target project, or {@code null} for the tenant scope
 * @param userId    the operator's login — for audit logging; actions
 *                  must not derive permissions from it (the controller
 *                  has enforced ADMIN already)
 */
public record AdminActionContext(
        String tenantId,
        @Nullable String projectId,
        @Nullable String userId) {

    /**
     * The project layer a scope-aware lookup should read: the chosen
     * project, or the tenant's {@code _tenant} project in the tenant
     * scope — the same convention {@code getStringValueCascade} uses.
     */
    public String effectiveProjectId() {
        return projectId != null && !projectId.isBlank()
                ? projectId
                : de.mhus.vance.shared.home.HomeBootstrapService.TENANT_PROJECT_NAME;
    }
}
