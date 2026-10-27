package de.mhus.vance.backup.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ConfigLoader}.
 *
 * <p>The loader is the first thing every CLI command runs through, so
 * the failure paths here get the most coverage: missing file,
 * unreadable file, empty file, parse error, missing or malformed
 * server.url, missing tenant, missing token. The happy path covers
 * the defaults baked into {@link BackupConfig} so a refactor that
 * changes a default (e.g. removing {@code _vance/**} from deny)
 * cannot land silently.
 */
class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void defaultConfigPath_livesUnderHomeDotVancetopeBackup(@TempDir Path home) {
        Path file = loader.defaultConfigPath(home);
        assertThat(file).isEqualTo(home.resolve(".vancetope/backup/backup.yaml"));
    }

    @Test
    void resolveConfigPath_overrideWins() {
        Path override = Path.of("/etc/foo.yaml");
        assertThat(loader.resolveConfigPath(override)).isEqualTo(override);
    }

    @Test
    void load_missingFile_throws(@TempDir Path home) {
        Path file = loader.defaultConfigPath(home);
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("not found")
                .hasMessageContaining(file.toString())
                .hasMessageContaining("init");
    }

    @Test
    void load_emptyFile_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, "");
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void load_garbageYaml_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, ":\n  - [unbalanced\n");
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void load_missingServerUrl_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  tenant: acme
                token: "eyJ..."
                """);
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("server.url");
    }

    @Test
    void load_wrongServerScheme_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  url: "ftp://brain.example"
                  tenant: acme
                token: "eyJ..."
                """);
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("must use http:// or https://");
    }

    @Test
    void load_serverUrlWithPath_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  url: "https://brain.example/some/path"
                  tenant: acme
                token: "eyJ..."
                """);
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("must not include a path");
    }

    @Test
    void load_missingTenant_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  url: "https://brain.example"
                token: "eyJ..."
                """);
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("server.tenant");
    }

    @Test
    void load_missingToken_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  url: "https://brain.example"
                  tenant: acme
                """);
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("missing 'token'");
    }

    @Test
    void load_happyPath_appliesDefaults(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  url: "http://localhost:9991"
                  tenant: acme
                token: "eyJhbGciOi..."
                target: /tmp/backup
                """);
        BackupConfig cfg = loader.load(file);

        assertThat(cfg.server.url).isEqualTo("http://localhost:9991");
        assertThat(cfg.server.tenant).isEqualTo("acme");
        assertThat(cfg.token).startsWith("eyJ");
        assertThat(cfg.target).isEqualTo("/tmp/backup");
        assertThat(cfg.deny).containsExactly("_vance/**");
        assertThat(cfg.projects.include).containsExactly("*");
        assertThat(cfg.projects.exclude).isEmpty();
        assertThat(cfg.projects.includeDisabled).isFalse();
        assertThat(cfg.retention.hourly).isEqualTo(24);
        assertThat(cfg.retention.daily).isEqualTo(30);
        assertThat(cfg.retention.weekly).isEqualTo(52);
        assertThat(cfg.notify).isTrue();
    }

    @Test
    void load_blankTarget_fallsBackToHome(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  url: "https://brain.example"
                  tenant: acme
                token: "eyJ..."
                target: ""
                """);
        BackupConfig cfg = loader.load(file);

        assertThat(cfg.target).isEqualTo(System.getProperty("user.home") + "/VancetopeBackup");
    }

    @Test
    void load_negativeRetention_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  url: "https://brain.example"
                  tenant: acme
                token: "eyJ..."
                retention:
                  hourly: -1
                """);
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void load_blankDenyEntry_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  url: "https://brain.example"
                  tenant: acme
                token: "eyJ..."
                deny:
                  - "drafts/**"
                  - ""
                """);
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("blank entry");
    }

    @Test
    void load_blankProjectDenyEntry_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                server:
                  url: "https://brain.example"
                  tenant: acme
                token: "eyJ..."
                projectDeny:
                  wiki:
                    - "drafts/**"
                    - "  "
                """);
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("blank entry");
    }

    @Test
    void load_tokenFile_resolvesInline(@TempDir Path home) throws IOException {
        Path tokenFile = home.resolve("token.txt");
        Files.writeString(tokenFile, "eyJhbGciOi...\n");

        Path cfgFile = home.resolve("backup.yaml");
        Files.writeString(cfgFile, """
                server:
                  url: "https://brain.example"
                  tenant: acme
                tokenFile: %s
                """.formatted(tokenFile));

        BackupConfig cfg = loader.load(cfgFile);
        assertThat(cfg.token).startsWith("eyJ");
    }

    @Test
    void load_tokenFile_missing_throws(@TempDir Path home) throws IOException {
        Path cfgFile = home.resolve("backup.yaml");
        Files.writeString(cfgFile, """
                server:
                  url: "https://brain.example"
                  tenant: acme
                tokenFile: /nonexistent/token
                """);

        assertThatThrownBy(() -> loader.load(cfgFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("tokenFile does not exist");
    }

    @Test
    void writeStarter_createsParentAndWritesDefaults(@TempDir Path home) throws IOException {
        Path file = home.resolve("nested/dir/backup.yaml");
        loader.writeStarter(file);

        assertThat(Files.exists(file)).isTrue();

        // Round-trip: append a valid server/token block, then load.
        Files.writeString(
                file,
                Files.readString(file)
                        + "\nserver:\n  url: \"https://brain.example\"\n  tenant: acme\ntoken: \"eyJ...\"\n");
        BackupConfig cfg = loader.loadFrom(file);

        assertThat(cfg.deny).containsExactly("_vance/**");
        assertThat(cfg.projects.include).containsExactly("*");
    }
}
