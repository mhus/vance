package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OAuthConfigRegistry}: lazy-bootstrap per
 * tenant, refresh, unknown-typeId-skip, tenant isolation.
 *
 * <p>{@link OAuthProviderLoader} is mocked — registry logic is tested
 * in isolation from YAML parsing and Mongo.
 */
class OAuthConfigRegistryTest {

    private static final String TENANT_A = "acme";
    private static final String TENANT_B = "globex";

    private OAuthProviderLoader loader;
    private OAuthProviderRegistry providerRegistry;
    private OAuthConfigRegistry registry;

    @BeforeEach
    void setUp() {
        loader = mock(OAuthProviderLoader.class);
        providerRegistry = new OAuthProviderRegistry(List.of(
                new StubProvider("oidc"),
                new StubProvider("slack")));
        registry = new OAuthConfigRegistry(loader, providerRegistry);
    }

    @Test
    void resolve_lazy_bootstraps_tenant_on_first_access() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(
                config("keycloak", "oidc"),
                config("slack-acme", "slack")));

        Optional<ResolvedOAuthProvider> hit = registry.resolve(TENANT_A, "keycloak");

        assertThat(hit).isPresent();
        assertThat(hit.get().providerId()).isEqualTo("keycloak");
        assertThat(hit.get().typeId()).isEqualTo("oidc");
    }

    @Test
    void list_returns_every_provider_in_loader_order() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(
                config("keycloak", "oidc"),
                config("slack-acme", "slack")));

        List<ResolvedOAuthProvider> all = registry.list(TENANT_A);

        assertThat(all).extracting(ResolvedOAuthProvider::providerId)
                .containsExactly("keycloak", "slack-acme");
    }

    @Test
    void resolve_unknown_provider_returns_empty() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(config("keycloak", "oidc")));

        assertThat(registry.resolve(TENANT_A, "missing")).isEmpty();
    }

    @Test
    void resolve_normalises_provider_id() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(config("keycloak", "oidc")));

        assertThat(registry.resolve(TENANT_A, "Keycloak")).isPresent();
        assertThat(registry.resolve(TENANT_A, "  KEYCLOAK  ")).isPresent();
    }

    @Test
    void unknown_type_id_skips_entry_at_bootstrap() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(
                config("keycloak", "oidc"),
                config("custom-bot", "no-such-type")));

        List<ResolvedOAuthProvider> all = registry.list(TENANT_A);

        assertThat(all).extracting(ResolvedOAuthProvider::providerId)
                .containsExactly("keycloak");
    }

    @Test
    void bootstrap_is_idempotent_and_replaces_scope() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(config("keycloak", "oidc")));
        registry.bootstrapTenant(TENANT_A);

        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(config("slack-acme", "slack")));
        registry.bootstrapTenant(TENANT_A);

        assertThat(registry.list(TENANT_A)).extracting(ResolvedOAuthProvider::providerId)
                .containsExactly("slack-acme");
    }

    @Test
    void refreshOne_replaces_existing_entry() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(config("slack-acme", "slack")));
        registry.bootstrapTenant(TENANT_A);
        when(loader.load(eq(TENANT_A), eq("slack-acme"))).thenReturn(Optional.of(
                configWithClientId("slack-acme", "slack", "new-client-id")));

        boolean ok = registry.refreshOne(TENANT_A, "slack-acme");

        assertThat(ok).isTrue();
        assertThat(registry.resolve(TENANT_A, "slack-acme"))
                .map(rp -> rp.config().clientId()).contains("new-client-id");
    }

    @Test
    void refreshOne_removes_entry_when_deleted() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(config("slack-acme", "slack")));
        registry.bootstrapTenant(TENANT_A);
        when(loader.load(eq(TENANT_A), eq("slack-acme"))).thenReturn(Optional.empty());

        boolean ok = registry.refreshOne(TENANT_A, "slack-acme");

        assertThat(ok).isFalse();
        assertThat(registry.resolve(TENANT_A, "slack-acme")).isEmpty();
    }

    @Test
    void refreshOne_parse_error_drops_entry() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(config("slack-acme", "slack")));
        registry.bootstrapTenant(TENANT_A);
        when(loader.load(eq(TENANT_A), eq("slack-acme"))).thenThrow(
                new OAuthProviderLoader.OAuthProviderParseException("bad yaml", new RuntimeException()));

        boolean ok = registry.refreshOne(TENANT_A, "slack-acme");

        assertThat(ok).isFalse();
        assertThat(registry.resolve(TENANT_A, "slack-acme")).isEmpty();
    }

    @Test
    void refreshOne_drops_entry_when_typeId_unknown_after_refresh() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(config("slack-acme", "slack")));
        registry.bootstrapTenant(TENANT_A);
        when(loader.load(eq(TENANT_A), eq("slack-acme"))).thenReturn(Optional.of(
                config("slack-acme", "still-no-such-type")));

        boolean ok = registry.refreshOne(TENANT_A, "slack-acme");

        assertThat(ok).isFalse();
        assertThat(registry.resolve(TENANT_A, "slack-acme")).isEmpty();
    }

    @Test
    void refreshOne_without_scope_returns_false() {
        // Tenant scope was never bootstrapped — refresh is irrelevant.
        assertThat(registry.refreshOne(TENANT_A, "anything")).isFalse();
    }

    @Test
    void unloadTenant_drops_scope() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(config("keycloak", "oidc")));
        registry.bootstrapTenant(TENANT_A);

        registry.unloadTenant(TENANT_A);

        // After unload + lazy re-bootstrap the registry sees whatever the
        // loader currently returns — we drive the loader to empty for
        // a clean assert.
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of());

        assertThat(registry.list(TENANT_A)).isEmpty();
    }

    @Test
    void tenants_are_isolated() {
        when(loader.loadAll(eq(TENANT_A))).thenReturn(List.of(config("keycloak", "oidc")));
        when(loader.loadAll(eq(TENANT_B))).thenReturn(List.of(config("slack-globex", "slack")));

        assertThat(registry.list(TENANT_A)).extracting(ResolvedOAuthProvider::providerId)
                .containsExactly("keycloak");
        assertThat(registry.list(TENANT_B)).extracting(ResolvedOAuthProvider::providerId)
                .containsExactly("slack-globex");
    }

    // ─────── Helpers ───────

    private static OAuthProviderConfig config(String providerId, String typeId) {
        return configWithClientId(providerId, typeId, "client-" + providerId);
    }

    private static OAuthProviderConfig configWithClientId(
            String providerId, String typeId, String clientId) {
        return new OAuthProviderConfig(
                providerId,
                typeId,
                /*discoveryUrl*/ "oidc".equals(typeId) ? "https://x/.well-known" : null,
                /*authorizeUrl*/ "oidc".equals(typeId) ? null : "https://x/authorize",
                /*tokenUrl*/ "oidc".equals(typeId) ? null : "https://x/token",
                clientId,
                /*clientSecret*/ "secret-" + providerId,
                new ArrayList<>(),
                new LinkedHashMap<>());
    }

    private record StubProvider(String typeId) implements OAuthProvider {
        @Override public URI buildAuthorizeUri(OAuthProviderConfig c, OAuthInitContext ctx) {
            return URI.create("https://stub/authorize");
        }
        @Override public OAuthTokenSet exchangeCode(OAuthProviderConfig c, String code, OAuthInitContext ctx) {
            throw new UnsupportedOperationException("stub");
        }
        @Override public OAuthTokenSet refresh(OAuthProviderConfig c, String refresh) {
            throw new UnsupportedOperationException("stub");
        }
    }
}
