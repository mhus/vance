package de.mhus.vance.brain.backup;

import de.mhus.vance.brain.access.IntegrationScopeProfile;
import de.mhus.vance.brain.access.IntegrationSurface;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Scope profile for the {@code vance-liver} local-backup tool
 * ({@code planning/user-backup.md}).
 *
 * <p>The five surfaces match the routes declared in
 * {@link BackupController} §6.1. {@code POST} for the list calls because
 * the filter lists (deny, projectDeny) live in the body — too unwieldy
 * for a query string, and query strings land in access logs. {@code GET}
 * for the content fetch (cache-friendly, supports the ETag = contentVersion
 * flow that lets the tool skip unchanged bytes on a re-run). {@code DELETE}
 * for {@code integration-tokens/current} is NOT in this profile — that
 * door is reachable by every INTEGRATION token via the documented
 * carve-out in {@code NarrowedCredentialCheck}, mirroring the OAuth
 * {@code POST revoke} pattern. A profile entry would dilute the meaning
 * of the {@code scp} claim and would not cover existing tokens.
 *
 * <p>Three flags flip from the {@link IntegrationScopeProfile} defaults:
 *
 * <ul>
 *   <li>{@code requiresProject()} — {@code false}. The tool reads from
 *       every project the user can read, not one pinned project.</li>
 *   <li>{@code slidingLifetime()} — {@code true}. Each successful backup
 *       extends the token's expiry; a forgotten backup that wandered
 *       off expires on its own.</li>
 *   <li>{@code surfaces} — only the five routes above. {@code POST /backup/runs}
 *       is the one signal that extends the token (planning §7.6).</li>
 * </ul>
 */
@Component
public class BackupScopeProfile implements IntegrationScopeProfile {

    @Override
    public String id() {
        return "backup";
    }

    @Override
    public String label() {
        return "Back up all your documents (read-only)";
    }

    @Override
    public List<IntegrationSurface> surfaces() {
        return List.of(
                IntegrationSurface.of("GET", "/backup/info"),
                IntegrationSurface.of("POST", "/backup/projects"),
                IntegrationSurface.of("POST", "/backup/projects/*/entries"),
                IntegrationSurface.of("GET", "/backup/documents/*/content"),
                IntegrationSurface.of("POST", "/backup/runs"));
    }

    @Override
    public boolean requiresProject() {
        return false;
    }

    @Override
    public boolean slidingLifetime() {
        return true;
    }
}
