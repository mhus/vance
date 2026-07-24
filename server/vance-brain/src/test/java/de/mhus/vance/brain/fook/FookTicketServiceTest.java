package de.mhus.vance.brain.fook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.yaml.snakeyaml.Yaml;

/**
 * Pure-logic tests for {@link FookTicketService}. {@link DocumentService}
 * is mocked — no Spring context, no Mongo. Covers the round-trip
 * codec (create → upsertText payload → parseRoot path equivalence),
 * the Jaccard ranking on token overlap, and the relations-merge
 * semantics.
 */
class FookTicketServiceTest {

    private DocumentService documentService;
    private FookTicketService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        service = new FookTicketService(documentService);
    }

    // ─── createTicket: structure + meta ─────────────────────────────

    @Test
    void create_emits_meta_wrapper_with_required_fields() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.upsertText(any(), any(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(new DocumentDocument());

        TicketReporter reporter = TicketReporter.builder()
                .kind(TicketReporter.Kind.ENGINE)
                .userId("alice")
                .tenantId("acme")
                .build();
        NewTicketPayload payload = NewTicketPayload.builder()
                .title("Brain crashes on missing recipes.yaml")
                .description("When the recipes folder is empty, brain throws NPE on boot.")
                .type("bug")
                .severity("high")
                .reporter(reporter)
                .relatedTickets(List.of())
                .build();

        String uuid = service.createTicket(payload);
        assertThat(uuid).isNotBlank();

        String yaml = captureUpsertedText();
        Map<String, Object> root = parse(yaml);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) root.get("$meta");

        assertThat(meta).isNotNull();
        assertThat(meta.get("kind")).isEqualTo("fook-ticket");
        assertThat(meta.get("id")).isEqualTo(uuid);
        assertThat(meta.get("status")).isEqualTo("new");
        assertThat(meta.get("triagedBy")).isEqualTo("fook");
        assertThat(meta.get("type")).isEqualTo("bug");
        assertThat(meta.get("severity")).isEqualTo("high");
        assertThat(meta.get("duplicateOf")).isNull();
        assertThat(meta.get("reporterKind")).isEqualTo("engine");
        assertThat(meta.get("reporterUserId")).isEqualTo("alice");
        assertThat(meta.get("reporterTenantId")).isEqualTo("acme");
    }

    @Test
    void create_routes_description_to_body_not_meta() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.upsertText(any(), any(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(new DocumentDocument());

        service.createTicket(NewTicketPayload.builder()
                .title("T")
                .description("Body lives outside meta.")
                .type("bug")
                .severity("low")
                .reporter(TicketReporter.builder()
                        .kind(TicketReporter.Kind.USER_DIRECT)
                        .userId("bob")
                        .tenantId("acme")
                        .build())
                .relatedTickets(List.of())
                .build());

        Map<String, Object> root = parse(captureUpsertedText());
        assertThat(root.get("description")).isEqualTo("Body lives outside meta.");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) root.get("$meta");
        assertThat(meta).doesNotContainKey("description");
    }

    @Test
    void create_includes_relatedTo_when_provided() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.upsertText(any(), any(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(new DocumentDocument());

        service.createTicket(NewTicketPayload.builder()
                .title("T").description("d").type("bug").severity("low")
                .reporter(reporterEngine())
                .relatedTickets(List.of("uuid-a", "uuid-b"))
                .build());

        Map<String, Object> root = parse(captureUpsertedText());
        @SuppressWarnings("unchecked")
        Map<String, Object> relations = (Map<String, Object>) root.get("relations");
        assertThat(relations.get("relatedTo")).isEqualTo(List.of("uuid-a", "uuid-b"));
        assertThat(relations.get("rootCauseOf")).isEqualTo(List.of());
    }

    @Test
    void create_path_uses_prefix_and_uuid() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.upsertText(any(), any(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(new DocumentDocument());

        String uuid = service.createTicket(minimalPayload());

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                eq("_vance"), eq("_tenant"), path.capture(),
                any(), anyList(), any(), any(), any());
        assertThat(path.getValue()).isEqualTo("_vance/fook/tickets/" + uuid + ".yaml");
    }

    // ─── searchSimilar: token-overlap ranking ───────────────────────

    @Test
    void search_ranks_higher_overlap_first() {
        // Both tickets share at least one token with the query so both
        // surface as candidates — but `uuid-crash` overlaps on six
        // tokens vs. `uuid-color`'s one, so it must come first.
        DocumentDocument crashTicket = ticketDoc(
                "uuid-crash",
                "Brain crashes on missing recipes.yaml",
                "Boot throws NPE when recipes folder is empty.");
        DocumentDocument weakOverlap = ticketDoc(
                "uuid-color",
                "Sidebar color too pale on boot",
                "Hover state barely visible.");
        Page<DocumentDocument> page = new PageImpl<>(
                List.of(weakOverlap, crashTicket),
                PageRequest.of(0, 500), 2);
        when(documentService.listByProjectPaged(
                eq("_vance"), eq("_tenant"), eq(0), eq(500),
                eq("_vance/fook/tickets/"), eq("fook-ticket")))
                .thenReturn(page);

        List<TicketCandidate> hits = service.searchSimilar(
                "Brain crash on boot NPE in recipes loader when folder empty",
                5);
        assertThat(hits).extracting(TicketCandidate::getId)
                .containsExactly("uuid-crash", "uuid-color");
    }

    @Test
    void search_returns_empty_when_no_tickets_indexed() {
        Page<DocumentDocument> empty = new PageImpl<>(List.of());
        when(documentService.listByProjectPaged(
                eq("_vance"), eq("_tenant"), eq(0), eq(500),
                eq("_vance/fook/tickets/"), eq("fook-ticket")))
                .thenReturn(empty);

        List<TicketCandidate> hits = service.searchSimilar("any thing", 5);
        assertThat(hits).isEmpty();
    }

    @Test
    void search_excludes_tickets_with_zero_token_overlap() {
        DocumentDocument other = ticketDoc(
                "uuid-other",
                "Calendar widget shows wrong week",
                "Off-by-one in week number");
        Page<DocumentDocument> page = new PageImpl<>(List.of(other));
        when(documentService.listByProjectPaged(
                any(), any(), any(int.class), any(int.class),
                any(), any())).thenReturn(page);

        List<TicketCandidate> hits = service.searchSimilar(
                "totally unrelated topic nothing matches here", 5);
        // No overlap at all → no candidates surfaced.
        assertThat(hits).isEmpty();
    }

    @Test
    void search_limits_results_to_requested_count() {
        DocumentDocument a = ticketDoc("a", "alpha beta gamma", "delta");
        DocumentDocument b = ticketDoc("b", "alpha beta", "delta");
        DocumentDocument c = ticketDoc("c", "alpha", "delta");
        Page<DocumentDocument> page = new PageImpl<>(List.of(a, b, c));
        when(documentService.listByProjectPaged(
                any(), any(), any(int.class), any(int.class),
                any(), any())).thenReturn(page);

        List<TicketCandidate> hits = service.searchSimilar(
                "alpha beta gamma delta", 2);
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).getId()).isEqualTo("a");
    }

    // ─── readTicket: parse round-trip ───────────────────────────────

    @Test
    void read_roundtrips_a_created_ticket() {
        // Capture the YAML the create-path emits, then feed it back
        // through readTicket and verify field equivalence.
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.upsertText(any(), any(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(new DocumentDocument());

        String uuid = service.createTicket(NewTicketPayload.builder()
                .title("Brain crash")
                .description("NPE on boot.")
                .type("bug")
                .severity("high")
                .triageNote("Looks novel — no candidate matched.")
                .context(TicketContext.builder()
                        .projectId("web-redesign")
                        .sessionId("sess-1")
                        .processId("proc-1")
                        .recipe("arthur")
                        .engine("arthur")
                        .build())
                .reporter(reporterEngine())
                .relatedTickets(List.of("other-uuid"))
                .build());

        String yaml = captureUpsertedText();
        DocumentDocument doc = DocumentDocument.builder()
                .path("_vance/fook/tickets/" + uuid + ".yaml")
                .storageId("blob-test-yaml")
                .mimeType("application/yaml")
                .build();
        when(documentService.findByPath("_vance", "_tenant",
                "_vance/fook/tickets/" + uuid + ".yaml"))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(yaml);

        Optional<TicketDocument> read = service.readTicket(uuid);
        assertThat(read).isPresent();
        TicketDocument t = read.get();
        assertThat(t.getId()).isEqualTo(uuid);
        assertThat(t.getTitle()).isEqualTo("Brain crash");
        assertThat(t.getType()).isEqualTo("bug");
        assertThat(t.getSeverity()).isEqualTo("high");
        assertThat(t.getStatus()).isEqualTo("new");
        assertThat(t.getDescription()).isEqualTo("NPE on boot.");
        assertThat(t.getTriageNote()).isEqualTo("Looks novel — no candidate matched.");
        assertThat(t.getContext()).isNotNull();
        assertThat(t.getContext().getProjectId()).isEqualTo("web-redesign");
        assertThat(t.getReporter().getKind()).isEqualTo(TicketReporter.Kind.ENGINE);
        assertThat(t.getReporter().getUserId()).isEqualTo("alice");
        assertThat(t.getRelations().getRelatedTo()).containsExactly("other-uuid");
        assertThat(t.getCreatedAt()).isNotNull();
        assertThat(t.getTriagedAt()).isNotNull();
    }

    @Test
    void read_returns_empty_when_ticket_does_not_exist() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        assertThat(service.readTicket("missing-uuid")).isEmpty();
    }

    // ─── updateRelations: merge semantics ───────────────────────────

    @Test
    void update_sets_duplicateOf_and_merges_relatedTo() {
        String existing = """
                $meta:
                  kind: fook-ticket
                  id: uuid-1
                  title: Existing
                  type: bug
                  severity: medium
                  status: new
                  duplicateOf: null
                  reporterKind: engine
                  reporterUserId: alice
                  reporterTenantId: acme
                  createdAt: '2026-01-01T00:00:00Z'
                  triagedAt: '2026-01-01T00:00:00Z'
                  triagedBy: fook
                description: |
                  Original description.
                relations:
                  rootCauseOf: []
                  relatedTo:
                    - 'pre-existing-1'
                """;
        DocumentDocument doc = DocumentDocument.builder()
                .path("_vance/fook/tickets/uuid-1.yaml")
                .storageId("blob-test-existing")
                .mimeType("application/yaml")
                .title("Existing")
                .build();
        when(documentService.findByPath("_vance", "_tenant",
                "_vance/fook/tickets/uuid-1.yaml")).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(existing);
        when(documentService.upsertText(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(doc);

        service.updateRelations("uuid-1", RelationsPatch.builder()
                .duplicateOf("uuid-target")
                .addRelatedTo(List.of("pre-existing-1", "new-1"))   // dedup verified
                .addRootCauseOf(List.of("rc-1"))
                .build());

        String updated = captureUpsertedText();
        Map<String, Object> root = parse(updated);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) root.get("$meta");
        @SuppressWarnings("unchecked")
        Map<String, Object> relations = (Map<String, Object>) root.get("relations");

        assertThat(meta.get("duplicateOf")).isEqualTo("uuid-target");
        assertThat(relations.get("relatedTo"))
                .isEqualTo(List.of("pre-existing-1", "new-1"));
        assertThat(relations.get("rootCauseOf")).isEqualTo(List.of("rc-1"));
    }

    @Test
    void update_throws_when_ticket_missing() {
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateRelations("absent",
                RelationsPatch.builder()
                        .duplicateOf("x")
                        .addRelatedTo(List.of())
                        .addRootCauseOf(List.of())
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent");
    }

    @Test
    void update_leaves_duplicateOf_when_patch_null() {
        String existing = """
                $meta:
                  kind: fook-ticket
                  id: uuid-2
                  title: T
                  type: bug
                  severity: low
                  status: new
                  duplicateOf: 'prior-target'
                  reporterKind: engine
                  reporterUserId: alice
                  reporterTenantId: acme
                  createdAt: '2026-01-01T00:00:00Z'
                  triagedAt: '2026-01-01T00:00:00Z'
                  triagedBy: fook
                description: ''
                relations:
                  rootCauseOf: []
                  relatedTo: []
                """;
        DocumentDocument doc = DocumentDocument.builder()
                .path("_vance/fook/tickets/uuid-2.yaml")
                .storageId("blob-test-existing").mimeType("application/yaml").build();
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(existing);
        when(documentService.upsertText(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(doc);

        service.updateRelations("uuid-2", RelationsPatch.builder()
                .duplicateOf(null)
                .addRelatedTo(List.of("new"))
                .addRootCauseOf(List.of())
                .build());

        Map<String, Object> root = parse(captureUpsertedText());
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) root.get("$meta");
        assertThat(meta.get("duplicateOf")).isEqualTo("prior-target");
    }

    // ─── helpers ────────────────────────────────────────────────────

    private TicketReporter reporterEngine() {
        return TicketReporter.builder()
                .kind(TicketReporter.Kind.ENGINE)
                .userId("alice")
                .tenantId("acme")
                .build();
    }

    private NewTicketPayload minimalPayload() {
        return NewTicketPayload.builder()
                .title("T").description("d").type("bug").severity("low")
                .reporter(reporterEngine())
                .relatedTickets(List.of())
                .build();
    }

    private DocumentDocument ticketDoc(String id, String title, String desc) {
        String yaml = """
                $meta:
                  kind: fook-ticket
                  id: %s
                  title: '%s'
                  type: bug
                  severity: medium
                  status: new
                  duplicateOf: null
                  reporterKind: engine
                  reporterUserId: alice
                  reporterTenantId: acme
                  createdAt: '2026-01-01T00:00:00Z'
                  triagedAt: '2026-01-01T00:00:00Z'
                  triagedBy: fook
                description: '%s'
                relations:
                  rootCauseOf: []
                  relatedTo: []
                """.formatted(id, title, desc);
        DocumentDocument doc = DocumentDocument.builder()
                .path("_vance/fook/tickets/" + id + ".yaml")
                .storageId("blob-test-" + id)
                .mimeType("application/yaml")
                .title(title)
                .build();
        when(documentService.readContent(doc)).thenReturn(yaml);
        return doc;
    }

    private String captureUpsertedText() {
        ArgumentCaptor<String> textCap = ArgumentCaptor.forClass(String.class);
        verify(documentService).upsertText(
                any(), any(), any(), any(), anyList(),
                textCap.capture(), any(), any());
        return textCap.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String yaml) {
        return (Map<String, Object>) new Yaml().load(yaml);
    }
}
