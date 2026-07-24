package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link KindHeaderCodec} — the shared {@code $meta}
 * wrap/unwrap used by every structured kind codec (card/records/list/
 * tree/graph/sheet/data) for JSON and YAML. Pins the subtle rules a
 * refactor could silently break: scalar-meta-over-body merge, non-scalar
 * meta drop, mapping-root enforcement, duplicate-key rejection, and the
 * scalar-filtered `headerExtra` that cannot override `kind`.
 */
class KindHeaderCodecTest {

    @Test
    void unwrapJsonMeta_mergesScalarMetaOverBody() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("kind", "wrong"); // body key
        obj.put("title", "T");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", "card"); // meta wins on collision
        obj.put("$meta", meta);

        Map<String, Object> merged = KindHeaderCodec.unwrapJsonMeta(obj);

        assertThat(merged).containsEntry("kind", "card").containsEntry("title", "T");
        assertThat(merged).doesNotContainKey("$meta");
    }

    @Test
    void unwrapJsonMeta_dropsNonScalarMetaValues() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", "card");
        meta.put("nested", Map.of("a", 1)); // non-scalar → dropped
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("$meta", meta);

        Map<String, Object> merged = KindHeaderCodec.unwrapJsonMeta(obj);

        assertThat(merged).containsEntry("kind", "card").doesNotContainKey("nested");
    }

    @Test
    void unwrapJsonMeta_withoutMetaMap_passesThroughUnchanged() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("title", "T");

        assertThat(KindHeaderCodec.unwrapJsonMeta(obj)).containsEntry("title", "T");
    }

    @Test
    void wrapJsonMeta_emitsMetaWithKind_besideBodyKeys() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "T");

        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta("card", body);

        assertThat(wrapped).containsKey("$meta").containsEntry("title", "T");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) wrapped.get("$meta");
        assertThat(meta).containsEntry("kind", "card");
    }

    @Test
    void parseYamlBody_unwrapsMetaAndFlattens() {
        Map<String, Object> top = KindHeaderCodec.parseYamlBody(
                "$meta:\n  kind: card\ntitle: T\n");

        assertThat(top).containsEntry("kind", "card").containsEntry("title", "T");
    }

    @Test
    void parseYamlBody_rejectsSequenceRoot() {
        assertThatThrownBy(() -> KindHeaderCodec.parseYamlBody("- a\n- b\n"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("mapping");
    }

    @Test
    void parseYamlBody_rejectsScalarRoot() {
        assertThatThrownBy(() -> KindHeaderCodec.parseYamlBody("just a string"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("mapping");
    }

    @Test
    void parseYamlBody_rejectsDuplicateKeys() {
        assertThatThrownBy(() -> KindHeaderCodec.parseYamlBody("a: 1\na: 2\n"))
                .isInstanceOf(KindCodecException.class);
    }

    @Test
    void parseYamlBody_emptyStream_yieldsEmptyMap() {
        assertThat(KindHeaderCodec.parseYamlBody("")).isEmpty();
    }

    @Test
    void dumpYamlBody_emitsMetaFirst_withKind() {
        String yaml = KindHeaderCodec.dumpYamlBody(
                "card", Map.of("title", "T"));

        assertThat(yaml).contains("$meta:").contains("kind: card").contains("title: T");
        assertThat(yaml.indexOf("$meta")).isLessThan(yaml.indexOf("title"));
    }

    @Test
    void dumpYamlBody_headerExtra_scalarFiltered_andCannotOverrideKind() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("kind", "attacker");        // must be ignored
        extra.put("schema", "s1");            // scalar → kept
        extra.put("nested", Map.of("x", 1));  // non-scalar → dropped

        String yaml = KindHeaderCodec.dumpYamlBody("card", Map.of("title", "T"), extra);
        Map<String, Object> back = KindHeaderCodec.parseYamlBody(yaml);

        assertThat(back).containsEntry("kind", "card").containsEntry("schema", "s1");
        assertThat(back).doesNotContainKey("nested");
    }

    @Test
    void jsonAndYaml_shapeSymmetry_forSameBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "T");

        Map<String, Object> fromJson =
                KindHeaderCodec.unwrapJsonMeta(KindHeaderCodec.wrapJsonMeta("card", body));
        Map<String, Object> fromYaml =
                KindHeaderCodec.parseYamlBody(KindHeaderCodec.dumpYamlBody("card", body));

        assertThat(fromJson).containsEntry("kind", "card").containsEntry("title", "T");
        assertThat(fromYaml).containsEntry("kind", "card").containsEntry("title", "T");
    }
}
