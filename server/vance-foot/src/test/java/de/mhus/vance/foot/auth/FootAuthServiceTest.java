package de.mhus.vance.foot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.access.AccessTokenRequest;
import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.foot.config.FootConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FootAuthServiceTest {

    /** Records every mint call and returns a scripted response. */
    private static final class FakeClient implements AccessTokenClient {
        record Call(String httpBase, String tenant, String username, AccessTokenRequest request) {}
        final List<Call> calls = new ArrayList<>();
        AccessTokenResponse next = AccessTokenResponse.builder().token("minted").expiresAtTimestamp(0L).build();

        @Override
        public AccessTokenResponse mint(String httpBase, String tenant, String username,
                                        AccessTokenRequest request) {
            calls.add(new Call(httpBase, tenant, username, request));
            return next;
        }
    }

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private FootAuthService service(FootConfig config, VancePaths paths, AccessTokenClient client) {
        return new FootAuthService(config, paths, new AccessStore(), new ProjectBindingStore(),
                new ProjectBindingApplier(), client, now::get);
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
        client.next = AccessTokenResponse.builder().token("pw-token").expiresAtTimestamp(0L).build();

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
                .token("acc").expiresAtTimestamp(now.get() + 3_600_000L)
                .refreshToken("ref").refreshTokenExpiresAtTimestamp(now.get() + 99_999L)
                .build();

        LoginResult result = service(config, paths, client).login(new LoginRequest(
                "https://brain.example.com", "wss://brain.example.com",
                "acme", "mike", "my-project", "pw"));

        // access.yaml written with the refresh token
        Optional<AccessData> access = new AccessStore().load(dir);
        assertThat(access).isPresent();
        assertThat(access.get().getAccessToken()).isEqualTo("acc");
        assertThat(access.get().getRefreshToken()).isEqualTo("ref");
        // project.yaml written
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

        boolean removed = service(config(), localPaths(dir), new FakeClient()).logout();

        assertThat(removed).isTrue();
        assertThat(store.exists(dir)).isFalse();
    }
}
