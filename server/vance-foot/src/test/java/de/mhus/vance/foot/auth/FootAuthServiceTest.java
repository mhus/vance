package de.mhus.vance.foot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.foot.config.FootConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FootAuthServiceTest {

    /** Records every mint call and returns a scripted response. */
    private static final class FakeClient implements AccessTokenClient {
        record Call(String httpBase, String tenant, String username, AccessTokenRequest request) {}

        final List<Call> calls = new ArrayList<>();
        AccessTokenResponse next = AccessTokenResponse.builder()
                .token("minted")
                .expiresAtTimestamp(0L)
                .build();

        @Override
        public AccessTokenResponse mint(String httpBase, String tenant, String username, AccessTokenRequest request) {
            calls.add(new Call(httpBase, tenant, username, request));
            return next;
        }
    }

    /** Scripted OAuth token endpoint. */
    private static final class FakeOAuthClient implements OAuthClient {
        final List<String> refreshed = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> revoked = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger refreshCalls = new AtomicInteger();
        boolean refuse;
        boolean revokeFails;
        long refreshDelayMs;

        @Override
        public Endpoints discover(String httpBase, String tenant) {
            return new Endpoints(httpBase + "/brain/" + tenant, "a", "t", "r");
        }

        @Override
        public Tokens exchangeCode(Endpoints e, String clientId, String code, String redirectUri, String verifier) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public Tokens refresh(String tokenEndpoint, String clientId, String refreshToken)
                throws OAuthRefusedException, InterruptedException {
            refreshed.add(refreshToken);
            refreshCalls.incrementAndGet();
            if (refreshDelayMs > 0) {
                Thread.sleep(refreshDelayMs);
            }
            if (refuse) {
                throw new OAuthRefusedException("invalid_grant");
            }
            return new Tokens("oauth-access-2", 3600, "rt-2");
        }

        @Override
        public boolean revoke(String revocationEndpoint, String clientId, String token) {
            revoked.add(token);
            return !revokeFails;
        }
    }

    private final FakeOAuthClient oauthClient = new FakeOAuthClient();

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private FootAuthService service(FootConfig config, VancePaths paths, AccessTokenClient client) {
        return service(config, paths, client, new OAuthBrowserLogin(oauthClient, url -> {}));
    }

    private FootAuthService service(
            FootConfig config, VancePaths paths, AccessTokenClient client, OAuthBrowserLogin browserLogin) {
        return new FootAuthService(
                config,
                paths,
                new AccessStore(),
                new ProjectBindingStore(),
                new ProjectBindingApplier(),
                client,
                oauthClient,
                browserLogin,
                now::get);
    }

    private VancePaths localPaths(Path dir) {
        VancePaths paths = new VancePaths(dir.toString(), null, null, "/nonexistent-cwd", "/nonexistent-home");
        return paths;
    }

    private FootConfig config(String... auth) {
        FootConfig config = new FootConfig();
        config.getBrain().setHttpBase("https://brain.example.com");
        config.getAuth().setTenant("acme");
        config.getAuth().setUsername("mike");
        config.getAuth().setPassword(null);
        return config;
    }

    @Test
    void acquire_usesCachedAccessTokenWithoutNetwork(@TempDir Path dir) throws Exception {
        AccessStore store = new AccessStore();
        AccessData data = new AccessData();
        data.setUsername("mike");
        data.setAccessToken("cached");
        data.setAccessExpiresAt(now.get() + 3_600_000L);
        store.save(dir, data);
        FakeClient client = new FakeClient();

        AccessTokenResponse token = service(config(), localPaths(dir), client).acquireAccessToken();

        assertThat(token.getToken()).isEqualTo("cached");
        assertThat(client.calls).isEmpty();
    }

    @Test
    void acquire_refreshesWhenAccessExpiredButRefreshValid(@TempDir Path dir) throws Exception {
        AccessStore store = new AccessStore();
        AccessData data = new AccessData();
        data.setUsername("mike");
        data.setAccessToken("stale");
        data.setAccessExpiresAt(now.get() - 1); // expired
        data.setRefreshToken("refresh-abc");
        data.setRefreshExpiresAt(now.get() + 30L * 24 * 3600 * 1000);
        store.save(dir, data);

        FakeClient client = new FakeClient();
        client.next = AccessTokenResponse.builder()
                .token("fresh")
                .expiresAtTimestamp(now.get() + 3_600_000L)
                .refreshToken("refresh-def")
                .refreshTokenExpiresAtTimestamp(now.get() + 40L * 24 * 3600 * 1000)
                .build();

        AccessTokenResponse token = service(config(), localPaths(dir), client).acquireAccessToken();

        assertThat(token.getToken()).isEqualTo("fresh");
        assertThat(client.calls).hasSize(1);
        assertThat(client.calls.get(0).request().getRefreshToken()).isEqualTo("refresh-abc");
        assertThat(client.calls.get(0).request().isRequestRefreshToken()).isTrue();
        // Rotated refresh token persisted back.
        Optional<AccessData> after = store.load(dir);
        assertThat(after).isPresent();
        assertThat(after.get().getAccessToken()).isEqualTo("fresh");
        assertThat(after.get().getRefreshToken()).isEqualTo("refresh-def");
    }

    @Test
    void acquire_fallsBackToConfiguredPasswordWithoutPersisting(@TempDir Path dir) throws Exception {
        FootConfig config = config();
        config.getAuth().setPassword("dev-secret");
        FakeClient client = new FakeClient();
        client.next = AccessTokenResponse.builder()
                .token("pw-token")
                .expiresAtTimestamp(0L)
                .build();

        AccessTokenResponse token = service(config, localPaths(dir), client).acquireAccessToken();

        assertThat(token.getToken()).isEqualTo("pw-token");
        assertThat(client.calls.get(0).request().getPassword()).isEqualTo("dev-secret");
        assertThat(client.calls.get(0).request().isRequestRefreshToken()).isFalse();
        // Password path never writes access.yaml.
        assertThat(new AccessStore().exists(dir)).isFalse();
    }

    @Test
    void acquire_noCredentials_throwsAuthRequired(@TempDir Path dir) {
        FootAuthService service = service(config(), localPaths(dir), new FakeClient());

        assertThatThrownBy(service::acquireAccessToken)
                .isInstanceOf(AuthRequiredException.class)
                .hasMessageContaining("/login");
    }

    @Test
    void login_persistsAccessAndBindingAndAppliesConfig(@TempDir Path dir) throws Exception {
        FootConfig config = config();
        VancePaths paths = localPaths(dir);
        FakeClient client = new FakeClient();
        client.next = AccessTokenResponse.builder()
                .token("acc")
                .expiresAtTimestamp(now.get() + 3_600_000L)
                .refreshToken("ref")
                .refreshTokenExpiresAtTimestamp(now.get() + 99_999L)
                .build();

        LoginResult result = service(config, paths, client)
                .login(new LoginRequest(
                        "https://brain.example.com", "wss://brain.example.com", "acme", "mike", "my-project", "pw"));

        // access.yaml written with the refresh token
        Optional<AccessData> access = new AccessStore().load(dir);
        assertThat(access).isPresent();
        assertThat(access.get().getAccessToken()).isEqualTo("acc");
        assertThat(access.get().getRefreshToken()).isEqualTo("ref");
        // project.eddie.yaml written
        Optional<ProjectBinding> binding = new ProjectBindingStore().load(dir);
        assertThat(binding).isPresent();
        assertThat(binding.get().getProject()).isEqualTo("my-project");
        assertThat(binding.get().getBrain().getWsBase()).isEqualTo("wss://brain.example.com");
        // binding applied to running config → arms auto-bootstrap
        assertThat(config.getBootstrap().getProjectId()).isEqualTo("my-project");
        assertThat(result.dir()).isEqualTo(dir);
    }

    @Test
    void logout_deletesAccessFile(@TempDir Path dir) {
        AccessStore store = new AccessStore();
        AccessData data = new AccessData();
        data.setAccessToken("x");
        store.save(dir, data);

        FootAuthService.LogoutResult result =
                service(config(), localPaths(dir), new FakeClient()).logout();

        assertThat(result.removed()).isTrue();
        assertThat(result.serverRevokeFailed()).isFalse();
        assertThat(store.exists(dir)).isFalse();
    }

    private static AccessData oauthLogin(long accessExpiresAt) {
        AccessData data = new AccessData();
        data.setUsername("mike");
        data.setAccessToken("oauth-access-1");
        data.setAccessExpiresAt(accessExpiresAt);
        data.setRefreshToken("rt-1");
        data.setAuthMethod("oauth");
        data.setOauthTokenEndpoint("https://brain.example.com/brain/acme/oauth/token");
        data.setOauthRevocationEndpoint("https://brain.example.com/brain/acme/oauth/revoke");
        data.setOauthClientId("vancetope-foot");
        return data;
    }

    @Test
    void acquire_oauthLogin_refreshesAtTheTokenEndpoint_andPersistsTheRotatedToken(@TempDir Path dir) throws Exception {
        AccessStore store = new AccessStore();
        store.save(dir, oauthLogin(now.get() - 1));
        FakeClient client = new FakeClient();

        AccessTokenResponse token = service(config(), localPaths(dir), client).acquireAccessToken();

        assertThat(token.getToken()).isEqualTo("oauth-access-2");
        assertThat(oauthClient.refreshed).containsExactly("rt-1");
        assertThat(client.calls).isEmpty();
        AccessData saved = store.load(dir).orElseThrow();
        assertThat(saved.getRefreshToken()).isEqualTo("rt-2");
        assertThat(saved.getAuthMethod()).isEqualTo("oauth");
    }

    @Test
    void acquire_oauthLogin_refused_asksForANewLogin(@TempDir Path dir) throws Exception {
        new AccessStore().save(dir, oauthLogin(now.get() - 1));
        oauthClient.refuse = true;

        assertThatThrownBy(() ->
                        service(config(), localPaths(dir), new FakeClient()).acquireAccessToken())
                .isInstanceOf(AuthRequiredException.class)
                .hasMessageContaining("--browser");
    }

    @Test
    void logout_oauthLogin_revokesAtTheServer(@TempDir Path dir) {
        new AccessStore().save(dir, oauthLogin(now.get() + 3_600_000L));

        assertThat(service(config(), localPaths(dir), new FakeClient()).logout())
                .isEqualTo(new FootAuthService.LogoutResult(true, false));
        assertThat(oauthClient.revoked).containsExactly("rt-1");
    }

    @Test
    void logout_oauthLogin_revokeNotConfirmed_isReported(@TempDir Path dir) {
        new AccessStore().save(dir, oauthLogin(now.get() + 3_600_000L));
        oauthClient.revokeFails = true;

        FootAuthService.LogoutResult result =
                service(config(), localPaths(dir), new FakeClient()).logout();

        assertThat(result.removed()).isTrue();
        assertThat(result.serverRevokeFailed()).isTrue();
        assertThat(new AccessStore().exists(dir)).isFalse();
    }

    @Test
    void acquire_oauthLogin_concurrentClients_refreshOnlyOnce(@TempDir Path dir) throws Exception {
        new AccessStore().save(dir, oauthLogin(now.get() - 1));
        oauthClient.refreshDelayMs = 200;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<AccessTokenResponse> acquire =
                    () -> service(config(), localPaths(dir), new FakeClient()).acquireAccessToken();
            Future<AccessTokenResponse> first = pool.submit(acquire);
            Future<AccessTokenResponse> second = pool.submit(acquire);

            assertThat(first.get(10, TimeUnit.SECONDS).getToken()).isEqualTo("oauth-access-2");
            assertThat(second.get(10, TimeUnit.SECONDS).getToken()).isEqualTo("oauth-access-2");
        } finally {
            pool.shutdownNow();
        }
        // The second client waited for the lock, re-read the file and took the
        // fresh token instead of spending the already rotated refresh token.
        assertThat(oauthClient.refreshCalls.get()).isEqualTo(1);
        assertThat(new AccessStore().load(dir).orElseThrow().getRefreshToken()).isEqualTo("rt-2");
    }

    /** A browser login that skips discovery and the browser, answering with fixed tokens. */
    private OAuthBrowserLogin scriptedBrowserLogin(String refreshToken) {
        return new OAuthBrowserLogin(oauthClient, url -> {}) {
            @Override
            public Result run(
                    String httpBase,
                    String tenant,
                    boolean allowInsecureTransport,
                    Consumer<String> showUrl,
                    Duration timeout) {
                return new Result(
                        new OAuthClient.Endpoints(
                                httpBase + "/brain/" + tenant,
                                httpBase + "/brain/" + tenant + "/oauth/authorize",
                                httpBase + "/brain/" + tenant + "/oauth/token",
                                httpBase + "/brain/" + tenant + "/oauth/revoke"),
                        new OAuthClient.Tokens("oauth-access-new", 3600, refreshToken),
                        "mike");
            }
        };
    }

    @Test
    void loginWithBrowser_revokesTheReplacedBrowserLogin(@TempDir Path dir) throws Exception {
        new AccessStore().save(dir, oauthLogin(now.get() + 3_600_000L));

        service(config(), localPaths(dir), new FakeClient(), scriptedBrowserLogin("rt-new"))
                .loginWithBrowser(
                        "https://brain.example.com",
                        "wss://brain.example.com",
                        "acme",
                        null,
                        url -> {},
                        Duration.ofMinutes(1));

        assertThat(oauthClient.revoked).containsExactly("rt-1");
        AccessData saved = new AccessStore().load(dir).orElseThrow();
        assertThat(saved.getRefreshToken()).isEqualTo("rt-new");
        assertThat(saved.getAuthMethod()).isEqualTo("oauth");
    }

    @Test
    void loginWithBrowser_withoutPreviousBrowserLogin_revokesNothing(@TempDir Path dir) throws Exception {
        AccessData passwordLogin = new AccessData();
        passwordLogin.setAccessToken("pw-access");
        passwordLogin.setRefreshToken("pw-refresh");
        new AccessStore().save(dir, passwordLogin);

        service(config(), localPaths(dir), new FakeClient(), scriptedBrowserLogin("rt-new"))
                .loginWithBrowser(
                        "https://brain.example.com",
                        "wss://brain.example.com",
                        "acme",
                        null,
                        url -> {},
                        Duration.ofMinutes(1));

        assertThat(oauthClient.revoked).isEmpty();
        assertThat(new AccessStore().load(dir).orElseThrow().getRefreshToken()).isEqualTo("rt-new");
    }
}
