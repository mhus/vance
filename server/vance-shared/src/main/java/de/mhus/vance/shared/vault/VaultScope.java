package de.mhus.vance.shared.vault;

import org.jspecify.annotations.Nullable;

/**
 * The scope a vault access is resolved against. Mirrors the setting cascade
 * {@code user → project → tenant}: a {@code vault.*} binding set on the
 * requesting user's home project wins over the project binding, which wins
 * over the tenant-wide {@code _vance} binding.
 *
 * <p>{@code userId} / {@code projectId} are {@code @Nullable} on purpose —
 * headless / service-account runs (a compose block under {@code _damogran},
 * a scheduler {@code runAs}) carry no interactive user, so the user layer is
 * skipped and resolution falls through to project / tenant. That is the
 * intended behaviour: automation uses the shared project vault, an interactive
 * user can override at their own scope.
 *
 * @param tenantId  the tenant ({@code TenantDocument.name}); required
 * @param userId    the requesting user ({@code UserDocument.name}), or
 *                  {@code null} for non-user callers
 * @param projectId the addressed project ({@code ProjectDocument.name}), or
 *                  {@code null} to resolve only against the user / tenant layer
 */
public record VaultScope(
        String tenantId,
        @Nullable String userId,
        @Nullable String projectId) {

    public VaultScope {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }
}
