package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Streaming-path coverage for the three {@link HeaderStrategy} implementations.
 * Each test feeds the parser an oversized body (well past any reasonable buffer
 * limit) to prove no implementation silently materialises the stream.
 */
class HeaderStrategyStreamingTest {

    private final MarkdownHeaderStrategy markdown = new MarkdownHeaderStrategy();
    private final JsonHeaderStrategy json = new JsonHeaderStrategy();
    private final YamlHeaderStrategy yaml = new YamlHeaderStrategy();

    @Test
    void markdown_streaming_parsesFrontMatter() throws IOException {
        String body = """
                ---
                kind: list
                schema: requirement
                ---
                # Heading
                body content...
                """;
        Optional<DocumentHeader> header = markdown.parse(stream(body));
        assertThat(header).isPresent();
        assertThat(header.get().getKind()).isEqualTo("list");
        assertThat(header.get().getValues())
                .containsEntry("kind", "list")
                .containsEntry("schema", "requirement");
    }

    @Test
    void markdown_streaming_largeBodyAfterFrontMatter_doesNotMaterialise() throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("---\nkind: list\n---\n");
        // Append ~5 MB of body content past the front matter. The streaming
        // parser must stop reading after the closing fence and never load this.
        for (int i = 0; i < 5 * 1024; i++) {
            body.append("line ").append(i).append(": ").append("x".repeat(1000)).append('\n');
        }
        Optional<DocumentHeader> header = markdown.parse(stream(body.toString()));
        assertThat(header).isPresent();
        assertThat(header.get().getKind()).isEqualTo("list");
    }

    @Test
    void markdown_streaming_noFrontMatter_returnsEmpty() throws IOException {
        String body = "# Just a heading\nNo front matter here.\n";
        assertThat(markdown.parse(stream(body))).isEmpty();
    }

    @Test
    void json_streaming_extractsMetaFromAnywhereInTopLevel() throws IOException {
        String body = """
                {
                  "data": "first",
                  "$meta": { "kind": "list", "schema": "requirement" },
                  "trailing": 42
                }
                """;
        Optional<DocumentHeader> header = json.parse(stream(body));
        assertThat(header).isPresent();
        assertThat(header.get().getKind()).isEqualTo("list");
        assertThat(header.get().getValues())
                .containsEntry("kind", "list")
                .containsEntry("schema", "requirement");
    }

    @Test
    void json_streaming_largeBodyAroundMeta_doesNotMaterialise() throws IOException {
        // 5 MB of items array followed by $meta at the end — the streaming
        // parser must walk through but never buffer it all.
        StringBuilder body = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 50_000; i++) {
            if (i > 0) body.append(',');
            body.append('"').append("x".repeat(100)).append('"');
        }
        body.append("], \"$meta\": {\"kind\":\"records\"}}");

        Optional<DocumentHeader> header = json.parse(stream(body.toString()));
        assertThat(header).isPresent();
        assertThat(header.get().getKind()).isEqualTo("records");
    }

    @Test
    void json_streaming_metaAsArray_returnsEmpty() throws IOException {
        String body = "{\"$meta\": [1, 2, 3], \"data\": \"x\"}";
        assertThat(json.parse(stream(body))).isEmpty();
    }

    @Test
    void json_streaming_rootArray_returnsEmpty() throws IOException {
        String body = "[1, 2, 3]";
        assertThat(json.parse(stream(body))).isEmpty();
    }

    @Test
    void json_streaming_malformed_returnsEmpty() throws IOException {
        String body = "{\"$meta\": {\"kind\": \"list\""; // truncated
        assertThat(json.parse(stream(body))).isEmpty();
    }

    @Test
    void json_streaming_metaSkipsNestedNonScalars() throws IOException {
        String body = """
                {
                  "$meta": {
                    "kind": "list",
                    "nested_obj": {"a": 1},
                    "nested_arr": [1, 2],
                    "schema": "requirement"
                  }
                }
                """;
        Optional<DocumentHeader> header = json.parse(stream(body));
        assertThat(header).isPresent();
        assertThat(header.get().getValues())
                .containsKeys("kind", "schema")
                .doesNotContainKeys("nested_obj", "nested_arr");
    }

    @Test
    void yaml_streaming_extractsMeta() throws IOException {
        String body = """
                $meta:
                  kind: list
                  schema: requirement
                items:
                  - one
                  - two
                """;
        Optional<DocumentHeader> header = yaml.parse(stream(body));
        assertThat(header).isPresent();
        assertThat(header.get().getKind()).isEqualTo("list");
        assertThat(header.get().getValues())
                .containsEntry("kind", "list")
                .containsEntry("schema", "requirement");
    }

    @Test
    void yaml_streaming_largeBodyAroundMeta_doesNotMaterialise() throws IOException {
        StringBuilder body = new StringBuilder("$meta:\n  kind: records\n");
        body.append("items:\n");
        for (int i = 0; i < 50_000; i++) {
            body.append("  - ").append("x".repeat(100)).append('\n');
        }

        Optional<DocumentHeader> header = yaml.parse(stream(body.toString()));
        assertThat(header).isPresent();
        assertThat(header.get().getKind()).isEqualTo("records");
    }

    @Test
    void yaml_streaming_metaAfterOtherKeys_stillFound() throws IOException {
        String body = """
                data: first
                items:
                  - a
                  - b
                $meta:
                  kind: list
                """;
        Optional<DocumentHeader> header = yaml.parse(stream(body));
        assertThat(header).isPresent();
        assertThat(header.get().getKind()).isEqualTo("list");
    }

    @Test
    void yaml_streaming_rootScalar_returnsEmpty() throws IOException {
        String body = "just a string value\n";
        assertThat(yaml.parse(stream(body))).isEmpty();
    }

    @Test
    void yaml_streaming_noKind_returnsEmpty() throws IOException {
        String body = """
                $meta:
                  schema: requirement
                """;
        // YamlHeaderStrategy requires `kind` to be present — mirrors string parser.
        assertThat(yaml.parse(stream(body))).isEmpty();
    }

    @Test
    void yaml_streaming_metaSkipsNestedNonScalars() throws IOException {
        String body = """
                $meta:
                  kind: list
                  nested:
                    a: 1
                  arr:
                    - 1
                    - 2
                  schema: requirement
                """;
        Optional<DocumentHeader> header = yaml.parse(stream(body));
        assertThat(header).isPresent();
        assertThat(header.get().getValues())
                .containsKeys("kind", "schema")
                .doesNotContainKeys("nested", "arr");
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
