package de.mhus.vance.brain.milliways;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.oauth.OAuthConfigRegistry;
import de.mhus.vance.brain.oauth.OAuthExpiredException;
import de.mhus.vance.brain.oauth.OAuthProvider;
import de.mhus.vance.brain.oauth.OAuthProviderConfig;
import de.mhus.vance.brain.oauth.OAuthTokenRefresher;
import de.mhus.vance.brain.oauth.ResolvedOAuthProvider;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentRef;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.mail.MailMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link GmailShareHandler}. The wire mechanics live in
 * {@link GmailApiClientTest}; what is tested here is everything around them —
 * the three separate reasons this handler can be unavailable, and how the
 * failure kinds are translated: a revoked account is
 * {@link ShareUnavailableException} (reconnect), a rejected message is
 * {@link ShareException} (fix it), a dead API is
 * {@link ShareTransportException}.
 */
class GmailShareHandlerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String PATH = "notes/results.md";
    private static final String TOKEN_KEY = "oauth.google.access_token";
    private static final String SCOPES_KEY = "oauth.google.scopes";
    private static final String SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";
    private static final SecurityContext MARA =
            SecurityContext.user("mara", TENANT, List.of());
    private static final byte[] CONTENT = "# Results\n".getBytes(StandardCharsets.UTF_8);

    private OAuthConfigRegistry configRegistry;
    private OAuthTokenRefresher tokenRefresher;
    private SettingService settingService;
    private DocumentService documentService;
    private GmailApiClient gmailApiClient;
    private GmailShareHandler handler;

    @BeforeEach
    void setUp() {
        configRegistry = mock(OAuthConfigRegistry.class);
        tokenRefresher = mock(OAuthTokenRefresher.class);
        settingService = mock(SettingService.class);
        documentService = mock(DocumentService.class);
        gmailApiClient = mock(GmailApiClient.class);
        handler = new GmailShareHandler(
                configRegistry, tokenRefresher, settingService, documentService,
                gmailApiClient, "google", 25L * 1024 * 1024);

        when(documentService.loadContent(any(DocumentDocument.class)))
                .thenAnswer(inv -> new ByteArrayInputStream(CONTENT));
        when(tokenRefresher.resolveAccessToken(TENANT, "mara", "google")).thenReturn("tok-1");
        when(gmailApiClient.send(anyString(), any(MailMessage.class)))
                .thenReturn(Map.of("id", "18f0", "threadId", "18f0"));
    }

    // ── Availability: three different missing things ───────────────

    @Test
    void availability_tenantHasNoGoogleProvider_namesTheAdminStep() {
        givenProviderConfigured(false);

        ShareAvailability availability = handler.availability(scope());

        assertThat(availability.available()).isFalse();
        assertThat(availability.statusText()).contains("OAuth Providers");
    }

    @Test
    void availability_userNeverConnected_namesTheUserStep() {
        givenProviderConfigured(true);
        givenConnected(false);

        ShareAvailability availability = handler.availability(scope());

        assertThat(availability.available()).isFalse();
        assertThat(availability.statusText()).contains("Connected Accounts");
    }

    @Test
    void availability_connectedWithoutMailScope_isUnavailable() {
        // A Google connection made for Drive or Calendar carries no
        // permission to send. Answering that here is the whole point: the
        // alternative is walking the sharer through the form and then
        // handing them Google's 403.
        givenProviderConfigured(true);
        givenConnected(true);
        givenScopes("https://www.googleapis.com/auth/drive.file openid email");

        ShareAvailability availability = handler.availability(scope());

        assertThat(availability.available()).isFalse();
        assertThat(availability.statusText()).contains("send mail");
    }

    @Test
    void availability_readonlyMailScopeDoesNotCount() {
        // gmail.readonly shares a prefix with the sending scopes and permits
        // nothing — the check lists scopes rather than matching a prefix.
        givenProviderConfigured(true);
        givenConnected(true);
        givenScopes("https://www.googleapis.com/auth/gmail.readonly");

        assertThat(handler.availability(scope()).available()).isFalse();
    }

    @Test
    void availability_connectedWithSendScope_isReady() {
        givenReady();

        assertThat(handler.availability(scope()).available()).isTrue();
    }

    @Test
    void availability_unknownScopeList_isReady() {
        // The scopes setting is only written when the provider returns a
        // scope claim. An older connection without one works; reporting it
        // as broken would hide a usable account behind an unactionable line.
        givenProviderConfigured(true);
        givenConnected(true);
        givenScopes(null);

        assertThat(handler.availability(scope()).available()).isTrue();
    }

    // ── Form ───────────────────────────────────────────────────────

    @Test
    void form_asksNothingAboutTheSender() {
        givenReady();

        assertThat(handler.form(scope()))
                .extracting(FormFieldDto::getName)
                .containsExactly(
                        MailShareSupport.FIELD_TO,
                        MailShareSupport.FIELD_SUBJECT,
                        MailShareSupport.FIELD_TEXT);
    }

    // ── Sending ────────────────────────────────────────────────────

    @Test
    void share_sendsWithTheSharersOwnTokenAndNoFromHeader() {
        givenReady();

        handler.share(request(Map.of("to", "ford@example.com", "text", "have a look")));

        ArgumentCaptor<MailMessage> sent = ArgumentCaptor.forClass(MailMessage.class);
        verify(gmailApiClient).send(eq("tok-1"), sent.capture());
        // From is Google's to fill in — an invented one is either redundant
        // or rejected as an unregistered send-as alias.
        assertThat(sent.getValue().from()).isNull();
        assertThat(sent.getValue().to()).containsExactly("ford@example.com");
        assertThat(sent.getValue().body()).isEqualTo("have a look");
        assertThat(sent.getValue().attachmentsOrEmpty()).hasSize(1);
    }

    @Test
    void share_documentlessSubject_sendsWithoutAttachment() {
        givenReady();

        handler.share(new ShareRequest(linkScope(), Map.of("to", "ford@example.com")));

        ArgumentCaptor<MailMessage> sent = ArgumentCaptor.forClass(MailMessage.class);
        verify(gmailApiClient).send(anyString(), sent.capture());
        assertThat(sent.getValue().attachmentsOrEmpty()).isEmpty();
        verify(documentService, never()).loadContent(any(DocumentDocument.class));
    }

    @Test
    void share_recordsDomainsAndTheMessageId() {
        givenReady();

        ShareResult result = handler.share(request(Map.of(
                "to", "ford@example.com, zaphod@example.org")));

        assertThat(result.details())
                .containsEntry("provider", "google")
                .containsEntry("recipientCount", 2)
                .containsEntry("recipientDomains", List.of("example.com", "example.org"))
                .containsEntry("messageId", "18f0")
                .containsEntry("attachment", "results.md");
        assertThat(result.message()).contains("2 recipients");
    }

    @Test
    void share_noRecipient_isRefused() {
        givenReady();

        assertThatThrownBy(() -> handler.share(request(Map.of("to", "  "))))
                .isInstanceOf(ShareException.class);
        verify(gmailApiClient, never()).send(anyString(), any(MailMessage.class));
    }

    // ── The three failure kinds ────────────────────────────────────

    @Test
    void share_connectionRevokedBetweenCheckAndSend_isUnavailable() {
        givenReady();
        when(tokenRefresher.resolveAccessToken(TENANT, "mara", "google"))
                .thenThrow(new OAuthExpiredException("google", "provider rejected refresh"));

        assertThatThrownBy(() -> handler.share(request(Map.of("to", "ford@example.com"))))
                .isInstanceOf(ShareUnavailableException.class)
                .hasMessageContaining("Connected Accounts");
    }

    @Test
    void share_googleRejectsTheToken_isUnavailableNotFailed() {
        // 401/403 is not a broken relay — the sharer reconnects and it works.
        givenReady();
        givenSendFails(new GmailApiClient.GmailException("nope", 403, true, null));

        assertThatThrownBy(() -> handler.share(request(Map.of("to", "ford@example.com"))))
                .isInstanceOf(ShareUnavailableException.class);
    }

    @Test
    void share_googleRefusesTheMessage_isRefusedNotFailed() {
        givenReady();
        givenSendFails(new GmailApiClient.GmailException("Invalid To header", 400, true, null));

        assertThatThrownBy(() -> handler.share(request(Map.of("to", "ford@example.com"))))
                .isInstanceOf(ShareException.class)
                .isNotInstanceOf(ShareTransportException.class)
                .isNotInstanceOf(ShareUnavailableException.class)
                .hasMessageContaining("Invalid To header");
    }

    @Test
    void share_googleUnreachable_isTransportFailure() {
        givenReady();
        givenSendFails(new GmailApiClient.GmailException("timeout", 0, false, null));

        assertThatThrownBy(() -> handler.share(request(Map.of("to", "ford@example.com"))))
                .isInstanceOf(ShareTransportException.class);
    }

    // ── A tenant that named its provider differently ───────────────

    @Test
    void providerId_isConfigurable_andDrivesEveryLookup() {
        handler = new GmailShareHandler(
                configRegistry, tokenRefresher, settingService, documentService,
                gmailApiClient, "workspace", 1024 * 1024);
        when(configRegistry.resolve(TENANT, "workspace"))
                .thenReturn(Optional.of(resolvedProvider("workspace")));
        when(settingService.getDecryptedUserPassword(
                TENANT, "mara", "oauth.workspace.access_token")).thenReturn("stored");
        when(settingService.getUserStringValue(TENANT, "mara", "oauth.workspace.scopes"))
                .thenReturn(SEND_SCOPE);
        when(tokenRefresher.resolveAccessToken(TENANT, "mara", "workspace")).thenReturn("tok-2");

        assertThat(handler.availability(scope()).available()).isTrue();
        handler.share(request(Map.of("to", "ford@example.com")));

        verify(gmailApiClient).send(eq("tok-2"), any(MailMessage.class));
    }

    // ── helpers ────────────────────────────────────────────────────

    private void givenReady() {
        givenProviderConfigured(true);
        givenConnected(true);
        givenScopes(SEND_SCOPE + " openid email");
    }

    private void givenProviderConfigured(boolean configured) {
        when(configRegistry.resolve(TENANT, "google")).thenReturn(configured
                ? Optional.of(resolvedProvider("google"))
                : Optional.empty());
    }

    /** A real one, not a mock: {@link ResolvedOAuthProvider} is a record. */
    private static ResolvedOAuthProvider resolvedProvider(String providerId) {
        return new ResolvedOAuthProvider(
                new OAuthProviderConfig(
                        providerId, "google", "https://accounts.google.com/.well-known", null,
                        null, "client-id", "client-secret", List.of(SEND_SCOPE), Map.of()),
                mock(OAuthProvider.class));
    }

    private void givenConnected(boolean connected) {
        when(settingService.getDecryptedUserPassword(TENANT, "mara", TOKEN_KEY))
                .thenReturn(connected ? "stored-token" : null);
    }

    private void givenScopes(String granted) {
        when(settingService.getUserStringValue(TENANT, "mara", SCOPES_KEY)).thenReturn(granted);
    }

    private void givenSendFails(RuntimeException failure) {
        when(gmailApiClient.send(anyString(), any(MailMessage.class))).thenThrow(failure);
    }

    private ShareRequest request(Map<String, Object> values) {
        return new ShareRequest(scope(), values);
    }

    private static ShareScope scope() {
        return new ShareScope(
                MARA, TENANT, PROJECT,
                ShareSubject.ofDocument(DocumentRef.of(PROJECT, PATH)),
                DocumentDocument.builder()
                        .id("doc-1")
                        .tenantId(TENANT)
                        .projectId(PROJECT)
                        .path(PATH)
                        .title("Results")
                        .mimeType("text/markdown")
                        .size(CONTENT.length)
                        .build());
    }

    /** A subject with no document — nothing to attach. */
    private static ShareScope linkScope() {
        return new ShareScope(
                MARA, TENANT, PROJECT,
                new ShareSubject("Canyon test results", "https://example.com/hit",
                        "…the test is done…", null),
                null);
    }
}
