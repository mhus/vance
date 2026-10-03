package de.mhus.vance.shared.homes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The home-isolation contract behind {@code mhus/vance#63}: one {@code HOME}
 * per scope, the scope from settings but the directory name from code, and a
 * size guard on top. The security-relevant assertions are the fail-safe
 * fallback (an unparsable scope must never widen sharing) and the path-segment
 * check (scope keys become path segments).
 */
class HomesServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "apollo";
    private static final String USER = "wile.coyote";

    @TempDir
    Path root;

    private SettingService settings;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
    }

    private HomesService service() {
        return service(2L * 1024 * 1024 * 1024);
    }

    private HomesService service(long maxBytes) {
        HomesProperties properties = new HomesProperties();
        properties.setRoot(root.toString());
        properties.setMaxBytes(maxBytes);
        return new HomesService(properties, new WorkspaceProperties(), settings);
    }

    // ── scope resolution ──────────────────────────────────────────────────

    @Test
    void scopeOf_withoutSetting_isProject() {
        assertThat(service().scopeOf(TENANT, PROJECT)).isEqualTo(HomeScope.PROJECT);
    }

    @Test
    void scopeOf_unknownValue_fallsBackToProjectNeverWidensSharing() {
        when(settings.getStringValueCascade(eq(TENANT), eq(PROJECT), isNull(), eq("home-scope")))
                .thenReturn("banana");

        assertThat(service().scopeOf(TENANT, PROJECT)).isEqualTo(HomeScope.PROJECT);
    }

    @Test
    void scopeOf_tenantSetting_isTenant() {
        when(settings.getStringValueCascade(eq(TENANT), eq(PROJECT), isNull(), eq("home-scope")))
                .thenReturn("tenant");

        assertThat(service().scopeOf(TENANT, PROJECT)).isEqualTo(HomeScope.TENANT);
    }

    @Test
    void scopeOf_userSetting_isUser() {
        when(settings.getStringValueCascade(eq(TENANT), eq(PROJECT), isNull(), eq("home-scope")))
                .thenReturn("user");

        assertThat(service().scopeOf(TENANT, PROJECT)).isEqualTo(HomeScope.USER);
    }

    // ── scope keys ────────────────────────────────────────────────────────

    @Test
    void scopeKey_project_isTheProjectName() {
        assertThat(service().scopeKey(TENANT, PROJECT, USER)).isEqualTo(PROJECT);
    }

    @Test
    void scopeKey_tenant_isTheTenantKeyOfThePermissionVocabulary() {
        when(settings.getStringValueCascade(eq(TENANT), eq(PROJECT), isNull(), eq("home-scope")))
                .thenReturn("tenant");

        assertThat(service().scopeKey(TENANT, PROJECT, USER)).isEqualTo("_tenant");
    }

    @Test
    void scopeKey_user_isTheUsersHubKey() {
        when(settings.getStringValueCascade(eq(TENANT), eq(PROJECT), isNull(), eq("home-scope")))
                .thenReturn("user");

        assertThat(service().scopeKey(TENANT, PROJECT, USER)).isEqualTo("_user_wile.coyote");
    }

    @Test
    void scopeKey_userWithoutUser_fallsBackToTheProjectHome() {
        when(settings.getStringValueCascade(eq(TENANT), eq(PROJECT), isNull(), eq("home-scope")))
                .thenReturn("user");

        assertThat(service().scopeKey(TENANT, PROJECT, null)).isEqualTo(PROJECT);
    }

    // ── directories ───────────────────────────────────────────────────────

    @Test
    void ensure_createsTheScopedHomeAndIsIdempotent() {
        HomesService service = service();

        Path first = service.ensure(TENANT, PROJECT, USER);
        Path second = service.ensure(TENANT, PROJECT, USER);

        assertThat(first).isEqualTo(root.resolve(TENANT).resolve(PROJECT));
        assertThat(second).isEqualTo(first);
        assertThat(Files.isDirectory(first)).isTrue();
    }

    @Test
    void ensure_createsSharedHomeForTenantScope() {
        when(settings.getStringValueCascade(eq(TENANT), any(), isNull(), eq("home-scope")))
                .thenReturn("tenant");
        HomesService service = service();

        Path projectHome = service.ensure(TENANT, "other-project", USER);

        assertThat(projectHome).isEqualTo(root.resolve(TENANT).resolve("_tenant"));
    }

    @Test
    void homeDir_rejectsTraversalInScopeKeys() {
        HomesService service = service();

        assertThatThrownBy(() -> service.homeDir(TENANT, ".."))
                .isInstanceOf(HomesException.class)
                .hasMessageContaining("path segment");
        assertThatThrownBy(() -> service.homeDir(TENANT, "a/b"))
                .isInstanceOf(HomesException.class)
                .hasMessageContaining("path segment");
        assertThatThrownBy(() -> service.homeDir("", PROJECT))
                .isInstanceOf(HomesException.class)
                .hasMessageContaining("required");
    }

    // ── size guard ────────────────────────────────────────────────────────

    @Test
    void ensure_overBudget_refusesToProvideTheHome() throws IOException {
        HomesService service = service(16);
        Path home = service.ensure(TENANT, PROJECT, USER);
        Files.writeString(home.resolve("cache.bin"), "0123456789012345678901234567890123456789");

        // A fresh probe (the measurement is cached per scope for a few
        // minutes) sees the grown home and refuses.
        assertThatThrownBy(() -> service(16).ensure(TENANT, PROJECT, USER))
                .isInstanceOf(HomesException.class)
                .hasMessageContaining("vance.homes.max-bytes");
    }

    @Test
    void ensure_budgetDisabled_neverRefuses() throws IOException {
        HomesService service = service(0);
        Path home = service.ensure(TENANT, PROJECT, USER);
        Files.writeString(home.resolve("cache.bin"), "x".repeat(1000));

        assertThat(service.ensure(TENANT, PROJECT, USER)).isEqualTo(home);
    }

    // ── maintenance ───────────────────────────────────────────────────────

    @Test
    void delete_removesTheHomeAndReportsWhetherItDid() throws IOException {
        HomesService service = service();
        Path home = service.ensure(TENANT, PROJECT, USER);
        Files.writeString(home.resolve("token"), "secret");

        assertThat(service.delete(TENANT, PROJECT)).isEqualTo(1);
        assertThat(Files.exists(home)).isFalse();
        assertThat(service.delete(TENANT, PROJECT)).isEqualTo(0);
    }

    @Test
    void rename_movesTheHomeToTheNewScopeKey() {
        HomesService service = service();
        Path home = service.ensure(TENANT, PROJECT, USER);

        assertThat(service.rename(TENANT, PROJECT, "zeus")).isEqualTo(1);

        assertThat(Files.exists(home)).isFalse();
        assertThat(service.homeDir(TENANT, "zeus")).exists();
    }

    @Test
    void rename_toAnExistingHome_refuses() {
        HomesService service = service();
        service.ensure(TENANT, PROJECT, USER);
        service.ensure(TENANT, "zeus", USER);

        assertThatThrownBy(() -> service.rename(TENANT, PROJECT, "zeus"))
                .isInstanceOf(HomesException.class)
                .hasMessageContaining("already exists");
    }
}
