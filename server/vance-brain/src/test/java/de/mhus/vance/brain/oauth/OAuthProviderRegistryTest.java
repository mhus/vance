package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OAuthProviderRegistry} — bean discovery and
 * the duplicate-typeId fail-fast.
 */
class OAuthProviderRegistryTest {

    @Test
    void registers_providers_keyed_by_type_id() {
        OAuthProviderRegistry registry = new OAuthProviderRegistry(List.of(
                new StubProvider("oidc"),
                new StubProvider("slack")));

        assertThat(registry.list()).extracting(OAuthProvider::typeId)
                .containsExactlyInAnyOrder("oidc", "slack");
        assertThat(registry.find("oidc")).isPresent();
        assertThat(registry.find("slack")).isPresent();
    }

    @Test
    void find_returns_empty_for_unknown_type_id() {
        OAuthProviderRegistry registry = new OAuthProviderRegistry(List.of(
                new StubProvider("oidc")));

        assertThat(registry.find("unknown")).isEmpty();
    }

    @Test
    void empty_provider_list_yields_empty_registry() {
        OAuthProviderRegistry registry = new OAuthProviderRegistry(List.of());

        assertThat(registry.list()).isEmpty();
        assertThat(registry.find("oidc")).isEmpty();
    }

    @Test
    void duplicate_type_id_fails_fast_at_construction() {
        assertThatThrownBy(() -> new OAuthProviderRegistry(List.of(
                new StubProvider("oidc"),
                new StubProvider("oidc"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate OAuthProvider typeId 'oidc'");
    }

    @Test
    void list_returns_unmodifiable_snapshot() {
        OAuthProviderRegistry registry = new OAuthProviderRegistry(List.of(
                new StubProvider("oidc")));

        List<OAuthProvider> snapshot = registry.list();

        assertThatThrownBy(() -> snapshot.add(new StubProvider("slack")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Minimal {@link OAuthProvider} stub — exercises the registry only, never invoked. */
    private record StubProvider(String typeId) implements OAuthProvider {

        @Override
        public URI buildAuthorizeUri(OAuthProviderConfig cfg, OAuthInitContext ctx) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public OAuthTokenSet exchangeCode(OAuthProviderConfig cfg, String code, OAuthInitContext ctx) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public OAuthTokenSet refresh(OAuthProviderConfig cfg, String refreshToken) {
            throw new UnsupportedOperationException("stub");
        }
    }
}
