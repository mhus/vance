package de.mhus.vance.brain.tools.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Static-helper tests for {@link CalendarFolderReader}. The
 * stateful {@code scan(...)} path needs a real {@code DocumentService}
 * and lives in the integration-test suite; what we test here are
 * the pure functions: lane resolution, generated-artefact detection,
 * output-path joining.
 */
class CalendarFolderReaderTest {

    // ── laneFor ────────────────────────────────────────────────────

    @Test
    void laneFor_fileInRootFolder_isDefaultLane() {
        assertThat(CalendarFolderReader.laneFor(
                "calendars", "calendars/overview.yaml"))
                .isEqualTo(CalendarFolderReader.DEFAULT_LANE);
    }

    @Test
    void laneFor_oneLevelDeep_usesFolderName() {
        assertThat(CalendarFolderReader.laneFor(
                "calendars", "calendars/design/mockups.yaml"))
                .isEqualTo("design");
        assertThat(CalendarFolderReader.laneFor(
                "calendars", "calendars/backend/api.yaml"))
                .isEqualTo("backend");
    }

    @Test
    void laneFor_deeplyNested_usesLeafFolder() {
        assertThat(CalendarFolderReader.laneFor(
                "calendars", "calendars/area-a/sub-b/file.yaml"))
                .isEqualTo("sub-b");
        assertThat(CalendarFolderReader.laneFor(
                "projects/x", "projects/x/quarter-3/sprint-2/standup.yaml"))
                .isEqualTo("sprint-2");
    }

    // ── isGeneratedArtefactPath ────────────────────────────────────

    @Test
    void isGeneratedArtefactPath_excludesGanttAndConflictsAndManifest() {
        assertThat(CalendarFolderReader.isGeneratedArtefactPath(
                "calendars/_gantt.md")).isTrue();
        assertThat(CalendarFolderReader.isGeneratedArtefactPath(
                "calendars/_conflicts.yaml")).isTrue();
        assertThat(CalendarFolderReader.isGeneratedArtefactPath(
                "calendars/_app.yaml")).isTrue();
        assertThat(CalendarFolderReader.isGeneratedArtefactPath(
                "calendars/design/_info.yaml")).isTrue();
    }

    @Test
    void isGeneratedArtefactPath_keepsRegularCalendars() {
        assertThat(CalendarFolderReader.isGeneratedArtefactPath(
                "calendars/design/mockups.yaml")).isFalse();
        assertThat(CalendarFolderReader.isGeneratedArtefactPath(
                "calendars/overview.yaml")).isFalse();
    }

    // ── resolveOutputPath ──────────────────────────────────────────

    @Test
    void resolveOutputPath_bareFilename_joinsWithSuiteFolder() {
        assertThat(CalendarFolderReader.resolveOutputPath(
                "calendars", "_gantt.md"))
                .isEqualTo("calendars/_gantt.md");
    }

    @Test
    void resolveOutputPath_alreadyPath_returnsAsIs() {
        assertThat(CalendarFolderReader.resolveOutputPath(
                "calendars", "exports/_gantt.md"))
                .isEqualTo("exports/_gantt.md");
    }

    @Test
    void resolveOutputPath_blankInput_returnsNull() {
        assertThat(CalendarFolderReader.resolveOutputPath("calendars", null))
                .isNull();
        assertThat(CalendarFolderReader.resolveOutputPath("calendars", ""))
                .isNull();
    }
}
