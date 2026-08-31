package de.mhus.vance.addon.brain.gtd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/**
 * The pure path arithmetic behind {@code assignProject} — which folder an action
 * belongs in for a given project, and which one it currently sits in. A wrong
 * answer here silently relocates a document, so both halves are pinned.
 */
class GtdServiceRefileTest {

    private static final GtdConfig DEFAULTS = GtdConfig.defaults();

    @Test
    void projectDir_named_isSlugUnderProjectsDir() {
        assertThat(GtdService.projectDir(DEFAULTS, "Website Relaunch"))
                .isEqualTo("projects/website-relaunch");
    }

    @Test
    void projectDir_blank_fallsBackToActionsDir() {
        assertThat(GtdService.projectDir(DEFAULTS, null)).isEqualTo("actions");
        assertThat(GtdService.projectDir(DEFAULTS, "   ")).isEqualTo("actions");
    }

    @Test
    void projectDir_honoursConfiguredDirectoryNames() {
        GtdConfig config = new GtdConfig(null, null, "in", "next", "areas", java.util.List.of(), new java.util.EnumMap<>(GtdBucket.class));
        assertThat(GtdService.projectDir(config, "Q3")).isEqualTo("areas/q3");
        assertThat(GtdService.projectDir(config, "")).isEqualTo("next");
    }

    @Test
    void projectDir_unslugifiableName_isRejected() {
        assertThatThrownBy(() -> GtdService.projectDir(DEFAULTS, "///"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no usable folder name");
    }

    @Test
    void currentDir_readsTheFolderRelativeToTheGtdRoot() {
        assertThat(GtdService.currentDir("gtd/life", "gtd/life/inbox/call-tax.md"))
                .isEqualTo("inbox");
        assertThat(GtdService.currentDir("gtd/life", "gtd/life/projects/relaunch/brief.md"))
                .isEqualTo("projects/relaunch");
        assertThat(GtdService.currentDir("gtd/life", "gtd/life/_today.md")).isEmpty();
    }

    @Test
    void currentDir_outsideTheRoot_isRejected() {
        assertThatThrownBy(() -> GtdService.currentDir("gtd/life", "gtd/other/inbox/x.md"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not inside");
    }

    @Test
    void refilingIntoTheSameProject_isDetectedAsNoOp() {
        String path = "gtd/life/projects/relaunch/brief.md";
        assertThat(GtdService.projectDir(DEFAULTS, "Relaunch"))
                .isEqualTo(GtdService.currentDir("gtd/life", path));
    }
}
