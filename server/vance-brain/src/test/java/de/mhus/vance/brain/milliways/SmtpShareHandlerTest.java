package de.mhus.vance.brain.milliways;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.tools.mail.SmtpSenderToolFactory;
import de.mhus.vance.brain.tools.rest.SettingsSecretResolver;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SmtpShareHandler}. The send mechanics live in
 * {@code SmtpSenderAttachmentTest} (vance-toolpack, against GreenMail);
 * what is tested here is everything around them — which packs count as
 * available, what the form asks for, and how the two failure kinds are
 * translated: a refusal the user can fix ({@link ShareException}) versus a
 * relay that broke ({@link ShareTransportException}).
 */
class SmtpShareHandlerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String PATH = "notes/results.md";
    private static final SecurityContext MARA =
            SecurityContext.user("mara", TENANT, List.of());
    private static final byte[] CONTENT =
            "# Results\n".getBytes(StandardCharsets.UTF_8);

    private ServerToolService serverToolService;
    private SettingsSecretResolver secretResolver;
    private DocumentService documentService;
    private SmtpShareHandler handler;

    @BeforeEach
    void setUp() {
        serverToolService = mock(ServerToolService.class);
        secretResolver = mock(SettingsSecretResolver.class);
        documentService = mock(DocumentService.class);
        handler = new SmtpShareHandler(
                serverToolService, secretResolver, documentService, 10 * 1024 * 1024);
        // Pass-through resolver: the interesting assertion is *which* method
        // gets called (connector path), not what it substitutes.
        when(secretResolver.resolveForConnector(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(documentService.loadContent(any(DocumentDocument.class)))
                .thenAnswer(inv -> new ByteArrayInputStream(CONTENT));
    }

    // ── Availability ───────────────────────────────────────────────

    @Test
    void availability_noPackInProject_isUnavailable() {
        givenPacks();

        ShareAvailability availability = handler.availability(scope());

        assertThat(availability.available()).isFalse();
        assertThat(availability.statusText()).contains("SMTP");
    }

    @Test
    void availability_onlyDisabledPack_isUnavailable() {
        givenPacks(pack("relay", false, Map.of("host", "mail.example.com")));

        assertThat(handler.availability(scope()).available()).isFalse();
    }

    @Test
    void availability_otherToolTypesAreIgnored() {
        givenPacks(new ServerToolConfig(
                "jira", "rest_api", "", Map.of(), List.of(), true, false,
                Set.of(), false, "", ServerToolConfig.Source.PROJECT, null, null, ""));

        assertThat(handler.availability(scope()).available()).isFalse();
    }

    @Test
    void availability_activePack_isReady() {
        givenPacks(pack("relay", true, Map.of("host", "mail.example.com")));

        assertThat(handler.availability(scope()).available()).isTrue();
    }

    // ── Form ───────────────────────────────────────────────────────

    @Test
    void form_singlePack_asksNothingAboutThePack() {
        givenPacks(pack("relay", true, Map.of("host", "mail.example.com")));

        assertThat(handler.form(scope()))
                .extracting(FormFieldDto::getName)
                .containsExactly(
                        SmtpShareHandler.FIELD_TO,
                        SmtpShareHandler.FIELD_SUBJECT,
                        SmtpShareHandler.FIELD_TEXT);
    }

    @Test
    void form_twoPacks_offersTheChoice() {
        givenPacks(
                pack("house", true, Map.of("host", "a", "from", "team@example.com")),
                pack("bulk", true, Map.of("host", "b")));

        List<FormFieldDto> fields = handler.form(scope());

        FormFieldDto packField = fields.get(0);
        assertThat(packField.getName()).isEqualTo(SmtpShareHandler.FIELD_PACK);
        assertThat(packField.getType()).isEqualTo("select");
        assertThat(packField.getDefaultValue()).isEqualTo("house");
        assertThat(packField.getChoices()).extracting(FormChoiceDto::getValue)
                .containsExactly("house", "bulk");
        // The From address disambiguates two relays better than a name.
        assertThat(packField.getChoices().get(0).getLabel().values())
                .containsExactly("house (team@example.com)");
    }

    @Test
    void form_subjectDefaultsToDocumentTitle() {
        givenPacks(pack("relay", true, Map.of("host", "a")));

        FormFieldDto subject = handler.form(scope()).get(1);

        assertThat(subject.getName()).isEqualTo(SmtpShareHandler.FIELD_SUBJECT);
        assertThat(subject.getDefaultValue()).isEqualTo("Results");
    }

    @Test
    void form_untitledDocument_subjectDefaultsToFileName() {
        givenPacks(pack("relay", true, Map.of("host", "a")));

        FormFieldDto subject = handler.form(scopeOf(doc(null, CONTENT.length))).get(1);

        assertThat(subject.getDefaultValue()).isEqualTo("results.md");
    }

    // ── Refusals the user can fix ──────────────────────────────────

    @Test
    void share_noRecipient_isRefused() {
        givenPacks(pack("relay", true, Map.of("host", "a", "from", "me@example.com")));

        assertThatThrownBy(() -> handler.share(request(Map.of("to", "  "))))
                .isInstanceOf(ShareException.class);
    }

    @Test
    void share_unknownPack_isRefused() {
        givenPacks(pack("relay", true, Map.of("host", "a")));

        assertThatThrownBy(() -> handler.share(request(Map.of(
                "pack", "somewhere-else", "to", "ford@example.com"))))
                .isInstanceOf(ShareException.class)
                .hasMessageContaining("somewhere-else");
    }

    @Test
    void share_packDisappeared_isUnavailable() {
        givenPacks();

        assertThatThrownBy(() -> handler.share(request(Map.of("to", "ford@example.com"))))
                .isInstanceOf(ShareUnavailableException.class);
    }

    @Test
    void share_documentBeyondTheCap_isRefusedBeforeReading() {
        handler = new SmtpShareHandler(serverToolService, secretResolver, documentService, 1024);
        givenPacks(pack("relay", true, Map.of("host", "a", "from", "me@example.com")));

        assertThatThrownBy(() -> handler.share(
                requestOn(doc("Results", 5000), Map.of("to", "ford@example.com"))))
                .isInstanceOf(ShareException.class)
                .hasMessageContaining("limit");

        // Never even opened the stream — a named refusal, not a slow one.
        verify(documentService, org.mockito.Mockito.never())
                .loadContent(any(DocumentDocument.class));
    }

    @Test
    void share_recipientOutsideAllowedDomains_isRefusedNotFailed() {
        // The pack's own exfiltration guard. It fires before any socket is
        // opened, and it is a refusal the user can act on — so it must not
        // surface as a transport failure.
        givenPacks(pack("relay", true, Map.of(
                "host", "127.0.0.1",
                "port", 1,
                "from", "me@example.com",
                "allowedRecipientDomains", List.of("example.com"))));

        assertThatThrownBy(() -> handler.share(request(Map.of("to", "ford@elsewhere.test"))))
                .isInstanceOf(ShareException.class)
                .isNotInstanceOf(ShareTransportException.class);
    }

    // ── The relay broke ────────────────────────────────────────────

    @Test
    void share_deadRelay_isTransportFailure() {
        // Port 1 on loopback refuses immediately; no waiting on a timeout.
        givenPacks(pack("relay", true, Map.of(
                "host", "127.0.0.1", "port", 1, "from", "me@example.com",
                "starttls", false, "tls", false, "timeoutSeconds", 1)));

        assertThatThrownBy(() -> handler.share(request(Map.of("to", "ford@example.com"))))
                .isInstanceOf(ShareTransportException.class);
    }

    // ── Secrets ────────────────────────────────────────────────────

    @Test
    void share_resolvesPackSecretsOnTheConnectorPath() {
        givenPacks(pack("relay", true, Map.of(
                "host", "127.0.0.1", "port", 1, "from", "me@example.com",
                "password", "{{secret:project:smtp.pass}}",
                "starttls", false, "tls", false, "timeoutSeconds", 1)));

        // The send itself fails (dead port) — the assertion is about what
        // happened before it: a connector may read PASSWORD settings, the
        // restrictive resolve() path may not, and picking the wrong one
        // would leave the relay unauthenticated with an empty password.
        assertThatThrownBy(() -> handler.share(request(Map.of("to", "ford@example.com"))))
                .isInstanceOf(ShareTransportException.class);

        verify(secretResolver).resolveForConnector(
                org.mockito.ArgumentMatchers.eq("{{secret:project:smtp.pass}}"),
                any(ToolInvocationContext.class));
        verify(secretResolver, org.mockito.Mockito.never())
                .resolve(any(), any(ToolInvocationContext.class));
    }

    // ── A pack that cannot be built ────────────────────────────────

    @Test
    void share_packWithoutHost_isRefusedNotFailed() {
        // availability() only checks that a pack exists, so a half-filled one
        // reports READY and the user gets as far as submitting. Building the
        // config threw outside the try, and the IllegalArgumentException
        // escaped to MilliwaysService as outcome=failed / HTTP 500 — instead
        // of the 422 carrying the sentence that says which field is missing.
        givenPacks(pack("relay", true, Map.of("from", "me@example.com")));

        assertThatThrownBy(() -> handler.share(request(Map.of("to", "ford@example.com"))))
                .isInstanceOf(ShareException.class)
                .isNotInstanceOf(ShareTransportException.class)
                .hasMessageContaining("host");
    }

    // ── helpers ────────────────────────────────────────────────────

    private void givenPacks(ServerToolConfig... packs) {
        when(serverToolService.listConfigs(TENANT, PROJECT)).thenReturn(List.of(packs));
    }

    private static ServerToolConfig pack(
            String name, boolean enabled, Map<String, Object> parameters) {
        return new ServerToolConfig(
                name, SmtpSenderToolFactory.TYPE_ID, "", parameters, List.of(),
                enabled, false, Set.of(), false, "",
                ServerToolConfig.Source.PROJECT, null, null, "");
    }

    private ShareRequest request(Map<String, Object> values) {
        return requestOn(doc("Results", CONTENT.length), values);
    }

    private ShareRequest requestOn(DocumentDocument document, Map<String, Object> values) {
        return new ShareRequest(scopeOf(document), values);
    }

    private static ShareScope scope() {
        return scopeOf(doc("Results", CONTENT.length));
    }

    private static ShareScope scopeOf(DocumentDocument document) {
        return new ShareScope(MARA, TENANT, PROJECT, PATH, document);
    }

    private static DocumentDocument doc(String title, long size) {
        return DocumentDocument.builder()
                .id("doc-1")
                .tenantId(TENANT)
                .projectId(PROJECT)
                .path(PATH)
                .title(title)
                .mimeType("text/markdown")
                .size(size)
                .build();
    }
}
