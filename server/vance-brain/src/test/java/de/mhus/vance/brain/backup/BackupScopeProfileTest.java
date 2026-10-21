package de.mhus.vance.brain.backup;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.access.IntegrationScopeProfile;
import org.junit.jupiter.api.Test;

/**
 * The shape of the {@code backup} profile — the contract that
 * {@code IntegrationScopeRegistry} enforces (no duplicate ids, no
 * empty surfaces) and that the mint form displays.
 *
 * <p>Pinning the id, the label, and the three flag flips from
 * {@link IntegrationScopeProfile}'s defaults is what makes the
 * "Backup" entry in the mint form behave the way
 * {@code planning/user-backup.md} describes — a forgotten backup that
 * runs forever keeps its token alive, a forgotten backup that does
 * not run dies on its own.
 */
class BackupScopeProfileTest {

    private final BackupScopeProfile profile = new BackupScopeProfile();

    @Test
    void id_isStable() {
        // Renaming would invalidate every minted token — the registry is
        // resolved against running code on every request, not against
        // anything copied into the JWT at mint time, but a token carrying
        // a now-unknown id simply stops working.
        assertThat(profile.id()).isEqualTo("backup");
    }

    @Test
    void label_isHumanReadable() {
        // The mint form picks this up; a vague label here would be
        // visible to every user who reads the form.
        assertThat(profile.label()).isNotBlank();
    }

    @Test
    void requiresProject_isFalse_forTenantWideScope() {
        // A tenant-wide tool that pretends to be project-pinned would
        // confuse the mint's pin checks and the NarrowedCredentialCheck
        // would reject the token on the first request.
        assertThat(profile.requiresProject()).isFalse();
    }

    @Test
    void slidingLifetime_isTrue() {
        // The signal that keeps the token alive is POST /backup/runs,
        // not just any authenticated call. A polling-only tool would
        // otherwise extend forever without ever backing up.
        assertThat(profile.slidingLifetime()).isTrue();
    }

    @Test
    void surfaces_matchTheFiveRoutes() {
        // Each surface is one HTTP method + one Ant-style path. The
        // matching is exact-ish by design — POST /backup/projects is the
        // filter list, /backup/projects/*/entries is the cursor listing
        // per project, /backup/documents/*/content is the body fetch
        // with ETag. Anything more than these five is a door the tool
        // does not need.
        assertThat(profile.surfaces()).hasSize(5);
        assertThat(profile.surfaces())
                .anyMatch(s -> s.method().equals("GET") && s.pathPattern().equals("/backup/info"));
        assertThat(profile.surfaces())
                .anyMatch(s -> s.method().equals("POST") && s.pathPattern().equals("/backup/projects"));
        assertThat(profile.surfaces())
                .anyMatch(s -> s.method().equals("POST") && s.pathPattern().equals("/backup/projects/*/entries"));
        assertThat(profile.surfaces())
                .anyMatch(s -> s.method().equals("GET") && s.pathPattern().equals("/backup/documents/*/content"));
        assertThat(profile.surfaces())
                .anyMatch(s -> s.method().equals("POST") && s.pathPattern().equals("/backup/runs"));
    }
}
