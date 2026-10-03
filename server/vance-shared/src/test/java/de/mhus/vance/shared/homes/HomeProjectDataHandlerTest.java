package de.mhus.vance.shared.homes;

import static org.assertj.core.api.Assertions.assertThat;
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
 * The maintenance answers for a project's home: a project owns exactly its
 * project-scoped directory, and a <em>shared</em> home is reported as
 * deliberately kept rather than counted away — the same "silence must never
 * mean nothing there" rule the whole maintenance seam is built on.
 */
class HomeProjectDataHandlerTest {

    private static final String TENANT = "acme";

    @TempDir
    Path root;

    private SettingService settings;
    private HomeProjectDataHandler handler;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
        handler = new HomeProjectDataHandler(service());
    }

    private HomesService service() {
        HomesProperties properties = new HomesProperties();
        properties.setRoot(root.toString());
        return new HomesService(properties, new WorkspaceProperties(), settings);
    }

    private void scope(String value) {
        when(settings.getStringValueCascade(eq(TENANT), eq("apollo"), isNull(), eq("home-scope")))
                .thenReturn(value);
    }

    @Test
    void delete_removesOnlyTheProjectsOwnHome() throws IOException {
        HomesService service = service();
        Path home = service.ensure(TENANT, "apollo", null);
        Files.writeString(home.resolve("token"), "secret");

        assertThat(handler.count(TENANT, "apollo")).isEqualTo(1);
        assertThat(handler.delete(TENANT, "apollo")).isEqualTo(1);
        assertThat(Files.exists(home)).isFalse();
    }

    @Test
    void delete_underSharedScope_keepsTheHomeAndSaysSo() {
        scope("tenant");
        HomesService service = service();
        Path shared = service.ensure(TENANT, "apollo", null);

        assertThat(handler.delete(TENANT, "apollo")).isEqualTo(0);
        assertThat(Files.exists(shared)).isTrue();
        assertThat(handler.deleteNote(TENANT, "apollo")).contains("_tenant").contains("kept");
    }

    @Test
    void deleteNote_underProjectScope_isSilent() {
        assertThat(handler.deleteNote(TENANT, "apollo")).isNull();
    }

    @Test
    void rename_carriesTheHomeAndBlocksOnAnExistingTarget() throws IOException {
        HomesService service = service();
        Path home = service.ensure(TENANT, "apollo", null);
        Files.writeString(home.resolve("token"), "secret");

        assertThat(handler.rename(TENANT, "apollo", "zeus")).isEqualTo(1);
        assertThat(Files.exists(home)).isFalse();
        assertThat(Files.exists(service.homeDir(TENANT, "zeus"))).isTrue();

        Files.createDirectories(service.homeDir(TENANT, "hera"));
        assertThat(handler.renameBlocker(TENANT, "zeus", "hera")).contains("hera");
        assertThat(handler.renameBlocker(TENANT, "zeus", "athena")).isNull();
    }
}
