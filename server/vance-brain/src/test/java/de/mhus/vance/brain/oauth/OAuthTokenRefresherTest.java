package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.settings.SettingService;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OAuthTokenRefresher} — cached-vs-refresh
 * decisions, persistence of new tokens, expired-exception mapping,
 * and the per-(tenant,user,provider) lock that prevents racing
 * refreshes from invalidating each other.
 */
class OAuthTokenRefresherTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-19T12:00:00Z");
    private static final String TENANT = "acme";
    private static final String USER = "wile.coyote";
    private static final String PROVIDER = "slack";
    private static final String USER_REF = "_user_" + USER;
    private static final String ACCESS_KEY = "oauth.slack.access_token";
    private static final String REFRESH_KEY = "oauth.slack.refresh_token";
    private static final String EXPIRES_KEY = "oauth.slack.expires_at";

    private OAuthConfigRegistry configRegistry;
    private SettingService settingService;
    private OAuthTokenRefresher refresher;
    private RecordingProvider provider;

    @BeforeEach
    void setUp() {
        configRegistry = mock(OAuthConfigRegistry.class);
        settingService = mock(SettingService.class);
        provider = new RecordingProvider();
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        refresher = new OAuthTokenRefresher(configRegistry, settingService, clock);

        when(configRegistry.resolve(TENANT, PROVIDER)).thenReturn(Optional.of(
                new ResolvedOAuthProvider(providerConfig("real-secret"), provider)));
    }

    // ─────── Cached path (no refresh) ───────

    @Test
    void returns_cached_token_when_not_near_expiry() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.plusSeconds(600).toString()); // 10 min future
        when(settingService.getDecryptedUserPassword(TENANT, USER, ACCESS_KEY))
                .thenReturn("cached-access-token");

        String token = refresher.resolveAccessToken(TENANT, USER, PROVIDER);

        assertThat(token).isEqualTo("cached-access-token");
        assertThat(provider.refreshCount.get()).isZero();
    }

    @Test
    void returns_cached_token_when_expires_at_missing() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY)).thenReturn(null);
        when(settingService.getDecryptedUserPassword(TENANT, USER, ACCESS_KEY))
                .thenReturn("never-expiring-token");

        String token = refresher.resolveAccessToken(TENANT, USER, PROVIDER);

        assertThat(token).isEqualTo("never-expiring-token");
        assertThat(provider.refreshCount.get()).isZero();
    }

    @Test
    void normalises_provider_id_for_setting_keys() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.plusSeconds(600).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, ACCESS_KEY))
                .thenReturn("cached-token");

        String token = refresher.resolveAccessToken(TENANT, USER, "Slack");

        assertThat(token).isEqualTo("cached-token");
    }

    // ─────── Refresh path ───────

    @Test
    void refreshes_when_token_expires_within_buffer() {
        // 30 seconds in the future — inside the 60s refresh buffer.
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.plusSeconds(30).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, REFRESH_KEY))
                .thenReturn("stored-refresh-token");
        provider.next = new OAuthTokenSet("fresh-access", "fresh-refresh",
                FIXED_NOW.plusSeconds(3600), Map.of("scope", "channels:read"));

        String token = refresher.resolveAccessToken(TENANT, USER, PROVIDER);

        assertThat(token).isEqualTo("fresh-access");
        assertThat(provider.refreshCount.get()).isEqualTo(1);
        assertThat(provider.lastRefreshToken).isEqualTo("stored-refresh-token");

        verify(settingService).setEncryptedPassword(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(USER_REF),
                eq(ACCESS_KEY), eq("fresh-access"));
        verify(settingService).setEncryptedPassword(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(USER_REF),
                eq(REFRESH_KEY), eq("fresh-refresh"));
        verify(settingService).set(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(USER_REF),
                eq(EXPIRES_KEY), eq(FIXED_NOW.plusSeconds(3600).toString()),
                eq(SettingType.STRING), any());
    }

    @Test
    void refreshes_when_token_already_expired() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.minusSeconds(60).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, REFRESH_KEY))
                .thenReturn("stored-refresh-token");
        provider.next = new OAuthTokenSet("fresh-access", "fresh-refresh",
                FIXED_NOW.plusSeconds(3600), Map.of());

        String token = refresher.resolveAccessToken(TENANT, USER, PROVIDER);

        assertThat(token).isEqualTo("fresh-access");
        assertThat(provider.refreshCount.get()).isEqualTo(1);
    }

    @Test
    void keeps_existing_refresh_token_when_provider_omits_one() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.minusSeconds(60).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, REFRESH_KEY))
                .thenReturn("stored-refresh-token");
        provider.next = new OAuthTokenSet("fresh-access",
                /*refreshToken*/ null,
                FIXED_NOW.plusSeconds(3600), Map.of());

        refresher.resolveAccessToken(TENANT, USER, PROVIDER);

        verify(settingService, never()).setEncryptedPassword(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(USER_REF),
                eq(REFRESH_KEY), any());
    }

    @Test
    void persists_extra_claims_as_scopes_and_extra_blob() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.minusSeconds(60).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, REFRESH_KEY))
                .thenReturn("rt");
        provider.next = new OAuthTokenSet("a", "b", FIXED_NOW.plusSeconds(3600),
                Map.of("scope", "openid email", "team_id", "T123"));

        refresher.resolveAccessToken(TENANT, USER, PROVIDER);

        verify(settingService).set(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(USER_REF),
                eq("oauth.slack.scopes"), eq("openid email"),
                eq(SettingType.STRING), any());
        verify(settingService).set(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(USER_REF),
                eq("oauth.slack.extra"),
                org.mockito.ArgumentMatchers.contains("team_id"),
                eq(SettingType.STRING), any());
    }

    // ─────── Expired exceptions ───────

    @Test
    void throws_expired_when_no_refresh_token() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.minusSeconds(60).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, REFRESH_KEY))
                .thenReturn(null);

        assertThatThrownBy(() -> refresher.resolveAccessToken(TENANT, USER, PROVIDER))
                .isInstanceOf(OAuthExpiredException.class)
                .hasMessageContaining("no refresh token")
                .extracting("providerId").isEqualTo(PROVIDER);
        assertThat(provider.refreshCount.get()).isZero();
    }

    @Test
    void throws_expired_when_provider_config_missing() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.minusSeconds(60).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, REFRESH_KEY))
                .thenReturn("rt");
        when(configRegistry.resolve(TENANT, PROVIDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refresher.resolveAccessToken(TENANT, USER, PROVIDER))
                .isInstanceOf(OAuthExpiredException.class)
                .hasMessageContaining("no longer configured");
    }

    @Test
    void throws_expired_when_client_secret_empty() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.minusSeconds(60).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, REFRESH_KEY))
                .thenReturn("rt");
        when(configRegistry.resolve(TENANT, PROVIDER)).thenReturn(Optional.of(
                new ResolvedOAuthProvider(providerConfig(""), provider)));

        assertThatThrownBy(() -> refresher.resolveAccessToken(TENANT, USER, PROVIDER))
                .isInstanceOf(OAuthExpiredException.class)
                .hasMessageContaining("no client secret");
    }

    @Test
    void throws_expired_when_provider_rejects_refresh() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.minusSeconds(60).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, REFRESH_KEY))
                .thenReturn("rt");
        provider.throwOnRefresh = new OAuthFlowException(PROVIDER, "invalid_grant");

        assertThatThrownBy(() -> refresher.resolveAccessToken(TENANT, USER, PROVIDER))
                .isInstanceOf(OAuthExpiredException.class)
                .hasMessageContaining("provider rejected refresh")
                .hasMessageContaining("invalid_grant");
    }

    @Test
    void throws_expired_when_provider_throws_runtime() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.minusSeconds(60).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, REFRESH_KEY))
                .thenReturn("rt");
        provider.throwOnRefreshRuntime = new IllegalStateException("connection reset");

        assertThatThrownBy(() -> refresher.resolveAccessToken(TENANT, USER, PROVIDER))
                .isInstanceOf(OAuthExpiredException.class)
                .hasMessageContaining("connection reset");
    }

    // ─────── Per-(tenant,user,provider) lock ───────

    @Test
    void parallel_callers_only_trigger_one_refresh() throws Exception {
        // AtomicReferences make the simulated state transition (refresh
        // persists → next read sees fresh values) thread-safe across the
        // 8 worker threads. Plain Mockito when().thenReturn() in a
        // callback is publish-visible only on next-call evaluation —
        // good enough for sequential tests, race-prone for parallel.
        Instant freshExpiry = FIXED_NOW.plusSeconds(3600);
        java.util.concurrent.atomic.AtomicReference<String> expiresAtRef =
                new java.util.concurrent.atomic.AtomicReference<>(
                        FIXED_NOW.minusSeconds(60).toString());
        java.util.concurrent.atomic.AtomicReference<String> accessTokenRef =
                new java.util.concurrent.atomic.AtomicReference<>(null);

        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenAnswer(inv -> expiresAtRef.get());
        when(settingService.getDecryptedUserPassword(TENANT, USER, ACCESS_KEY))
                .thenAnswer(inv -> accessTokenRef.get());
        when(settingService.getDecryptedUserPassword(TENANT, USER, REFRESH_KEY))
                .thenReturn("rt");

        provider.next = new OAuthTokenSet("fresh-access", "fresh-refresh",
                freshExpiry, Map.of());

        // Atomic publish: the moment persistTokens writes expires_at to
        // the future, sibling readers see the new value AND the new
        // access token together — no torn read possible.
        when(settingService.set(eq(TENANT), eq(SettingService.SCOPE_PROJECT),
                eq(USER_REF), eq(EXPIRES_KEY), any(), eq(SettingType.STRING), any()))
                .thenAnswer(inv -> {
                    expiresAtRef.set(freshExpiry.toString());
                    accessTokenRef.set("fresh-access");
                    return null;
                });

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch barrier = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    results.add(refresher.resolveAccessToken(TENANT, USER, PROVIDER));
                } catch (Exception ex) {
                    results.add("ERR:" + ex.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        barrier.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).isTrue();
        assertThat(results).hasSize(callers).allMatch("fresh-access"::equals);
        assertThat(provider.refreshCount.get())
                .as("with the (tenant,user,provider) lock + double-check, only one thread refreshes")
                .isEqualTo(1);
        verify(settingService, times(1)).setEncryptedPassword(eq(TENANT),
                eq(SettingService.SCOPE_PROJECT), eq(USER_REF),
                eq(ACCESS_KEY), eq("fresh-access"));
    }

    @Test
    void different_providers_do_not_serialize_each_other() {
        when(settingService.getUserStringValue(TENANT, USER, EXPIRES_KEY))
                .thenReturn(FIXED_NOW.minusSeconds(60).toString());
        when(settingService.getUserStringValue(TENANT, USER, "oauth.github.expires_at"))
                .thenReturn(FIXED_NOW.plusSeconds(3600).toString());
        when(settingService.getDecryptedUserPassword(TENANT, USER, "oauth.github.access_token"))
                .thenReturn("github-cached");
        when(configRegistry.resolve(TENANT, "github")).thenReturn(Optional.of(
                new ResolvedOAuthProvider(providerConfig("real-secret"), new RecordingProvider())));

        String github = refresher.resolveAccessToken(TENANT, USER, "github");

        // Cached path — should not have touched the slack provider at all.
        assertThat(github).isEqualTo("github-cached");
        assertThat(provider.refreshCount.get()).isZero();
    }

    // ─────── Helpers ───────

    private static OAuthProviderConfig providerConfig(String clientSecret) {
        return new OAuthProviderConfig(
                PROVIDER, "slack",
                /*discoveryUrl*/ null,
                "https://slack.com/oauth/v2/authorize",
                "https://slack.com/api/oauth.v2.access",
                "client-id",
                clientSecret,
                new ArrayList<>(),
                new LinkedHashMap<>());
    }

    private static class RecordingProvider implements OAuthProvider {
        final AtomicInteger refreshCount = new AtomicInteger();
        @org.jspecify.annotations.Nullable String lastRefreshToken;
        @org.jspecify.annotations.Nullable OAuthTokenSet next;
        @org.jspecify.annotations.Nullable OAuthFlowException throwOnRefresh;
        @org.jspecify.annotations.Nullable RuntimeException throwOnRefreshRuntime;

        @Override public String typeId() { return "slack"; }

        @Override public URI buildAuthorizeUri(OAuthProviderConfig c, OAuthInitContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override public OAuthTokenSet exchangeCode(OAuthProviderConfig c, String code, OAuthInitContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override public OAuthTokenSet refresh(OAuthProviderConfig c, String refreshToken) {
            lastRefreshToken = refreshToken;
            refreshCount.incrementAndGet();
            if (throwOnRefresh != null) throw throwOnRefresh;
            if (throwOnRefreshRuntime != null) throw throwOnRefreshRuntime;
            return next != null ? next : new OAuthTokenSet("default-access", null, null, Map.of());
        }
    }
}
