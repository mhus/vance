package de.mhus.vance.brain.milliways;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.api.milliways.ShareHandlerDto;
import de.mhus.vance.api.milliways.ShareResultDto;
import de.mhus.vance.shared.audit.AuditEventDto;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link MilliwaysService} — the façade's own three jobs
 * (resolve, authorize, record) plus how it treats handlers: unavailable
 * ones stay listed, a broken one does not take the menu down, and refusals
 * are distinguished from transport failures in the audit trail.
 */
class MilliwaysServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String PATH = "notes/results.md";
    private static final SecurityContext MARA =
            SecurityContext.user("mara", TENANT, List.of());

    private DocumentService documentService;
    private PermissionService permissionService;
    private AuditService auditService;
    private MetricService metricService;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        metricService = new MetricService(new SimpleMeterRegistry());
        when(documentService.findByPath(TENANT, PROJECT, PATH))
                .thenReturn(Optional.of(DocumentDocument.builder()
                        .id("doc-1")
                        .tenantId(TENANT)
                        .projectId(PROJECT)
                        .path(PATH)
                        .title("Results")
                        .build()));
    }

    // ── Listing ────────────────────────────────────────────────────

    @Test
    void listHandlers_unavailableHandler_staysListedWithReason() {
        MilliwaysService service = serviceWith(
                new StubHandler("inbox", ShareAvailability.ready()),
                new StubHandler("smtp", ShareAvailability.unavailable("No SMTP pack")));

        List<ShareHandlerDto> handlers = service.listHandlers(target());

        assertThat(handlers).extracting(ShareHandlerDto::getId)
                .containsExactly("inbox", "smtp");
        assertThat(handlers.get(1).isAvailable()).isFalse();
        assertThat(handlers.get(1).getStatusText()).isEqualTo("No SMTP pack");
    }

    @Test
    void listHandlers_orderIsAlphabetical_notBeanOrder() {
        MilliwaysService service = serviceWith(
                new StubHandler("smtp", ShareAvailability.ready()),
                new StubHandler("chat", ShareAvailability.ready()),
                new StubHandler("inbox", ShareAvailability.ready()));

        assertThat(service.listHandlers(target()))
                .extracting(ShareHandlerDto::getId)
                .containsExactly("chat", "inbox", "smtp");
    }

    @Test
    void listHandlers_brokenHandler_reportedUnavailable_othersSurvive() {
        MilliwaysService service = serviceWith(
                new StubHandler("inbox", ShareAvailability.ready()),
                new ThrowingAvailabilityHandler("smtp"));

        List<ShareHandlerDto> handlers = service.listHandlers(target());

        assertThat(handlers.get(0).isAvailable()).isTrue();
        assertThat(handlers.get(1).isAvailable()).isFalse();
        assertThat(handlers.get(1).getStatusText()).isNotBlank();
    }

    @Test
    void listHandlers_unreadableDocument_isDenied() {
        MilliwaysService service = serviceWith(new StubHandler("inbox", ShareAvailability.ready()));
        denyDocumentRead();

        assertThatThrownBy(() -> service.listHandlers(target()))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void listHandlers_enforcesDocumentRead_notProjectRead() {
        MilliwaysService service = serviceWith(new StubHandler("inbox", ShareAvailability.ready()));

        service.listHandlers(target());

        verify(permissionService).enforce(
                MARA, new Resource.Document(TENANT, PROJECT, PATH), Action.READ);
    }

    // ── Form ───────────────────────────────────────────────────────

    @Test
    void form_unknownHandler_isNotFound() {
        MilliwaysService service = serviceWith(new StubHandler("inbox", ShareAvailability.ready()));

        assertThatThrownBy(() -> service.form("carrier-pigeon", target()))
                .isInstanceOf(ShareNotFoundException.class)
                .hasMessageContaining("carrier-pigeon");
    }

    @Test
    void form_unavailableHandler_isRefusedWithItsOwnReason() {
        MilliwaysService service = serviceWith(
                new StubHandler("smtp", ShareAvailability.unavailable("No SMTP pack")));

        assertThatThrownBy(() -> service.form("smtp", target()))
                .isInstanceOf(ShareUnavailableException.class)
                .hasMessage("No SMTP pack");
    }

    @Test
    void form_carriesHandlerFields() {
        StubHandler handler = new StubHandler("inbox", ShareAvailability.ready());
        MilliwaysService service = serviceWith(handler);

        assertThat(service.form("inbox", target()).getFields())
                .extracting(FormFieldDto::getName)
                .containsExactly("text");
    }

    @Test
    void form_missingDocument_isNotFound() {
        MilliwaysService service = serviceWith(new StubHandler("inbox", ShareAvailability.ready()));
        when(documentService.findByPath(TENANT, PROJECT, PATH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.form("inbox", target()))
                .isInstanceOf(ShareNotFoundException.class)
                .hasMessageContaining(PATH);
    }

    // ── Share ──────────────────────────────────────────────────────

    @Test
    void share_success_passesResolvedDocumentAndValuesToHandler() {
        StubHandler handler = new StubHandler("inbox", ShareAvailability.ready());
        MilliwaysService service = serviceWith(handler);

        ShareResultDto result = service.share(
                "inbox", target(), Map.of("text", "look at this"));

        assertThat(result.getHandlerId()).isEqualTo("inbox");
        assertThat(result.getMessage()).isEqualTo("done");
        assertThat(handler.seen).isNotNull();
        assertThat(handler.seen.string("text")).isEqualTo("look at this");
        assertThat(handler.seen.scope().document().getId()).isEqualTo("doc-1");
    }

    @Test
    void share_success_isAudited() {
        MilliwaysService service = serviceWith(new StubHandler("inbox", ShareAvailability.ready()));

        service.share("inbox", target(), Map.of());

        ArgumentCaptor<AuditEventDto> captor = forClass(AuditEventDto.class);
        verify(auditService).record(captor.capture());
        AuditEventDto event = captor.getValue();
        assertThat(event.getAction()).isEqualTo(MilliwaysService.AUDIT_ACTION);
        assertThat(event.getOutcome()).isEqualTo("success");
        assertThat(event.getActor()).isEqualTo("mara");
        assertThat(event.getTarget()).isEqualTo(PROJECT + "/" + PATH);
        assertThat(event.getDetails()).containsEntry("handler", "inbox");
        assertThat(event.getDetails()).containsEntry("recipients", List.of("ford"));
    }

    @Test
    void share_handlerRefusal_isAuditedAsDeniedAndRethrown() {
        MilliwaysService service = serviceWith(
                new RefusingHandler("inbox", new ShareException("Say why")));

        assertThatThrownBy(() -> service.share("inbox", target(), Map.of()))
                .isInstanceOf(ShareException.class)
                .hasMessage("Say why");

        assertThat(auditOutcome()).isEqualTo("denied");
    }

    @Test
    void share_transportFailure_isAuditedAsFailedAndRethrown() {
        MilliwaysService service = serviceWith(
                new RefusingHandler("smtp", new IllegalStateException("relay refused")));

        assertThatThrownBy(() -> service.share("smtp", target(), Map.of()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditOutcome()).isEqualTo("failed");
    }

    @Test
    void share_unreadableDocument_isAuditedAsDeniedAndHandlerNeverRuns() {
        StubHandler handler = new StubHandler("inbox", ShareAvailability.ready());
        MilliwaysService service = serviceWith(handler);
        denyDocumentRead();

        assertThatThrownBy(() -> service.share("inbox", target(), Map.of()))
                .isInstanceOf(PermissionDeniedException.class);

        assertThat(handler.seen).isNull();
        assertThat(auditOutcome()).isEqualTo("denied");
    }

    @Test
    void share_unavailableHandler_neverRuns() {
        StubHandler handler = new StubHandler("smtp", ShareAvailability.unavailable("No SMTP pack"));
        MilliwaysService service = serviceWith(handler);

        assertThatThrownBy(() -> service.share("smtp", target(), Map.of()))
                .isInstanceOf(ShareUnavailableException.class);

        assertThat(handler.seen).isNull();
        verify(auditService, never()).record(any(AuditEventDto.class));
    }

    // ── Wiring ─────────────────────────────────────────────────────

    @Test
    void construction_duplicateHandlerIds_failsFast() {
        assertThatThrownBy(() -> serviceWith(
                new StubHandler("inbox", ShareAvailability.ready()),
                new StubHandler("inbox", ShareAvailability.ready())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inbox");
    }

    // ── helpers ────────────────────────────────────────────────────

    private MilliwaysService serviceWith(ShareHandler... handlers) {
        return new MilliwaysService(
                List.of(handlers), documentService, permissionService,
                auditService, metricService);
    }

    private ShareTarget target() {
        return new ShareTarget(MARA, TENANT, PROJECT, PATH);
    }

    private void denyDocumentRead() {
        doThrow(new PermissionDeniedException(
                MARA, new Resource.Document(TENANT, PROJECT, PATH), Action.READ))
                .when(permissionService)
                .enforce(any(SecurityContext.class), any(Resource.class), eq(Action.READ));
    }

    private String auditOutcome() {
        ArgumentCaptor<AuditEventDto> captor = forClass(AuditEventDto.class);
        verify(auditService).record(captor.capture());
        return captor.getValue().getOutcome();
    }

    /** Records what it was handed; reports whatever availability it was built with. */
    private static class StubHandler implements ShareHandler {
        private final String id;
        private final ShareAvailability availability;
        private @org.jspecify.annotations.Nullable ShareRequest seen;

        StubHandler(String id, ShareAvailability availability) {
            this.id = id;
            this.availability = availability;
        }

        @Override public String id() { return id; }

        @Override public Map<String, String> label() { return Map.of("en", id); }

        @Override public ShareAvailability availability(ShareScope scope) { return availability; }

        @Override public List<FormFieldDto> form(ShareScope scope) {
            return List.of(FormFieldDto.builder()
                    .name("text").type("textarea").label(Map.of("en", "Why")).build());
        }

        @Override public ShareResult share(ShareRequest request) {
            this.seen = request;
            return new ShareResult("done", Map.of("recipients", List.of("ford")));
        }
    }

    private static class ThrowingAvailabilityHandler extends StubHandler {
        ThrowingAvailabilityHandler(String id) {
            super(id, ShareAvailability.ready());
        }

        @Override public ShareAvailability availability(ShareScope scope) {
            throw new IllegalStateException("pack config unreadable");
        }
    }

    private static class RefusingHandler extends StubHandler {
        private final RuntimeException failure;

        RefusingHandler(String id, RuntimeException failure) {
            super(id, ShareAvailability.ready());
            this.failure = failure;
        }

        @Override public ShareResult share(ShareRequest request) {
            throw failure;
        }
    }
}
