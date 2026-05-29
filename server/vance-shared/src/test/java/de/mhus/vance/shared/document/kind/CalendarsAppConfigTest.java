package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for the typed view onto the {@code config.calendar} block
 * of an {@link ApplicationDocument} with {@code app: calendar}.
 */
class CalendarsAppConfigTest {

    private static final String YAML_MIME = "application/yaml";

    private static CalendarsAppConfig fromYaml(String body) {
        ApplicationDocument doc = ApplicationCodec.parse(body, YAML_MIME);
        return CalendarsAppConfig.from(doc);
    }

    // ── happy path ─────────────────────────────────────────────────

    @Test
    void from_appCalendar_parsesAllSections() {
        String body = """
                $meta:
                  kind: application
                  app: calendar
                calendar:
                  window:
                    from: "2026-06-01"
                    until: "2026-09-30"
                  lanes:
                    design: { title: "Design", color: blue, order: 1 }
                    backend: { title: "Backend", color: green, order: 2 }
                  gantt:
                    outputPath: custom-gantt.md
                    includeRecurring: true
                    tagFilter: [release]
                    criticalTags: [block]
                  conflicts:
                    outputPath: custom-conflicts.yaml
                    ignoreWithinTags: [private, holiday]
                """;
        CalendarsAppConfig cfg = fromYaml(body);

        assertThat(cfg.window().from()).isEqualTo("2026-06-01");
        assertThat(cfg.window().until()).isEqualTo("2026-09-30");

        assertThat(cfg.lanes()).hasSize(2);
        CalendarsAppConfig.Lane design = cfg.lanes().get("design");
        assertThat(design.title()).isEqualTo("Design");
        assertThat(design.color()).isEqualTo("blue");
        assertThat(design.order()).isEqualTo(1);

        assertThat(cfg.gantt().outputPath()).isEqualTo("custom-gantt.md");
        assertThat(cfg.gantt().includeRecurring()).isTrue();
        assertThat(cfg.gantt().tagFilter()).containsExactly("release");
        assertThat(cfg.gantt().criticalTags()).containsExactly("block");

        assertThat(cfg.conflicts().outputPath()).isEqualTo("custom-conflicts.yaml");
        assertThat(cfg.conflicts().ignoreWithinTags()).containsExactly("private", "holiday");
    }

    @Test
    void from_appCalendar_missingCalendarBlock_returnsDefaults() {
        // Manifest with kind+app set but no nested config payload.
        // Tools should still be runnable on auto-detected lanes/events.
        String body = """
                $meta:
                  kind: application
                  app: calendar
                title: Empty manifest
                """;
        CalendarsAppConfig cfg = fromYaml(body);

        assertThat(cfg.lanes()).isEmpty();
        assertThat(cfg.gantt().outputPath()).isEqualTo("_gantt.md");
        assertThat(cfg.gantt().includeRecurring()).isFalse();
        assertThat(cfg.gantt().criticalTags()).containsExactly("milestone", "critical");
        assertThat(cfg.gantt().doneTags()).containsExactly("done", "erledigt");
        assertThat(cfg.conflicts().outputPath()).isEqualTo("_conflicts.yaml");
    }

    @Test
    void from_appNotCalendar_throws() {
        String body = """
                $meta:
                  kind: application
                  app: kanban
                """;
        ApplicationDocument doc = ApplicationCodec.parse(body, YAML_MIME);
        assertThatThrownBy(() -> CalendarsAppConfig.from(doc))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("app='kanban'");
    }

    // ── lane resolution ───────────────────────────────────────────

    @Test
    void laneOrDefault_unknownLane_returnsAutoDefault() {
        CalendarsAppConfig cfg = CalendarsAppConfig.from(new LinkedHashMap<>());
        CalendarsAppConfig.Lane unknown = cfg.laneOrDefault("frontend");
        assertThat(unknown.name()).isEqualTo("frontend");
        assertThat(unknown.title()).isEqualTo("frontend");
        assertThat(unknown.color()).isNull();
        assertThat(unknown.order()).isNull();
    }

    @Test
    void laneOrDefault_titleFallsBackToName() {
        Map<String, Object> lane = new LinkedHashMap<>();
        lane.put("color", "purple");
        Map<String, Object> lanes = Map.of("frontend", lane);
        CalendarsAppConfig cfg = CalendarsAppConfig.from(Map.of("lanes", lanes));
        CalendarsAppConfig.Lane resolved = cfg.lanes().get("frontend");
        assertThat(resolved.title()).isEqualTo("frontend");
        assertThat(resolved.color()).isEqualTo("purple");
    }

    // ── permissive parsing ────────────────────────────────────────

    @Test
    void readLanes_nonMapEntry_yieldsAutoDefault() {
        // Lane value is e.g. a string instead of a map — common LLM
        // typo. Auto-default rather than throw.
        Map<String, Object> calBlock = Map.of(
                "lanes", Map.of("design", "broken"));
        CalendarsAppConfig cfg = CalendarsAppConfig.from(calBlock);
        CalendarsAppConfig.Lane lane = cfg.lanes().get("design");
        assertThat(lane).isNotNull();
        assertThat(lane.title()).isEqualTo("design");
    }

    @Test
    void readGantt_nonMap_returnsAllDefaults() {
        Map<String, Object> calBlock = Map.of("gantt", "ignored");
        CalendarsAppConfig cfg = CalendarsAppConfig.from(calBlock);
        assertThat(cfg.gantt().outputPath()).isEqualTo("_gantt.md");
    }

    @Test
    void readConflicts_nonMap_returnsAllDefaults() {
        Map<String, Object> calBlock = Map.of("conflicts", List.of("nope"));
        CalendarsAppConfig cfg = CalendarsAppConfig.from(calBlock);
        assertThat(cfg.conflicts().outputPath()).isEqualTo("_conflicts.yaml");
        assertThat(cfg.conflicts().ignoreWithinTags()).isEmpty();
    }
}
