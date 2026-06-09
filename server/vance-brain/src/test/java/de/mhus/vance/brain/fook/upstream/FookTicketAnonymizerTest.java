package de.mhus.vance.brain.fook.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.fook.TicketContext;
import de.mhus.vance.brain.fook.TicketDocument;
import de.mhus.vance.brain.fook.TicketRelations;
import de.mhus.vance.brain.fook.TicketReporter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link FookTicketAnonymizer}. No Spring,
 * no I/O.
 */
class FookTicketAnonymizerTest {

    private final FookTicketAnonymizer anonymizer = new FookTicketAnonymizer();

    // ─── available patterns ─────────────────────────────────────────

    @Test
    void available_patterns_list_is_stable() {
        assertThat(FookTicketAnonymizer.availablePatternNames())
                .containsExactly("email", "ipv4", "apiKey", "guid");
    }

    // ─── identity hashing ───────────────────────────────────────────

    @Test
    void reporter_identity_is_hashed_not_passed_through() {
        TicketDocument t = ticket("Brain crash", "Just descr.");
        ProviderTicketDraft draft = anonymizer.buildDraft(
                t, "secret", "fp-abc", true, List.of(), List.of());

        assertThat(draft.getReporterHash())
                .isNotEqualTo("alice")
                .matches("[0-9a-f]{16}");   // 16 hex chars
        assertThat(draft.getInstanceFingerprint()).isEqualTo("fp-abc");
        // The full body must not contain the real userId / tenantId.
        assertThat(draft.getBody()).doesNotContain("alice").doesNotContain("acme");
    }

    @Test
    void identity_hash_is_deterministic_for_same_inputs() {
        TicketDocument t1 = ticket("a", "b");
        TicketDocument t2 = ticket("different title", "different body");
        ProviderTicketDraft d1 = anonymizer.buildDraft(t1, "secret", "fp-1", true, List.of(), List.of());
        ProviderTicketDraft d2 = anonymizer.buildDraft(t2, "secret", "fp-1", true, List.of(), List.of());
        assertThat(d1.getReporterHash()).isEqualTo(d2.getReporterHash());
    }

    @Test
    void identity_hash_changes_when_secret_changes() {
        TicketDocument t = ticket("a", "b");
        ProviderTicketDraft d1 = anonymizer.buildDraft(t, "secret-A", "fp", true, List.of(), List.of());
        ProviderTicketDraft d2 = anonymizer.buildDraft(t, "secret-B", "fp", true, List.of(), List.of());
        assertThat(d1.getReporterHash()).isNotEqualTo(d2.getReporterHash());
    }

    @Test
    void anonymize_off_uses_anonymous_marker() {
        TicketDocument t = ticket("a", "b");
        ProviderTicketDraft d = anonymizer.buildDraft(
                t, "secret", "fp", false, List.of(), List.of());
        assertThat(d.getReporterHash()).isEqualTo("anonymous");
    }

    // ─── scrubbing patterns ─────────────────────────────────────────

    @Test
    void email_pattern_redacts_addresses() {
        TicketDocument t = ticket("Title", "Hi from alice@example.com, please help.");
        ProviderTicketDraft d = anonymizer.buildDraft(
                t, "s", "fp", true, List.of("email"), List.of());
        assertThat(d.getBody())
                .contains("[redacted-email]")
                .doesNotContain("alice@example.com");
    }

    @Test
    void ipv4_pattern_redacts_ip_addresses() {
        TicketDocument t = ticket("Title", "Connection refused to 10.0.0.42:8080.");
        ProviderTicketDraft d = anonymizer.buildDraft(
                t, "s", "fp", true, List.of("ipv4"), List.of());
        assertThat(d.getBody())
                .contains("[redacted-ip]")
                .doesNotContain("10.0.0.42");
    }

    @Test
    void apiKey_pattern_redacts_anthropic_openai_github_slack() {
        TicketDocument t = ticket("Title",
                "anthropic sk-ant-abcdef12345678901234567890 "
                        + "github ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "
                        + "slack xoxb-1234567890-abcdefghijklmnop");
        ProviderTicketDraft d = anonymizer.buildDraft(
                t, "s", "fp", true, List.of("apiKey"), List.of());
        assertThat(d.getBody())
                .contains("[redacted-key]")
                .doesNotContain("ghp_")
                .doesNotContain("sk-ant-")
                .doesNotContain("xoxb-");
    }

    @Test
    void guid_pattern_redacts_uuids() {
        TicketDocument t = ticket("Title",
                "Session id 7e3f1c2a-1234-5678-9abc-def012345678 failed.");
        ProviderTicketDraft d = anonymizer.buildDraft(
                t, "s", "fp", true, List.of("guid"), List.of());
        assertThat(d.getBody())
                .contains("[redacted-uuid]")
                .doesNotContain("7e3f1c2a-1234");
    }

    @Test
    void empty_pattern_list_keeps_text_intact() {
        TicketDocument t = ticket("alice@example.com",
                "Mail me at alice@example.com");
        ProviderTicketDraft d = anonymizer.buildDraft(
                t, "s", "fp", true, List.of(), List.of());
        assertThat(d.getTitle()).contains("alice@example.com");
        assertThat(d.getBody()).contains("alice@example.com");
    }

    @Test
    void unknown_pattern_name_is_ignored_silently() {
        TicketDocument t = ticket("Title", "Some text with alice@example.com.");
        ProviderTicketDraft d = anonymizer.buildDraft(
                t, "s", "fp", true,
                List.of("email", "nonexistent", "ipv4"),
                List.of());
        // email still gets applied; nonexistent is dropped.
        assertThat(d.getBody()).contains("[redacted-email]");
    }

    // ─── body structure ─────────────────────────────────────────────

    @Test
    void body_includes_metadata_section_with_non_identifying_context() {
        TicketDocument t = ticket("Brain crash", "boot fails.");
        ProviderTicketDraft d = anonymizer.buildDraft(
                t, "s", "fp-xyz", true, List.of(), List.of());

        // The non-identifying context fields are passed.
        assertThat(d.getBody())
                .contains("recipe: arthur")
                .contains("engine: arthur")
                .contains("type: bug")
                .contains("severity: high")
                .contains("fp-xyz");

        // Identifying context fields are NOT passed.
        assertThat(d.getBody())
                .doesNotContain("web-redesign")
                .doesNotContain("sess-")
                .doesNotContain("proc-");
    }

    @Test
    void body_lists_triage_note_when_present() {
        TicketDocument t = TicketDocument.builder()
                .id("uuid-1")
                .title("T")
                .type("bug")
                .severity("medium")
                .status("new")
                .description("d")
                .triageNote("Looks novel.")
                .reporter(reporter())
                .relations(emptyRel())
                .createdAt(Instant.now())
                .build();
        ProviderTicketDraft d = anonymizer.buildDraft(
                t, "s", "fp", true, List.of(), List.of());
        assertThat(d.getBody())
                .contains("## Triage notes")
                .contains("Looks novel.");
    }

    // ─── reply scrubbing ────────────────────────────────────────────

    @Test
    void scrubText_applies_patterns_to_arbitrary_string() {
        String result = anonymizer.scrubText(
                "Send to alice@example.com from 10.0.0.5",
                List.of("email", "ipv4"));
        assertThat(result)
                .contains("[redacted-email]")
                .contains("[redacted-ip]")
                .doesNotContain("alice")
                .doesNotContain("10.0.0.5");
    }

    // ─── extra labels passthrough ───────────────────────────────────

    @Test
    void extra_labels_pass_through_unchanged() {
        TicketDocument t = ticket("T", "d");
        ProviderTicketDraft d = anonymizer.buildDraft(
                t, "s", "fp", true, List.of(),
                List.of("env/prod", "team/backend"));
        assertThat(d.getExtraLabels())
                .containsExactly("env/prod", "team/backend");
    }

    // ─── helpers ────────────────────────────────────────────────────

    private static TicketDocument ticket(String title, String description) {
        return TicketDocument.builder()
                .id("uuid-1")
                .title(title)
                .type("bug")
                .severity("high")
                .status("new")
                .description(description)
                .triageNote(null)
                .context(TicketContext.builder()
                        .projectId("web-redesign")
                        .sessionId("sess-1")
                        .processId("proc-1")
                        .recipe("arthur")
                        .engine("arthur")
                        .build())
                .reporter(reporter())
                .relations(emptyRel())
                .createdAt(Instant.now())
                .build();
    }

    private static TicketReporter reporter() {
        return TicketReporter.builder()
                .kind(TicketReporter.Kind.ENGINE)
                .userId("alice")
                .tenantId("acme")
                .build();
    }

    private static TicketRelations emptyRel() {
        return TicketRelations.builder()
                .duplicateOf(null)
                .rootCauseOf(List.of())
                .relatedTo(List.of())
                .build();
    }
}
