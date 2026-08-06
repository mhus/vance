package de.mhus.vance.foot.tools.pack;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.auth.VancePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two-layer merge: global {@code <VANCE_HOME>/foot-tools} plus
 * project-local {@code ./.vancetope/foot-tools}, union by pack name with
 * the project winning. Both directories are temp dirs bound through
 * {@link VancePaths}' property constructor.
 */
class FootToolPackLoaderLayerTest {

    private Path home;
    private Path project;
    private Path homePacks;
    private Path projectPacks;
    private VancePaths paths;
    private FootToolPackLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createTempDirectory("foot-packs-home-");
        project = Files.createTempDirectory("foot-packs-project-");
        homePacks = Files.createDirectories(home.resolve(FootToolPackLoader.SUBDIR));
        projectPacks = Files.createDirectories(project.resolve(FootToolPackLoader.SUBDIR));
        paths = new VancePaths(project.toString(), home.toString());
        loader = new FootToolPackLoader(paths);
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(home);
        deleteRecursively(project);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }

    private static void writePack(Path dir, String name, String url) throws IOException {
        Files.writeString(dir.resolve(name + ".json"), """
                {"name":"%s","type":"mcp_server",
                 "parameters":{"transport":"http","url":"%s"}}
                """.formatted(name, url));
    }

    private Map<String, LoadedPack> loadByName() {
        return loader.loadAll().stream()
                .collect(java.util.stream.Collectors.toMap(LoadedPack::name, Function.identity()));
    }

    @Test
    void bothLayers_areUnioned() throws IOException {
        writePack(homePacks, "atlassian", "http://home/mcp");
        writePack(projectPacks, "projectdb", "http://project/mcp");

        assertThat(loader.loadAll()).extracting(LoadedPack::name)
                .containsExactlyInAnyOrder("atlassian", "projectdb");
    }

    @Test
    void sameName_projectWins() throws IOException {
        writePack(homePacks, "chrome", "http://home/mcp");
        writePack(projectPacks, "chrome", "http://project/mcp");

        Map<String, LoadedPack> packs = loadByName();

        assertThat(packs).hasSize(1);
        LoadedPack chrome = packs.get("chrome");
        assertThat(chrome.origin()).isEqualTo(PackOrigin.PROJECT);
        assertThat(chrome.file()).isEqualTo(projectPacks.resolve("chrome.json"));
        assertThat(chrome.config().parametersOrEmpty()).containsEntry("url", "http://project/mcp");
    }

    @Test
    void projectCanSilenceAnInheritedPack() throws IOException {
        // The override wins, and an override that says enabled:false is
        // how a project opts out of a global pack without touching ~.
        writePack(homePacks, "chrome", "http://home/mcp");
        Files.writeString(projectPacks.resolve("chrome.json"), """
                {"name":"chrome","type":"mcp_server","enabled":false,
                 "parameters":{"transport":"http","url":"http://home/mcp"}}
                """);

        LoadedPack chrome = loadByName().get("chrome");

        assertThat(chrome.config().isEffectivelyEnabled()).isFalse();
    }

    @Test
    void eachPack_carriesItsOrigin() throws IOException {
        writePack(homePacks, "fromHome", "http://home/mcp");
        writePack(projectPacks, "fromProject", "http://project/mcp");

        Map<String, LoadedPack> packs = loadByName();

        assertThat(packs.get("fromHome").origin()).isEqualTo(PackOrigin.GLOBAL);
        assertThat(packs.get("fromProject").origin()).isEqualTo(PackOrigin.PROJECT);
    }

    @Test
    void noLocalMode_dropsTheProjectLayer() throws IOException {
        writePack(homePacks, "atlassian", "http://home/mcp");
        writePack(projectPacks, "projectdb", "http://project/mcp");
        paths.setLocalEnabled(false);

        assertThat(loader.loadAll()).extracting(LoadedPack::name).containsExactly("atlassian");
    }

    @Test
    void absentProjectDirectory_isNotAFailure() throws IOException {
        deleteRecursively(projectPacks);
        writePack(homePacks, "atlassian", "http://home/mcp");

        assertThat(loader.loadAll()).extracting(LoadedPack::name).containsExactly("atlassian");
    }

    @Test
    void globalDirectory_followsVanceHome() {
        // The loader used to build ~/.vancetope/foot-tools straight from
        // user.home, so $VANCE_HOME / vance.home was ignored for packs
        // while every other store honoured it.
        assertThat(loader.globalDir()).isEqualTo(homePacks);
        assertThat(loader.projectDir()).isEqualTo(projectPacks);
    }

    @Test
    void reachDescription_showsTheSpawnedCommand() throws IOException {
        Files.writeString(projectPacks.resolve("chrome.json"), """
                {"name":"chrome","type":"mcp_server",
                 "parameters":{"transport":"stdio",
                               "command":["npx","-y","chrome-devtools-mcp@latest"]}}
                """);

        LoadedPack chrome = loadByName().get("chrome");

        assertThat(chrome.reachDescription()).isEqualTo("npx -y chrome-devtools-mcp@latest");
    }

    @Test
    void reachDescription_fallsBackToTheEndpoint() throws IOException {
        writePack(projectPacks, "remote", "https://mcp.example.com/mcp");

        assertThat(loadByName().get("remote").reachDescription())
                .isEqualTo("https://mcp.example.com/mcp");
    }

    @Test
    void loadAll_isRepeatable() throws IOException {
        writePack(homePacks, "a", "http://a/mcp");
        writePack(projectPacks, "b", "http://b/mcp");

        List<LoadedPack> first = loader.loadAll();
        List<LoadedPack> second = loader.loadAll();

        assertThat(second).extracting(LoadedPack::name)
                .containsExactlyElementsOf(first.stream().map(LoadedPack::name).toList());
    }
}
