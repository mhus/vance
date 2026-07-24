package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Round-trip and edge-case behaviour for {@code kind: tree}. A silent
 * round-trip loss here corrupts a user document (dropped children, mangled
 * multi-line text, lost extra fields). Covers markdown/json/yaml read + write,
 * nesting, per-item + top-level extra preservation (json/yaml), and the
 * error/empty paths.
 */
class TreeCodecTest {

    private static final String MD = "text/markdown";
    private static final String JSON = "application/json";
    private static final String YAML = "application/yaml";

    // ── Markdown ─────────────────────────────────────────────────

    @Test
    void parseMarkdown_nestedBullets_buildHierarchy() {
        String body = """
                ---
                kind: tree
                ---
                - a
                  - a0
                  - a1
                - b
                """;

        TreeDocument doc = TreeCodec.parse(body, MD);

        assertThat(doc.kind()).isEqualTo("tree");
        assertThat(doc.items()).extracting(TreeItem::text).containsExactly("a", "b");
        assertThat(doc.items().get(0).children()).extracting(TreeItem::text)
                .containsExactly("a0", "a1");
    }

    @Test
    void serializeMarkdown_emitsFenceAndIndentedBullets() {
        TreeDocument doc = new TreeDocument("tree",
                List.of(new TreeItem("a", List.of(TreeItem.leaf("a0")), map()),
                        TreeItem.leaf("b")),
                map());

        String md = TreeCodec.serialize(doc, MD);

        assertThat(md).isEqualTo("""
                ---
                kind: tree
                ---
                - a
                  - a0
                - b
                """);
    }

    @Test
    void markdownRoundTrip_preservesHierarchyAndMultilineText() {
        TreeDocument original = new TreeDocument("tree",
                List.of(new TreeItem("parent\nsecond line",
                                List.of(TreeItem.leaf("child")), map()),
                        TreeItem.leaf("sibling")),
                map());

        TreeDocument back = TreeCodec.parse(TreeCodec.serialize(original, MD), MD);

        assertThat(back).isEqualTo(original);
    }

    // ── JSON ─────────────────────────────────────────────────────

    @Test
    void parseJson_canonicalForm_withMetaAndChildren() {
        String body = """
                {
                  "$meta": { "kind": "tree" },
                  "items": [
                    { "text": "a", "children": [ { "text": "a0" } ] },
                    { "text": "b" }
                  ]
                }
                """;

        TreeDocument doc = TreeCodec.parse(body, JSON);

        assertThat(doc.items()).extracting(TreeItem::text).containsExactly("a", "b");
        assertThat(doc.items().get(0).children().get(0).text()).isEqualTo("a0");
    }

    @Test
    void jsonRoundTrip_preservesPerItemAndTopLevelExtra() {
        Map<String, Object> itemExtra = map();
        itemExtra.put("color", "red");
        Map<String, Object> topExtra = map();
        topExtra.put("title", "My tree");

        TreeDocument original = new TreeDocument("tree",
                List.of(new TreeItem("a", List.of(TreeItem.leaf("a0")), itemExtra)),
                topExtra);

        TreeDocument back = TreeCodec.parse(TreeCodec.serialize(original, JSON), JSON);

        assertThat(back.kind()).isEqualTo("tree");
        assertThat(back.extra()).containsEntry("title", "My tree");
        assertThat(back.items().get(0).extra()).containsEntry("color", "red");
        assertThat(back.items().get(0).children().get(0).text()).isEqualTo("a0");
    }

    // ── YAML ─────────────────────────────────────────────────────

    @Test
    void yamlRoundTrip_preservesHierarchy() {
        TreeDocument original = new TreeDocument("tree",
                List.of(new TreeItem("a", List.of(TreeItem.leaf("a0"), TreeItem.leaf("a1")), map()),
                        TreeItem.leaf("b")),
                map());

        TreeDocument back = TreeCodec.parse(TreeCodec.serialize(original, YAML), YAML);

        assertThat(back.items()).extracting(TreeItem::text).containsExactly("a", "b");
        assertThat(back.items().get(0).children()).extracting(TreeItem::text)
                .containsExactly("a0", "a1");
    }

    // ── supports + error/empty paths ─────────────────────────────

    @Test
    void supports_recognisesMarkdownJsonYaml() {
        assertThat(TreeCodec.supports(MD)).isTrue();
        assertThat(TreeCodec.supports(JSON)).isTrue();
        assertThat(TreeCodec.supports(YAML)).isTrue();
        assertThat(TreeCodec.supports("text/plain")).isFalse();
    }

    @Test
    void parse_unsupportedMime_throws() {
        assertThatThrownBy(() -> TreeCodec.parse("- a", "text/plain"))
                .isInstanceOf(KindCodecException.class);
    }

    @Test
    void parseJson_invalidJson_throws() {
        assertThatThrownBy(() -> TreeCodec.parse("{not json", JSON))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Invalid JSON");
    }

    @Test
    void parseJson_emptyBody_returnsEmptyDoc() {
        TreeDocument doc = TreeCodec.parse("", JSON);
        assertThat(doc.items()).isEmpty();
        assertThat(doc.kind()).isEqualTo("tree");
    }

    private static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }
}
