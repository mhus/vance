package de.mhus.vance.liver.config;

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
 * unreadable file, empty file, parse error, missing connection,
 * wrong connection scheme, blank deny entry. The happy path covers
 * the defaults baked into {@link BackupConfig} so a refactor that
 * changes a default (e.g. removing {@code _vance/**} from deny)
 * cannot land silently.
 */
class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void defaultConfigPath_livesUnderHomeDotVancetopeLiver(@TempDir Path home) {
        Path file = loader.defaultConfigPath(home);
        assertThat(file).isEqualTo(home.resolve(".vancetope/liver/backup.yaml"));
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
    void load_missingConnection_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, "target: /tmp/foo\n");
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("missing 'connection'");
    }

    @Test
    void load_wrongConnectionScheme_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, "connection: \"https://brain.example\"\n");
        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("must start with 'vancetope1.'");
    }

    @Test
    void load_happyPath_appliesDefaults(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                connection: vancetope1.brain.example/acme/backup/AAAA
                target: /tmp/backup
                """);
        BackupConfig cfg = loader.load(file);

        assertThat(cfg.connection).startsWith("vancetope1.");
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
                connection: vancetope1.brain.example/acme/backup/AAAA
                target: ""
                """);
        BackupConfig cfg = loader.load(file);

        assertThat(cfg.target).isEqualTo(System.getProperty("user.home") + "/VancetopeBackup");
    }

    @Test
    void load_negativeRetention_throws(@TempDir Path home) throws IOException {
        Path file = home.resolve("backup.yaml");
        Files.writeString(file, """
                connection: vancetope1.x/y/z/AAAA
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
                connection: vancetope1.x/y/z/AAAA
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
                connection: vancetope1.x/y/z/AAAA
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
    void load_connectionFile_resolvesInline(@TempDir Path home) throws IOException {
        Path connFile = home.resolve("conn.txt");
        Files.writeString(connFile, "vancetope1.brain.example/acme/backup/AAAA\n");

        Path cfgFile = home.resolve("backup.yaml");
        Files.writeString(cfgFile, "connectionFile: " + connFile + "\n");

        BackupConfig cfg = loader.load(cfgFile);
        assertThat(cfg.connection).startsWith("vancetope1.");
    }

    @Test
    void load_connectionFile_missing_throws(@TempDir Path home) throws IOException {
        Path cfgFile = home.resolve("backup.yaml");
        Files.writeString(cfgFile, "connectionFile: /nonexistent/conn\n");

        assertThatThrownBy(() -> loader.load(cfgFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("connectionFile does not exist");
    }

    @Test
    void redact_keepsPrefixHidesRest() {
        String r = ConfigLoader.redact("vancetope1.brain.example/acme/backup/SECRET-TOKEN-XYZ");
        assertThat(r).startsWith("vancetope1.brain");
        assertThat(r).doesNotContain("SECRET-TOKEN");
        assertThat(r).endsWith("…");
    }

    @Test
    void writeStarter_createsParentAndWritesDefaults(@TempDir Path home) throws IOException {
        Path file = home.resolve("nested/dir/backup.yaml");
        loader.writeStarter(file);

        assertThat(Files.exists(file)).isTrue();

        // Round-trip the written file to confirm the defaults are
        // what we expect. We pass a fully-populated starter through
        // loadFrom by adding a connection string afterwards.
        Files.writeString(file, Files.readString(file) + "\nconnection: vancetope1.x/y/z/AAAA\n");
        BackupConfig cfg = loader.loadFrom(file);

        assertThat(cfg.deny).containsExactly("_vance/**");
        assertThat(cfg.projects.include).containsExactly("*");
    }
}
