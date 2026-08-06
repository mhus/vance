package de.mhus.vance.foot.tools.pack;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The record of permanently approved project packs. Matching is on
 * (name, reach) so an approval covers one concrete command — a repo that
 * swaps the command has to ask again.
 */
class TrustedPacksStoreTest {

    private Path home;
    private Path project;
    private TrustedPacksStore store;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createTempDirectory("trusted-packs-home-");
        project = Files.createTempDirectory("trusted-packs-project-");
        store = new TrustedPacksStore();
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Path root : List.of(home, project)) {
            if (root == null || !Files.exists(root)) continue;
            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    private LoadedPack pack(String name, String command) {
        FootToolPackConfig config = new FootToolPackConfig(
                name, "mcp_server", null, null, null, null, null, null,
                Map.of("transport", "stdio", "command", List.of(command.split(" "))));
        return new LoadedPack(config, project.resolve("foot-tools/" + name + ".json"),
                PackOrigin.PROJECT);
    }

    @Test
    void absentFile_trustsNothing() {
        assertThat(store.isTrusted(home, project, pack("chrome", "npx chrome"))).isFalse();
    }

    @Test
    void trustedPack_isRecognisedAfterAReRead() {
        LoadedPack chrome = pack("chrome", "npx -y chrome-devtools-mcp@latest");

        store.trust(home, project, chrome);

        assertThat(new TrustedPacksStore().isTrusted(home, project, chrome)).isTrue();
    }

    @Test
    void approvalIsWrittenToTheGlobalHome_notTheProject() {
        // The gate exists because the project directory is untrusted
        // input; the approval must therefore not live inside it.
        store.trust(home, project, pack("chrome", "npx chrome"));

        assertThat(home.resolve(TrustedPacksStore.FILE_NAME)).exists();
        assertThat(project.resolve(TrustedPacksStore.FILE_NAME)).doesNotExist();
    }

    @Test
    void changedCommand_isNoLongerTrusted() {
        store.trust(home, project, pack("chrome", "npx -y chrome-devtools-mcp@latest"));

        assertThat(store.isTrusted(home, project, pack("chrome", "npx -y something-else")))
                .isFalse();
    }

    @Test
    void approvalIsScopedToItsProject() throws IOException {
        Path otherProject = Files.createTempDirectory("trusted-packs-other-");
        try {
            LoadedPack chrome = pack("chrome", "npx chrome");
            store.trust(home, project, chrome);

            assertThat(store.isTrusted(home, otherProject, chrome)).isFalse();
        } finally {
            Files.deleteIfExists(otherProject);
        }
    }

    @Test
    void trustingTwice_doesNotDuplicate() {
        LoadedPack chrome = pack("chrome", "npx chrome");

        store.trust(home, project, chrome);
        store.trust(home, project, chrome);

        assertThat(store.load(home).getTrustedPacks().values())
                .allSatisfy(entries -> assertThat(entries).hasSize(1));
    }

    @Test
    void twoPacksInOneProject_bothStick() {
        store.trust(home, project, pack("chrome", "npx chrome"));
        store.trust(home, project, pack("db", "npx db"));

        assertThat(store.isTrusted(home, project, pack("chrome", "npx chrome"))).isTrue();
        assertThat(store.isTrusted(home, project, pack("db", "npx db"))).isTrue();
    }

    @Test
    void brokenFile_trustsNothing_insteadOfFailingTheRun() throws IOException {
        Files.writeString(home.resolve(TrustedPacksStore.FILE_NAME), "\t: not: yaml: [");

        assertThat(store.isTrusted(home, project, pack("chrome", "npx chrome"))).isFalse();
    }
}
