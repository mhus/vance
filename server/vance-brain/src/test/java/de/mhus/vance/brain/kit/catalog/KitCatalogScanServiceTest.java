package de.mhus.vance.brain.kit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.brain.kit.KitException;
import de.mhus.vance.brain.kit.KitWorkspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KitCatalogScanServiceTest {

    @TempDir
    Path tmpRoot;

    private KitWorkspace workspace;
    private KitCatalogScanService scanner;

    @BeforeEach
    void setUp() {
        workspace = new KitWorkspace(tmpRoot.resolve("ws").toString());
        scanner = new KitCatalogScanService(workspace);
    }

    @Test
    void scan_localRepo_yieldsEntryPerKitSubdir() throws Exception {
        Path repo = setupLocalRepo();
        writeKit(repo, "research", "Research Base kit.yaml");
        writeKit(repo, "dev-java", "Java kit.yaml");
        commitAll(repo, "initial");

        ProjectKitsCatalogDto result = scanner.scan(repo.toUri().toString(), "main", null);

        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getKits())
                .extracting(ProjectKitEntry::getName)
                .containsExactly("dev-java", "research");
        ProjectKitEntry research = result.getKits().stream()
                .filter(e -> "research".equals(e.getName()))
                .findFirst().orElseThrow();
        assertThat(research.getTitle()).isEqualTo("research");
        assertThat(research.getSource().getPath()).isEqualTo("kits/research");
        assertThat(research.getSource().getBranch()).isEqualTo("main");
    }

    @Test
    void scan_appliesCatalogYamlOverrides() throws Exception {
        Path repo = setupLocalRepo();
        writeKit(repo, "research", "kit.yaml stub");
        writeKit(repo, "dev-java", "kit.yaml stub");
        Files.writeString(repo.resolve("kits").resolve("catalog.yaml"),
                """
                overrides:
                  research:
                    name: base/research
                    title: Research Base
                    description: Web-search research
                  dev-java:
                    name: base/dev/java
                    title: Java Development
                """);
        commitAll(repo, "with-overrides");

        ProjectKitsCatalogDto result = scanner.scan(repo.toUri().toString(), null, null);

        ProjectKitEntry research = result.getKits().stream()
                .filter(e -> "base/research".equals(e.getName()))
                .findFirst().orElseThrow();
        assertThat(research.getTitle()).isEqualTo("Research Base");
        assertThat(research.getDescription()).isEqualTo("Web-search research");
        assertThat(research.getSource().getPath()).isEqualTo("kits/research");

        ProjectKitEntry java = result.getKits().stream()
                .filter(e -> "base/dev/java".equals(e.getName()))
                .findFirst().orElseThrow();
        assertThat(java.getTitle()).isEqualTo("Java Development");
        assertThat(java.getDescription()).isNull();
    }

    @Test
    void scan_skipsSubdirsWithoutKitYaml() throws Exception {
        Path repo = setupLocalRepo();
        writeKit(repo, "real", "kit.yaml");
        // Subdir without kit.yaml — should be ignored.
        Files.createDirectories(repo.resolve("kits").resolve("notakit"));
        Files.writeString(repo.resolve("kits").resolve("notakit").resolve("README.md"), "irrelevant");
        commitAll(repo, "mixed");

        ProjectKitsCatalogDto result = scanner.scan(repo.toUri().toString(), null, null);

        assertThat(result.getKits())
                .extracting(ProjectKitEntry::getName)
                .containsExactly("real");
    }

    @Test
    void scan_rejectsRepoWithoutKitsSubdir() throws Exception {
        Path repo = setupLocalRepo();
        Files.writeString(repo.resolve("README.md"), "no kits here");
        commitAll(repo, "no-kits");

        assertThatThrownBy(() -> scanner.scan(repo.toUri().toString(), null, null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("kits/");
    }

    @Test
    void scan_rejectsBlankUrl() {
        assertThatThrownBy(() -> scanner.scan("  ", null, null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("gitUrl");
    }

    // ──────────────────── helpers ────────────────────

    private Path setupLocalRepo() throws IOException, GitAPIException {
        Path repo = Files.createDirectory(tmpRoot.resolve("source-" + System.nanoTime()));
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            // empty config
        }
        return repo;
    }

    private void writeKit(Path repo, String subDirName, String content) throws IOException {
        Path subDir = repo.resolve("kits").resolve(subDirName);
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("kit.yaml"),
                "name: " + subDirName + "\ndescription: " + content + "\n",
                StandardCharsets.UTF_8);
    }

    private void commitAll(Path repo, String message) throws IOException, GitAPIException {
        try (Git git = Git.open(repo.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage(message)
                    .setAuthor(new PersonIdent("Test", "test@example.com"))
                    .setCommitter(new PersonIdent("Test", "test@example.com"))
                    .setSign(false)
                    .call();
        }
    }
}
