package de.mhus.vance.shared.workspace;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The git credentials kept as encrypted settings, one per account and host under
 * {@link GitCredentialKey#storageKey()}. Project scope wins over the tenant-wide default, per
 * account. Only server code reads a token ({@link SettingService#getDecryptedPassword}); the
 * {@code credential.*} namespace is closed to secret references, so neither a script nor the model
 * can resolve one.
 */
@Component
public class GitCredentialStore {

    private final SettingService settings;

    public GitCredentialStore(SettingService settings) {
        this.settings = settings;
    }

    /**
     * Where a credential lives.
     *
     * @param referenceId the project (or the tenant hub) whose settings hold it
     */
    public record Located(GitCredentialKey key, String referenceId) {

        /** Whether it is the tenant-wide default rather than the project's own. */
        public boolean tenantWide() {
            return HomeBootstrapService.TENANT_PROJECT_NAME.equals(referenceId);
        }
    }

    /** Every account with a credential for {@code host}, visible from {@code projectId}. */
    public List<Located> locate(String tenantId, @Nullable String projectId, String host) {
        Map<String, Located> byUser = new LinkedHashMap<>();
        for (String referenceId : scopes(projectId)) { // outer first, so the inner scope overwrites
            for (SettingDocument doc : settings.findByPrefix(
                    tenantId, SettingService.SCOPE_PROJECT, referenceId, GitCredentialKey.PREFIX)) {
                GitCredentialKey key = GitCredentialKey.parse(doc.getKey());
                if (key != null && doc.getType().encrypted() && key.host().equalsIgnoreCase(host)) {
                    byUser.put(key.user(), new Located(key, referenceId));
                }
            }
        }
        return new ArrayList<>(byUser.values());
    }

    /** The credential for exactly {@code key}, if the project (or the tenant) has one. */
    public Optional<Located> locate(String tenantId, @Nullable String projectId, GitCredentialKey key) {
        return locate(tenantId, projectId, key.host()).stream()
                .filter(l -> l.key().user().equals(key.user()))
                .findFirst();
    }

    /** The token, decrypted; {@code null} when it cannot be read. Never hand this to a model. */
    public @Nullable String token(String tenantId, Located located) {
        return settings.getDecryptedPassword(
                tenantId,
                SettingService.SCOPE_PROJECT,
                located.referenceId(),
                located.key().storageKey());
    }

    /**
     * Removes the project's own credential for {@code key}.
     *
     * @return {@code false} when the project has none (a tenant-wide one stays)
     */
    public boolean revoke(String tenantId, String projectId, GitCredentialKey key) {
        if (!settings.exists(tenantId, SettingService.SCOPE_PROJECT, projectId, key.storageKey())) {
            return false;
        }
        settings.delete(tenantId, SettingService.SCOPE_PROJECT, projectId, key.storageKey());
        return true;
    }

    private static List<String> scopes(@Nullable String projectId) {
        if (projectId == null || projectId.isBlank() || HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            return List.of(HomeBootstrapService.TENANT_PROJECT_NAME);
        }
        return List.of(HomeBootstrapService.TENANT_PROJECT_NAME, projectId);
    }
}
