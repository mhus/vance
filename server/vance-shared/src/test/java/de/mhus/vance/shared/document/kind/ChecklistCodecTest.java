package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trip and edge-case behaviour for {@code kind: checklist}.
 *
 * <p>Mirrors the test surface of {@link CalendarCodecTest} —
 * happy-paths per format, lenient-read tolerance, custom-char
 * preservation, priority parsing.
 */
class ChecklistCodecTest {

    private static final String MD_MIME = "text/markdown";
    private static final String JSON_MIME = "application/json";
    private static final String YAML_MIME = "application/yaml";

    // ── Markdown: read ───────────────────────────────────────────

    @Test
    void parseMarkdown_canonicalForm_allStatusChars() {
        String body = """
                ---
                kind: checklist
                ---
                - [ ] open task
                - [x] done task
                - [~] in progress task
                - [/] in review task
                - [!] blocked task
                - [?] needs info task
                - [-] deferred task
                - [>] delegated task
                - [<] waiting task
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, MD_MIME);

        assertThat(doc.kind()).isEqualTo("checklist");
        assertThat(doc.items()).extracting(ChecklistItem::status).containsExactly(
                ChecklistStatus.OPEN,
                ChecklistStatus.DONE,
                ChecklistStatus.IN_PROGRESS,
                ChecklistStatus.REVIEW,
                ChecklistStatus.BLOCKED,
                ChecklistStatus.NEEDS_INFO,
                ChecklistStatus.DEFERRED,
                ChecklistStatus.DELEGATED,
                ChecklistStatus.WAITING);
        assertThat(doc.items()).extracting(ChecklistItem::text).containsExactly(
                "open task", "done task", "in progress task",
                "in review task", "blocked task", "needs info task",
                "deferred task", "delegated task", "waiting task");
    }

    @Test
    void parseMarkdown_plainBullet_defaultsToOpen() {
        String body = """
                ---
                kind: checklist
                ---
                - plain item without checkbox
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, MD_MIME);

        assertThat(doc.items()).hasSize(1);
        assertThat(doc.items().get(0).text()).isEqualTo("plain item without checkbox");
        assertThat(doc.items().get(0).status()).isEqualTo(ChecklistStatus.OPEN);
    }

    @Test
    void parseMarkdown_customStatusChar_preservedInExtra() {
        String body = """
                ---
                kind: checklist
                ---
                - [Z] item with custom char
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, MD_MIME);

        assertThat(doc.items()).hasSize(1);
        ChecklistItem item = doc.items().get(0);
        assertThat(item.status()).isEqualTo(ChecklistStatus.OPEN);
        assertThat(item.extra()).containsEntry(ChecklistCodec.STATUS_CHAR_EXTRA_KEY, "Z");
    }

    @Test
    void parseMarkdown_trailingPrioTag_extractedFromText() {
        String body = """
                ---
                kind: checklist
                ---
                - [ ] high prio task #prio:high
                - [x] low prio task #prio:low
                - [!] no prio task
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, MD_MIME);

        assertThat(doc.items().get(0).text()).isEqualTo("high prio task");
        assertThat(doc.items().get(0).priority()).isEqualTo(ChecklistPriority.HIGH);
        assertThat(doc.items().get(1).text()).isEqualTo("low prio task");
        assertThat(doc.items().get(1).priority()).isEqualTo(ChecklistPriority.LOW);
        assertThat(doc.items().get(2).priority()).isNull();
    }

    @Test
    void parseMarkdown_continuationIndent_appendsToPreviousText() {
        String body = """
                ---
                kind: checklist
                ---
                - [ ] first item
                  second line of first
                - [x] second item
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, MD_MIME);

        assertThat(doc.items()).hasSize(2);
        assertThat(doc.items().get(0).text()).isEqualTo("first item\nsecond line of first");
        assertThat(doc.items().get(1).text()).isEqualTo("second item");
    }

    @Test
    void parseMarkdown_uppercaseX_acceptedAsDone() {
        String body = """
                ---
                kind: checklist
                ---
                - [X] uppercase done
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, MD_MIME);

        assertThat(doc.items().get(0).status()).isEqualTo(ChecklistStatus.DONE);
    }

    // ── Markdown: write ──────────────────────────────────────────

    @Test
    void serializeMarkdown_writesCanonicalForm() {
        ChecklistDocument doc = new ChecklistDocument(
                "checklist",
                List.of(
                        ChecklistItem.of("open task"),
                        ChecklistItem.of("done task", ChecklistStatus.DONE),
                        new ChecklistItem("high prio", ChecklistStatus.IN_PROGRESS,
                                ChecklistPriority.HIGH, new LinkedHashMap<>())),
                new LinkedHashMap<>());

        String md = ChecklistCodec.serialize(doc, MD_MIME);

        assertThat(md).isEqualTo("""
                ---
                kind: checklist
                ---
                - [ ] open task
                - [x] done task
                - [~] high prio #prio:high
                """);
    }

    @Test
    void serializeMarkdown_customStatusChar_preferredOverStatus() {
        LinkedHashMap<String, Object> extra = new LinkedHashMap<>();
        extra.put(ChecklistCodec.STATUS_CHAR_EXTRA_KEY, "Z");
        ChecklistDocument doc = new ChecklistDocument(
                "checklist",
                List.of(new ChecklistItem("custom", ChecklistStatus.OPEN, null, extra)),
                new LinkedHashMap<>());

        String md = ChecklistCodec.serialize(doc, MD_MIME);

        assertThat(md).contains("- [Z] custom");
    }

    @Test
    void markdownRoundTrip_preservesAllStatusesAndPriority() {
        String original = """
                ---
                kind: checklist
                ---
                - [ ] open
                - [x] done #prio:high
                - [~] in progress
                - [!] blocked #prio:low
                - [>] delegated
                """;

        ChecklistDocument doc = ChecklistCodec.parse(original, MD_MIME);
        String reSerialized = ChecklistCodec.serialize(doc, MD_MIME);

        assertThat(reSerialized).isEqualTo(original);
    }

    // ── JSON: read ───────────────────────────────────────────────

    @Test
    void parseJson_canonicalForm_withStatusAndPriority() {
        String body = """
                {
                  "$meta": { "kind": "checklist" },
                  "items": [
                    { "text": "open item" },
                    { "text": "done item", "status": "done" },
                    { "text": "blocked", "status": "blocked", "priority": "high" }
                  ]
                }
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, JSON_MIME);

        assertThat(doc.kind()).isEqualTo("checklist");
        assertThat(doc.items()).hasSize(3);
        assertThat(doc.items().get(0).status()).isEqualTo(ChecklistStatus.OPEN);
        assertThat(doc.items().get(1).status()).isEqualTo(ChecklistStatus.DONE);
        assertThat(doc.items().get(2).status()).isEqualTo(ChecklistStatus.BLOCKED);
        assertThat(doc.items().get(2).priority()).isEqualTo(ChecklistPriority.HIGH);
    }

    @Test
    void parseJson_stringArrayShorthand_promotedToOpenItems() {
        String body = """
                {
                  "$meta": { "kind": "checklist" },
                  "items": ["first", "second"]
                }
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, JSON_MIME);

        assertThat(doc.items()).hasSize(2);
        assertThat(doc.items()).extracting(ChecklistItem::text)
                .containsExactly("first", "second");
        assertThat(doc.items()).allMatch(it -> it.status() == ChecklistStatus.OPEN);
    }

    @Test
    void parseJson_missingTextField_silentlyDropped() {
        String body = """
                {
                  "$meta": { "kind": "checklist" },
                  "items": [
                    { "text": "kept" },
                    { "status": "done" },
                    { "text": "also kept", "status": "blocked" }
                  ]
                }
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, JSON_MIME);

        assertThat(doc.items()).hasSize(2);
        assertThat(doc.items()).extracting(ChecklistItem::text)
                .containsExactly("kept", "also kept");
    }

    @Test
    void parseJson_unknownStatus_fallsBackToOpen() {
        String body = """
                {
                  "$meta": { "kind": "checklist" },
                  "items": [
                    { "text": "weird", "status": "frobnicated" }
                  ]
                }
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, JSON_MIME);

        assertThat(doc.items().get(0).status()).isEqualTo(ChecklistStatus.OPEN);
    }

    @Test
    void parseJson_unknownTopLevelKeys_preservedInExtra() {
        String body = """
                {
                  "$meta": { "kind": "checklist" },
                  "items": [{ "text": "x" }],
                  "title": "My checklist",
                  "owner": "alice"
                }
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, JSON_MIME);

        assertThat(doc.extra()).containsEntry("title", "My checklist");
        assertThat(doc.extra()).containsEntry("owner", "alice");
    }

    // ── JSON: write ──────────────────────────────────────────────

    @Test
    void serializeJson_omitsOpenStatus_keepsExplicitOthers() {
        ChecklistDocument doc = new ChecklistDocument(
                "checklist",
                List.of(
                        ChecklistItem.of("plain-text"),
                        ChecklistItem.of("done-text", ChecklistStatus.DONE)),
                new LinkedHashMap<>());

        String out = ChecklistCodec.serialize(doc, JSON_MIME);

        assertThat(out).contains("\"text\" : \"plain-text\"");
        // Open is the default; the status key is omitted entirely when
        // the value would be "open" to keep the wire form compact.
        assertThat(out).doesNotContain("\"status\" : \"open\"");
        assertThat(out).contains("\"status\" : \"done\"");
    }

    @Test
    void jsonRoundTrip_preservesEverything() {
        String original = """
                {
                  "$meta": { "kind": "checklist" },
                  "items": [
                    { "text": "open" },
                    { "text": "done", "status": "done", "priority": "high" },
                    { "text": "blocked", "status": "blocked" }
                  ]
                }
                """;

        ChecklistDocument first = ChecklistCodec.parse(original, JSON_MIME);
        String reSerialized = ChecklistCodec.serialize(first, JSON_MIME);
        ChecklistDocument second = ChecklistCodec.parse(reSerialized, JSON_MIME);

        assertThat(second.items()).hasSize(3);
        assertThat(second.items()).extracting(ChecklistItem::status).containsExactly(
                ChecklistStatus.OPEN, ChecklistStatus.DONE, ChecklistStatus.BLOCKED);
        assertThat(second.items().get(1).priority()).isEqualTo(ChecklistPriority.HIGH);
    }

    // ── YAML ─────────────────────────────────────────────────────

    @Test
    void parseYaml_canonicalForm() {
        String body = """
                $meta:
                  kind: checklist
                items:
                  - text: open task
                  - text: done task
                    status: done
                  - text: blocked high
                    status: blocked
                    priority: high
                """;

        ChecklistDocument doc = ChecklistCodec.parse(body, YAML_MIME);

        assertThat(doc.items()).hasSize(3);
        assertThat(doc.items().get(1).status()).isEqualTo(ChecklistStatus.DONE);
        assertThat(doc.items().get(2).priority()).isEqualTo(ChecklistPriority.HIGH);
    }

    @Test
    void yamlRoundTrip_preservesEverything() {
        String original = """
                $meta:
                  kind: checklist
                items:
                - text: open
                - text: done
                  status: done
                  priority: high
                """;

        ChecklistDocument first = ChecklistCodec.parse(original, YAML_MIME);
        String reSerialized = ChecklistCodec.serialize(first, YAML_MIME);
        ChecklistDocument second = ChecklistCodec.parse(reSerialized, YAML_MIME);

        assertThat(second.items()).hasSize(2);
        assertThat(second.items().get(0).status()).isEqualTo(ChecklistStatus.OPEN);
        assertThat(second.items().get(1).status()).isEqualTo(ChecklistStatus.DONE);
        assertThat(second.items().get(1).priority()).isEqualTo(ChecklistPriority.HIGH);
    }

    // ── Error paths ──────────────────────────────────────────────

    @Test
    void parse_unsupportedMime_throws() {
        assertThatThrownBy(() -> ChecklistCodec.parse("- [ ] foo", "text/plain"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Unsupported mime type");
    }

    @Test
    void parseJson_invalidJson_throws() {
        assertThatThrownBy(() -> ChecklistCodec.parse("{not json", JSON_MIME))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Invalid JSON");
    }

    @Test
    void parseJson_emptyBody_returnsEmptyDoc() {
        ChecklistDocument doc = ChecklistCodec.parse("", JSON_MIME);
        assertThat(doc.items()).isEmpty();
        assertThat(doc.kind()).isEqualTo("checklist");
    }
}
