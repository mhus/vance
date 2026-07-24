package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Round-trip and edge-case behaviour for {@code kind: records} — a
 * fixed-schema table. A silent round-trip loss corrupts tabular user data
 * (dropped rows, shifted columns, lost schema). Covers the two markdown
 * input shapes (front-matter schema + bullet rows, and a GFM table whose
 * header supplies the schema), json/yaml round-trip, and the required-schema
 * / error / empty paths.
 */
class RecordsCodecTest {

    private static final String MD = "text/markdown";
    private static final String JSON = "application/json";
    private static final String YAML = "application/yaml";
    private static final List<String> SCHEMA = List.of("name", "age");

    // ── Markdown: two input shapes ───────────────────────────────

    @Test
    void parseMarkdown_frontMatterSchema_withBulletRows() {
        String body = """
                ---
                kind: records
                schema: name, age
                ---
                - Alice, 30
                - Bob, 25
                """;

        RecordsDocument doc = RecordsCodec.parse(body, MD);

        assertThat(doc.schema()).containsExactly("name", "age");
        assertThat(doc.items()).hasSize(2);
        assertThat(doc.items().get(0).values()).containsEntry("name", "Alice").containsEntry("age", "30");
        assertThat(doc.items().get(1).values()).containsEntry("name", "Bob").containsEntry("age", "25");
    }

    @Test
    void parseMarkdown_gfmTable_headerSuppliesSchema() {
        String body = """
                ---
                kind: records
                ---
                | name | age |
                |------|-----|
                | Alice | 30 |
                | Bob | 25 |
                """;

        RecordsDocument doc = RecordsCodec.parse(body, MD);

        assertThat(doc.schema()).containsExactly("name", "age");
        assertThat(doc.items()).hasSize(2);
        assertThat(doc.items().get(0).values()).containsEntry("name", "Alice").containsEntry("age", "30");
    }

    @Test
    void parseMarkdown_missingSchema_throws() {
        String body = """
                ---
                kind: records
                ---
                - Alice, 30
                """;

        assertThatThrownBy(() -> RecordsCodec.parse(body, MD))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void markdownRoundTrip_preservesSchemaAndValues() {
        RecordsDocument original = new RecordsDocument("records", SCHEMA,
                List.of(rec("Alice", "30"), rec("Bob", "25")), map());

        RecordsDocument back = RecordsCodec.parse(RecordsCodec.serialize(original, MD), MD);

        assertThat(back.schema()).isEqualTo(SCHEMA);
        assertThat(back.items()).extracting(i -> i.values().get("name")).containsExactly("Alice", "Bob");
        assertThat(back.items()).extracting(i -> i.values().get("age")).containsExactly("30", "25");
    }

    @Test
    void serializeMarkdown_withoutSchema_throws() {
        RecordsDocument doc = new RecordsDocument("records", List.of(), List.of(), map());
        assertThatThrownBy(() -> RecordsCodec.serialize(doc, MD))
                .isInstanceOf(KindCodecException.class);
    }

    // ── JSON ─────────────────────────────────────────────────────

    @Test
    void parseJson_canonicalForm() {
        String body = """
                {
                  "$meta": { "kind": "records" },
                  "schema": ["name", "age"],
                  "items": [
                    { "name": "Alice", "age": "30" },
                    { "name": "Bob", "age": "25" }
                  ]
                }
                """;

        RecordsDocument doc = RecordsCodec.parse(body, JSON);

        assertThat(doc.schema()).containsExactly("name", "age");
        assertThat(doc.items()).hasSize(2);
        assertThat(doc.items().get(1).values()).containsEntry("name", "Bob");
    }

    @Test
    void jsonRoundTrip_preservesSchemaValuesAndTopLevelExtra() {
        Map<String, Object> top = map();
        top.put("title", "People");
        RecordsDocument original = new RecordsDocument("records", SCHEMA,
                List.of(rec("Alice", "30")), top);

        RecordsDocument back = RecordsCodec.parse(RecordsCodec.serialize(original, JSON), JSON);

        assertThat(back.schema()).isEqualTo(SCHEMA);
        assertThat(back.items().get(0).values()).containsEntry("name", "Alice").containsEntry("age", "30");
        assertThat(back.extra()).containsEntry("title", "People");
    }

    // ── YAML ─────────────────────────────────────────────────────

    @Test
    void yamlRoundTrip_preservesSchemaAndValues() {
        RecordsDocument original = new RecordsDocument("records", SCHEMA,
                List.of(rec("Alice", "30"), rec("Bob", "25")), map());

        RecordsDocument back = RecordsCodec.parse(RecordsCodec.serialize(original, YAML), YAML);

        assertThat(back.schema()).isEqualTo(SCHEMA);
        assertThat(back.items()).extracting(i -> i.values().get("name")).containsExactly("Alice", "Bob");
    }

    // ── supports + error/empty ───────────────────────────────────

    @Test
    void supports_recognisesMarkdownJsonYaml() {
        assertThat(RecordsCodec.supports(MD)).isTrue();
        assertThat(RecordsCodec.supports(JSON)).isTrue();
        assertThat(RecordsCodec.supports(YAML)).isTrue();
        assertThat(RecordsCodec.supports("text/plain")).isFalse();
    }

    @Test
    void parse_unsupportedMime_throws() {
        assertThatThrownBy(() -> RecordsCodec.parse("- a", "text/plain"))
                .isInstanceOf(KindCodecException.class);
    }

    @Test
    void parseJson_invalidJson_throws() {
        assertThatThrownBy(() -> RecordsCodec.parse("{not json", JSON))
                .isInstanceOf(KindCodecException.class);
    }

    // ── helpers ──────────────────────────────────────────────────

    private static RecordsItem rec(String... vals) {
        Map<String, String> v = new LinkedHashMap<>();
        for (int i = 0; i < SCHEMA.size(); i++) {
            v.put(SCHEMA.get(i), i < vals.length ? vals[i] : "");
        }
        return new RecordsItem(v, new LinkedHashMap<>(), new ArrayList<>());
    }

    private static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }
}
