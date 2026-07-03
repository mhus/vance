package de.mhus.vance.addon.brain.workbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the deterministic, Mongo-free helpers of
 * {@link WorkbookFormService}: fence saveScript path resolution + the
 * extension-aware serialisation (a {@code .json} records doc must contain real
 * JSON so a saveScript's {@code JSON.parse} works, not YAML).
 */
class WorkbookFormServiceTest {

    @Test
    void serialize_jsonExtension_writesRealJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("$meta", Map.of("kind", "records"));
        doc.put("schema", List.of("fach", "note"));
        doc.put("items", List.of(Map.of("fach", "m", "note", "1")));

        String out = WorkbookFormService.serialize("apps/ws/data/noten.records.json", doc);

        // Real JSON — starts with '{', not the YAML '$meta:' the bug produced.
        assertThat(out.stripLeading()).startsWith("{");
        assertThat(out).contains("\"$meta\"").contains("\"kind\" : \"records\"");
        assertThat(out).doesNotContain("$meta:\n");
    }

    @Test
    void serialize_yamlExtension_writesYaml() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("$meta", Map.of("kind", "records"));

        String out = WorkbookFormService.serialize("apps/ws/data/noten.yaml", doc);

        assertThat(out).contains("$meta:");
        assertThat(out.stripLeading()).doesNotStartWith("{");
    }

    @Test
    void resolveRelative_bareName_isResolvedAgainstDocFolder() {
        assertThat(WorkbookFormService.resolveRelative(
                "apps/ws/data/noten.records.json", "update_all.js"))
                .isEqualTo("apps/ws/data/update_all.js");
    }

    @Test
    void resolveRelative_leadingSlash_isProjectAbsolute() {
        assertThat(WorkbookFormService.resolveRelative(
                "apps/ws/data/noten.records.json", "/apps/ws/update_all.js"))
                .isEqualTo("apps/ws/update_all.js");
    }

    @Test
    void resolveRelative_docAtProjectRoot_keepsBareName() {
        assertThat(WorkbookFormService.resolveRelative("team.records.json", "run.js"))
                .isEqualTo("run.js");
    }

    @Test
    void stripVanceScheme_dropsVancePrefix_keepsRest() {
        assertThat(WorkbookFormService.stripVanceScheme("vance:update_all.js"))
                .isEqualTo("update_all.js");
        assertThat(WorkbookFormService.stripVanceScheme("vance:/apps/ws/run.js"))
                .isEqualTo("/apps/ws/run.js");
        assertThat(WorkbookFormService.stripVanceScheme("update_all.js"))
                .isEqualTo("update_all.js");
    }
}
