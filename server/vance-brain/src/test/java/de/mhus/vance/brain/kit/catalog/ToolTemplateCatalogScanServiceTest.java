package de.mhus.vance.brain.kit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.ToolTemplateCatalogDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
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

class ToolTemplateCatalogScanServiceTest {

    @TempDir
    Path tmpRoot;

    private ToolTemplateCatalogScanService scanner;

    @BeforeEach
    void setUp() {
        KitWorkspace workspace = new KitWorkspace(tmpRoot.resolve("ws").toString());
        scanner = new ToolTemplateCatalogScanService(workspace);
    }

    @Test
    void scan_yieldsEntryPerToolsSubdirWithTemplateYaml() throws Exception {
        Path repo = setupLocalRepo();
        writeTemplate(repo, "jira",
                "name: jira\ntitle: Atlassian Jira\ndescription: OAuth + REST\ncategory: developer-tools\n");
        writeTemplate(repo, "smtp-sender",
                "name: smtp-sender\ntitle: SMTP Sender\ncategory: communication\n");
        commitAll(repo, "initial");

        ToolTemplateCatalogDto result = scanner.scan(repo.toUri().toString(), "main", null);

        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getTemplates())
                .extracting(ToolTemplateCatalogEntry::getName)
                .containsExactly("jira", "smtp-sender");

        ToolTemplateCatalogEntry jira = result.getTemplates().stream()
                .filter(e -> "jira".equals(e.getName()))
                .findFirst().orElseThrow();
        assertThat(jira.getTitle()).isEqualTo("Atlassian Jira");
        assertThat(jira.getDescription()).isEqualTo("OAuth + REST");
        assertThat(jira.getCategory()).isEqualTo("developer-tools");
        assertThat(jira.getSource().getPath()).isEqualTo("tools/jira");
        assertThat(jira.getSource().getBranch()).isEqualTo("main");
    }

    @Test
    void scan_fillsMissingTitleWithSubDirName() throws Exception {
        Path repo = setupLocalRepo();
        // template.yaml without title
        writeTemplate(repo, "imap-mailbox", "name: imap-mailbox\n");
        commitAll(repo, "no-title");

        ToolTemplateCatalogDto result = scanner.scan(repo.toUri().toString(), null, null);

        ToolTemplateCatalogEntry entry = result.getTemplates().get(0);
        assertThat(entry.getName()).isEqualTo("imap-mailbox");
        assertThat(entry.getTitle()).isEqualTo("imap-mailbox");
        assertThat(entry.getDescription()).isNull();
        assertThat(entry.getCategory()).isNull();
    }

    @Test
    void scan_appliesCatalogYamlOverrides() throws Exception {
        Path repo = setupLocalRepo();
        writeTemplate(repo, "jira", "name: jira\ntitle: Jira (template)\n");
        Files.writeString(repo.resolve("tools").resolve("catalog.yaml"),
                """
                overrides:
                  jira:
                    name: atlassian/jira
                    title: Atlassian Jira (Override)
                    description: Catalog override description
                    category: developer-tools
                """);
        commitAll(repo, "with-overrides");

        ToolTemplateCatalogDto result = scanner.scan(repo.toUri().toString(), null, null);

        ToolTemplateCatalogEntry entry = result.getTemplates().get(0);
        assertThat(entry.getName()).isEqualTo("atlassian/jira");
        assertThat(entry.getTitle()).isEqualTo("Atlassian Jira (Override)");
        assertThat(entry.getDescription()).isEqualTo("Catalog override description");
        assertThat(entry.getCategory()).isEqualTo("developer-tools");
        // Source path stays subdir-based (the override doesn't relocate the kit).
        assertThat(entry.getSource().getPath()).isEqualTo("tools/jira");
    }

    @Test
    void scan_skipsSubdirsWithoutTemplateYaml() throws Exception {
        Path repo = setupLocalRepo();
        writeTemplate(repo, "real", "name: real\n");
        // Plain kit (has kit.yaml only) — should be ignored.
        Path plain = repo.resolve("tools").resolve("plain-kit");
        Files.createDirectories(plain);
        Files.writeString(plain.resolve("kit.yaml"), "name: plain-kit\n");
        commitAll(repo, "mixed");

        ToolTemplateCatalogDto result = scanner.scan(repo.toUri().toString(), null, null);

        assertThat(result.getTemplates())
                .extracting(ToolTemplateCatalogEntry::getName)
                .containsExactly("real");
    }

    @Test
    void scan_rejectsRepoWithoutToolsSubdir() throws Exception {
        Path repo = setupLocalRepo();
        Files.writeString(repo.resolve("README.md"), "no tools here");
        commitAll(repo, "no-tools");

        assertThatThrownBy(() -> scanner.scan(repo.toUri().toString(), null, null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("tools/");
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

    private void writeTemplate(Path repo, String subDirName, String templateYamlBody) throws IOException {
        Path subDir = repo.resolve("tools").resolve(subDirName);
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("template.yaml"), templateYamlBody, StandardCharsets.UTF_8);
        // tool-templates kits also carry a kit.yaml — write a stub so the layout
        // matches the production convention.
        Files.writeString(subDir.resolve("kit.yaml"),
                "name: tools-" + subDirName + "\nartifact: true\n", StandardCharsets.UTF_8);
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
