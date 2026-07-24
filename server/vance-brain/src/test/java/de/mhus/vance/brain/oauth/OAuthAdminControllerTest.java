package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.oauth.OAuthProviderAdminDto;
import de.mhus.vance.api.oauth.OAuthProviderWriteRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.SubjectType;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-logic tests for {@link OAuthAdminController}: list/get/
 * upsert/delete + the clientSecret semantics (null=leave, ""=remove,
 * non-empty=upsert).
 */
class OAuthAdminControllerTest {

    private static final String TENANT = "acme";
    private static final String ADMIN_USER = "marvin.acme";
    private static final String PROVIDER = "slack";
    private static final String DOC_PATH = "_vance/oauth/slack.yaml";
    private static final String SECRET_KEY = "oauth.slack.client_secret";
    private static final String TENANT_PROJECT = "_tenant";

    private OAuthProviderLoader loader;
    private OAuthConfigRegistry configRegistry;
    private DocumentService documentService;
    private SettingService settingService;
    private RequestAuthority authority;
    private OAuthAdminController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        loader = mock(OAuthProviderLoader.class);
        configRegistry = mock(OAuthConfigRegistry.class);
        documentService = mock(DocumentService.class);
        settingService = mock(SettingService.class);
        authority = mock(RequestAuthority.class);
        when(authority.contextOf(any(HttpServletRequest.class)))
                .thenReturn(new SecurityContext(SubjectType.USER, ADMIN_USER, TENANT, List.of()));
        controller = new OAuthAdminController(
                loader, configRegistry, documentService, settingService, authority);
        request = mock(HttpServletRequest.class);
    }

    // ─────── List + Get ───────

    @Test
    void list_returns_entries_sorted_by_provider_id() {
        when(configRegistry.list(TENANT)).thenReturn(List.of(
                resolved("slack", "slack"),
                resolved("keycloak", "oidc")));
        stubYamlBody("slack", "type: slack\n");
        stubYamlBody("keycloak", "type: oidc\n");
        stubSecret("slack", "real-secret");
        stubSecret("keycloak", null);

        List<OAuthProviderAdminDto> out = controller.listProviders(TENANT, request);

        assertThat(out).extracting(OAuthProviderAdminDto::getProviderId)
                .containsExactly("keycloak", "slack");
        assertThat(out).filteredOn(e -> "slack".equals(e.getProviderId()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getTypeId()).isEqualTo("slack");
                    assertThat(e.isHasClientSecret()).isTrue();
                    assertThat(e.getYaml()).contains("type: slack");
                });
        assertThat(out).filteredOn(e -> "keycloak".equals(e.getProviderId()))
                .singleElement()
                .satisfies(e -> assertThat(e.isHasClientSecret()).isFalse());
    }

    @Test
    void get_returns_404_when_missing() {
        when(configRegistry.resolve(TENANT, PROVIDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getProvider(TENANT, PROVIDER, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No OAuth provider");
    }

    @Test
    void get_omits_secret_value_but_reports_presence() {
        when(configRegistry.resolve(TENANT, PROVIDER)).thenReturn(Optional.of(resolved(PROVIDER, "slack")));
        stubYamlBody(PROVIDER, "type: slack\nclientId: c\n");
        stubSecret(PROVIDER, "the-real-secret");

        OAuthProviderAdminDto dto = controller.getProvider(TENANT, PROVIDER, request);

        // DTO carries no field for the secret value — only the presence flag.
        assertThat(dto.isHasClientSecret()).isTrue();
        assertThat(dto.getClientId()).isEqualTo("client-slack");
    }

    // ─────── Upsert — Create ───────

    @Test
    void upsert_creates_document_and_secret_when_absent() {
        when(documentService.findByPath(eq(TENANT), eq(TENANT_PROJECT), eq(DOC_PATH)))
                .thenReturn(Optional.empty());
        when(documentService.createText(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new DocumentDocument());
        when(configRegistry.resolve(TENANT, PROVIDER))
                .thenReturn(Optional.of(resolved(PROVIDER, "slack")));
        stubYamlBody(PROVIDER, "type: slack\n");
        stubSecret(PROVIDER, "secret-1");

        OAuthProviderWriteRequest body = OAuthProviderWriteRequest.builder()
                .yaml("type: slack\nclientId: c\nauthorizeUrl: https://a\ntokenUrl: https://t\n")
                .clientSecret("secret-1")
                .build();

        ResponseEntity<OAuthProviderAdminDto> resp =
                controller.upsertProvider(TENANT, PROVIDER, body, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(documentService).createText(eq(TENANT), eq(TENANT_PROJECT), eq(DOC_PATH),
                any(), any(), eq(body.getYaml()), eq(ADMIN_USER), any());
        verify(settingService).setEncryptedPassword(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(TENANT_PROJECT),
                eq(SECRET_KEY), eq("secret-1"));
        verify(configRegistry).refreshOne(TENANT, PROVIDER);
    }

    // ─────── Upsert — Update (YAML-only) ───────

    @Test
    void upsert_yaml_only_leaves_secret_alone() {
        DocumentDocument existing = new DocumentDocument();
        existing.setId("doc-1");
        when(documentService.findByPath(eq(TENANT), eq(TENANT_PROJECT), eq(DOC_PATH)))
                .thenReturn(Optional.of(existing));
        when(configRegistry.resolve(TENANT, PROVIDER))
                .thenReturn(Optional.of(resolved(PROVIDER, "slack")));
        stubYamlBody(PROVIDER, "type: slack\n");
        stubSecret(PROVIDER, "unchanged");

        OAuthProviderWriteRequest body = OAuthProviderWriteRequest.builder()
                .yaml("type: slack\nclientId: c\nauthorizeUrl: https://a\ntokenUrl: https://t\n")
                .clientSecret(null) // explicit: leave the existing secret
                .build();

        ResponseEntity<OAuthProviderAdminDto> resp =
                controller.upsertProvider(TENANT, PROVIDER, body, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(documentService).update(eq("doc-1"), any(), any(),
                eq(body.getYaml()), any(), any());
        verify(settingService, never()).setEncryptedPassword(any(), any(), any(), any(), any());
        verify(settingService, never()).delete(eq(TENANT), any(), any(), eq(SECRET_KEY));
    }

    // ─────── Upsert — Empty-string secret removes it ───────

    @Test
    void upsert_empty_secret_string_removes_the_secret_setting() {
        DocumentDocument existing = new DocumentDocument();
        existing.setId("doc-1");
        when(documentService.findByPath(eq(TENANT), eq(TENANT_PROJECT), eq(DOC_PATH)))
                .thenReturn(Optional.of(existing));
        when(configRegistry.resolve(TENANT, PROVIDER))
                .thenReturn(Optional.of(resolved(PROVIDER, "slack")));
        stubYamlBody(PROVIDER, "type: slack\n");
        stubSecret(PROVIDER, null);

        OAuthProviderWriteRequest body = OAuthProviderWriteRequest.builder()
                .yaml("type: slack\nclientId: c\nauthorizeUrl: https://a\ntokenUrl: https://t\n")
                .clientSecret("")
                .build();

        controller.upsertProvider(TENANT, PROVIDER, body, request);

        verify(settingService).delete(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(TENANT_PROJECT),
                eq(SECRET_KEY));
        verify(settingService, never()).setEncryptedPassword(any(), any(), any(), any(), any());
    }

    // ─────── Upsert validation ───────

    @Test
    void upsert_rejects_blank_yaml() {
        OAuthProviderWriteRequest body = OAuthProviderWriteRequest.builder()
                .yaml("")
                .build();

        assertThatThrownBy(() -> controller.upsertProvider(TENANT, PROVIDER, body, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("non-empty");
        verify(documentService, never()).createText(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void upsert_rejects_invalid_yaml_with_loader_message() {
        when(loader.validateYaml(eq(PROVIDER), any())).thenThrow(
                new OAuthProviderLoader.OAuthProviderParseException("missing field 'type'",
                        new RuntimeException()));

        OAuthProviderWriteRequest body = OAuthProviderWriteRequest.builder()
                .yaml("clientId: c\n")
                .build();

        assertThatThrownBy(() -> controller.upsertProvider(TENANT, PROVIDER, body, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("missing field 'type'");
        verify(documentService, never()).createText(any(), any(), any(), any(), any(), any(), any(), any());
        verify(documentService, never()).update(any(), any(), any(), any(), any(), any());
    }

    // ─────── Delete ───────

    @Test
    void delete_drops_document_secret_and_refreshes_registry() {
        DocumentDocument existing = new DocumentDocument();
        existing.setId("doc-1");
        when(documentService.findByPath(eq(TENANT), eq(TENANT_PROJECT), eq(DOC_PATH)))
                .thenReturn(Optional.of(existing));

        ResponseEntity<Void> resp = controller.deleteProvider(TENANT, PROVIDER, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(documentService).delete(eq("doc-1"), any(de.mhus.vance.shared.permission.WriteActor.class));
        verify(settingService, times(1)).delete(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(TENANT_PROJECT),
                eq(SECRET_KEY));
        verify(configRegistry).refreshOne(TENANT, PROVIDER);
    }

    @Test
    void delete_returns_404_when_missing() {
        when(documentService.findByPath(eq(TENANT), eq(TENANT_PROJECT), eq(DOC_PATH)))
                .thenReturn(Optional.empty());

        ResponseEntity<Void> resp = controller.deleteProvider(TENANT, PROVIDER, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(documentService, never()).delete(any(), any(de.mhus.vance.shared.permission.WriteActor.class));
        verify(configRegistry, never()).refreshOne(any(), any());
    }

    // ─────── Helpers ───────

    private void stubYamlBody(String providerId, String yaml) {
        when(documentService.lookupCascade(eq(TENANT), eq(TENANT_PROJECT),
                eq(OAuthProviderLoader.pathFor(providerId))))
                .thenReturn(Optional.of(new LookupResult(
                        OAuthProviderLoader.pathFor(providerId),
                        yaml, LookupResult.Source.VANCE, null)));
    }

    private void stubSecret(String providerId, String secret) {
        when(settingService.getDecryptedPassword(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(TENANT_PROJECT),
                eq("oauth." + providerId + ".client_secret")))
                .thenReturn(secret);
    }

    private static ResolvedOAuthProvider resolved(String providerId, String typeId) {
        return new ResolvedOAuthProvider(
                new OAuthProviderConfig(
                        providerId, typeId, null,
                        "https://provider.example/authorize",
                        "https://provider.example/token",
                        "client-" + providerId,
                        "secret-" + providerId,
                        new ArrayList<>(),
                        new LinkedHashMap<>()),
                new StubProvider(typeId));
    }

    private record StubProvider(String typeId) implements OAuthProvider {
        @Override public URI buildAuthorizeUri(OAuthProviderConfig c, OAuthInitContext ctx) {
            throw new UnsupportedOperationException();
        }
        @Override public OAuthTokenSet exchangeCode(OAuthProviderConfig c, String code, OAuthInitContext ctx) {
            throw new UnsupportedOperationException();
        }
        @Override public OAuthTokenSet refresh(OAuthProviderConfig c, String refresh) {
            throw new UnsupportedOperationException();
        }
    }
}
