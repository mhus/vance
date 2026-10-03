package de.mhus.vance.shared.homes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The user-scoped home follows the account: OWNED data is deleted with it, and
 * a rename carries the directory — same person, new login, same home
 * ({@code user-maintenance.md}).
 */
class HomeUserDataHandlerTest {

    private static final String TENANT = "acme";

    @TempDir
    Path root;

    private HomesService homes;
    private HomeUserDataHandler handler;

    @BeforeEach
    void setUp() {
        HomesProperties properties = new HomesProperties();
        properties.setRoot(root.toString());
        homes = new HomesService(properties, new WorkspaceProperties(), mock(SettingService.class));
        handler = new HomeUserDataHandler(homes);
    }

    @Test
    void countAndDelete_workOnTheUsersHubHome() throws Exception {
        Path home = homes.homeDir(TENANT, "_user_wile.coyote");
        Files.createDirectories(home);
        Files.writeString(home.resolve("token"), "secret");

        assertThat(handler.count(TENANT, "wile.coyote")).isEqualTo(1);
        assertThat(handler.delete(TENANT, "wile.coyote")).isEqualTo(1);
        assertThat(Files.exists(home)).isFalse();
        assertThat(handler.count(TENANT, "wile.coyote")).isZero();
    }

    @Test
    void rename_carriesTheHomeToTheNewLogin() throws Exception {
        Path home = homes.homeDir(TENANT, "_user_wile.coyote");
        Files.createDirectories(home);
        Files.writeString(home.resolve("token"), "secret");

        assertThat(handler.rename(TENANT, "wile.coyote", "road.runner")).isEqualTo(1);

        assertThat(Files.exists(home)).isFalse();
        assertThat(Files.exists(homes.homeDir(TENANT, "_user_road.runner"))).isTrue();
    }
}
