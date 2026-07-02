package de.mhus.vance.addon.brain.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the deterministic, Mongo-free helpers of
 * {@link WorkspaceFormService}: fence saveScript path resolution.
 */
class WorkspaceFormServiceTest {

    @Test
    void resolveRelative_bareName_isResolvedAgainstDocFolder() {
        assertThat(WorkspaceFormService.resolveRelative(
                "apps/ws/data/noten.records.json", "update_all.js"))
                .isEqualTo("apps/ws/data/update_all.js");
    }

    @Test
    void resolveRelative_leadingSlash_isProjectAbsolute() {
        assertThat(WorkspaceFormService.resolveRelative(
                "apps/ws/data/noten.records.json", "/apps/ws/update_all.js"))
                .isEqualTo("apps/ws/update_all.js");
    }

    @Test
    void resolveRelative_docAtProjectRoot_keepsBareName() {
        assertThat(WorkspaceFormService.resolveRelative("team.records.json", "run.js"))
                .isEqualTo("run.js");
    }

    @Test
    void stripVanceScheme_dropsVancePrefix_keepsRest() {
        assertThat(WorkspaceFormService.stripVanceScheme("vance:update_all.js"))
                .isEqualTo("update_all.js");
        assertThat(WorkspaceFormService.stripVanceScheme("vance:/apps/ws/run.js"))
                .isEqualTo("/apps/ws/run.js");
        assertThat(WorkspaceFormService.stripVanceScheme("update_all.js"))
                .isEqualTo("update_all.js");
    }
}
