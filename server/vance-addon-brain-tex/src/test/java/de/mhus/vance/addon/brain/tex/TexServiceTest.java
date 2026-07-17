package de.mhus.vance.addon.brain.tex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
import de.mhus.vance.shared.workspace.WorkspaceDescriptor;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link TexService}. Mocks {@link DocumentService},
 * {@link WorkspaceService}, and {@link Tex2PdfExecutor}; uses a real
 * temp directory for the RootDir so file transport can be verified.
 *
 * <p>The executor is mocked — tests that exercise the compile path
 * stub {@code executor.compile()} to return success or failure, so
 * no real LaTeX installation is needed.
 */
class TexServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test-proj";

    private DocumentService documentService;
    private WorkspaceService workspaceService;
    private SettingService settings;
    private Tex2PdfExecutor executor;
    private TexService texService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        workspaceService = mock(WorkspaceService.class);
        settings = mock(SettingService.class);
        executor = mock(Tex2PdfExecutor.class);
        when(executor.type()).thenReturn("local");
        // Default: settings cascade returns null → falls back to "local"
        when(settings.getStringValueCascade(any(), any(), any(), eq(TexService.SETTING_EXECUTOR)))
                .thenReturn(null);
        texService = new TexService(documentService, workspaceService, settings, List.of(executor));
    }

    // ── manifest parsing ───────────────────────────────────────────

    @Test
    void compile_throws_whenComposeDocNotFound() {
        when(documentService.findByPath(TENANT, PROJECT, "missing.yaml"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "missing.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("tex-compose document not found");
    }

    @Test
    void compile_throws_whenManifestIsEmpty() {
        mockComposeDoc("");

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("manifest is empty");
    }

    @Test
    void compile_throws_whenMainIsMissing() {
        mockComposeDoc("""
                files:
                  - thesis.tex
                """);

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'main' is required");
    }

    @Test
    void compile_throws_whenFilesIsMissing() {
        mockComposeDoc("""
                main: thesis.tex
                """);

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'files' is required");
    }

    @Test
    void compile_throws_whenFilesIsEmpty() {
        mockComposeDoc("""
                main: thesis.tex
                files: []
                """);

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'files' is required");
    }

    @Test
    void compile_throws_whenFilesContainsNonString() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - 123
                """);

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("must be strings or maps");
    }

    @Test
    void compile_throws_whenManifestIsNotYaml() {
        mockComposeDoc("{{not yaml}}: : :");

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not valid YAML");
    }

    // ── workspace lifecycle ───────────────────────────────────────

    @Test
    void compile_createsTempRootDir_andDisposesAfter() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - thesis.tex
                """);
        mockSourceFile("thesis.tex", "Hello World");
        RootDirHandle handle = mockRootDir();
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(handle);
        when(executor.compile(any())).thenReturn(Tex2PdfExecutor.Result.failure("mocked failure", null, 10));

        texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1");

        verify(workspaceService).createRootDir(any(RootDirSpec.class));
        verify(workspaceService).disposeRootDir(eq(TENANT), eq(PROJECT), eq(handle.getDirName()));
    }

    @Test
    void compile_createsRootDirWithCorrectSpec() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - thesis.tex
                """);
        mockSourceFile("thesis.tex", "content");
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(mockRootDir());
        when(executor.compile(any())).thenReturn(Tex2PdfExecutor.Result.failure("mocked", null, 1));

        texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1");

        ArgumentCaptor<RootDirSpec> captor = ArgumentCaptor.forClass(RootDirSpec.class);
        verify(workspaceService).createRootDir(captor.capture());
        RootDirSpec spec = captor.getValue();
        assertThat(spec.getTenantId()).isEqualTo(TENANT);
        assertThat(spec.getProjectId()).isEqualTo(PROJECT);
        assertThat(spec.getType()).isEqualTo("temp");
        assertThat(spec.getCreatorProcessId()).isEqualTo("proc-1");
        assertThat(spec.isDeleteOnCreatorClose()).isTrue();
        assertThat(spec.getLabelHint()).contains("thesis.tex");
    }

    @Test
    void compile_disposesRootDir_evenWhenFileTransportFails() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - missing.tex
                """);
        // source file not found
        when(documentService.findByPath(TENANT, PROJECT, "missing.tex"))
                .thenReturn(Optional.empty());
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(mockRootDir());

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("source file not found");

        // Cleanup must still happen
        verify(workspaceService).disposeRootDir(eq(TENANT), eq(PROJECT), any());
    }

    // ── file transport ────────────────────────────────────────────

    @Test
    void compile_transportsAllDeclaredFiles() throws Exception {
        String composeYaml = """
                main: thesis.tex
                files:
                  - thesis.tex
                  - refs.bib
                  - images/fig.png
                """;
        mockComposeDoc(composeYaml);
        mockSourceFile("thesis.tex", "\\documentclass{article}");
        mockSourceFile("refs.bib", "@book{x, title={T}}");
        mockSourceFile("images/fig.png", "fake-png-bytes");

        RootDirHandle handle = mockRootDir();
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(handle);
        when(executor.compile(any())).thenReturn(Tex2PdfExecutor.Result.failure("mocked", null, 1));

        texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1");

        // All three files should be transported to the RootDir
        Path root = handle.getPath();
        assertThat(Files.exists(root.resolve("thesis.tex"))).isTrue();
        assertThat(Files.exists(root.resolve("refs.bib"))).isTrue();
        assertThat(Files.exists(root.resolve("images/fig.png"))).isTrue();
        assertThat(Files.readString(root.resolve("thesis.tex"))).isEqualTo("\\documentclass{article}");
    }

    @Test
    void compile_throws_whenSourceFileNotFound() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - thesis.tex
                  - missing.bib
                """);
        mockSourceFile("thesis.tex", "content");
        when(documentService.findByPath(TENANT, PROJECT, "missing.bib"))
                .thenReturn(Optional.empty());
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(mockRootDir());

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("source file not found: missing.bib");
    }

    @Test
    void compile_createsSubdirectoriesForNestedFiles() throws Exception {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - thesis.tex
                  - chapters/intro.tex
                  - images/diagram.png
                """);
        mockSourceFile("thesis.tex", "main");
        mockSourceFile("chapters/intro.tex", "intro");
        mockSourceFile("images/diagram.png", "png");

        RootDirHandle handle = mockRootDir();
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(handle);
        when(executor.compile(any())).thenReturn(Tex2PdfExecutor.Result.failure("mocked", null, 1));

        texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1");

        Path root = handle.getPath();
        assertThat(Files.isDirectory(root.resolve("chapters"))).isTrue();
        assertThat(Files.isDirectory(root.resolve("images"))).isTrue();
        assertThat(Files.exists(root.resolve("chapters/intro.tex"))).isTrue();
        assertThat(Files.exists(root.resolve("images/diagram.png"))).isTrue();
    }

    // ── cross-project references ───────────────────────────────────

    @Test
    void compile_transportsCrossProjectFiles() throws Exception {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - thesis.tex
                  - project: tud-template
                    path: tud-report.cls
                    target: lib/tud-report.cls
                """);
        mockSourceFile("thesis.tex", "\\documentclass{tud-report}");

        // Mock cross-project file
        DocumentDocument crossDoc = mock(DocumentDocument.class);
        when(crossDoc.getId()).thenReturn("cross-doc-id");
        when(documentService.findByPath(TENANT, "tud-template", "tud-report.cls"))
                .thenReturn(Optional.of(crossDoc));
        when(documentService.loadContent(crossDoc))
                .thenReturn(new ByteArrayInputStream("class content".getBytes()));

        RootDirHandle handle = mockRootDir();
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(handle);
        when(executor.compile(any())).thenReturn(Tex2PdfExecutor.Result.failure("mocked", null, 1));

        texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1");

        Path root = handle.getPath();
        assertThat(Files.exists(root.resolve("thesis.tex"))).isTrue();
        assertThat(Files.exists(root.resolve("lib/tud-report.cls"))).isTrue();
        assertThat(Files.readString(root.resolve("lib/tud-report.cls"))).isEqualTo("class content");
    }

    @Test
    void compile_throws_whenCrossProjectFileNotFound() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - thesis.tex
                  - project: other-proj
                    path: missing.cls
                    target: lib/missing.cls
                """);
        mockSourceFile("thesis.tex", "content");
        when(documentService.findByPath(TENANT, "other-proj", "missing.cls"))
                .thenReturn(Optional.empty());
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(mockRootDir());

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("cross-project source file not found: other-proj/missing.cls");
    }

    @Test
    void compile_throws_whenCrossProjectEntryMissingProject() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - path: some-file.cls
                    target: lib/some-file.cls
                """);

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("requires 'project'");
    }

    @Test
    void compile_throws_whenCrossProjectEntryMissingPath() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - project: tud-template
                    target: lib/some-file.cls
                """);

        assertThatThrownBy(() -> texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("requires 'path'");
    }

    @Test
    void parseManifest_crossProjectFile_defaultsTargetToPath() {
        TexComposeManifest m = texService.parseManifest("""
                main: thesis.tex
                files:
                  - project: tud-template
                    path: images/logo.png
                """);
        var entry = (TexComposeManifest.FileEntry.CrossProjectFile) m.files().get(0);
        assertThat(entry.target()).isEqualTo("images/logo.png");
    }

    // ── executor delegation ────────────────────────────────────────

    @Test
    void compile_delegatesToExecutorWithCorrectRequest() {
        mockComposeDoc("""
                main: thesis.tex
                engine: xelatex
                files:
                  - thesis.tex
                """);
        mockSourceFile("thesis.tex", "content");
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(mockRootDir());
        when(executor.compile(any())).thenReturn(Tex2PdfExecutor.Result.failure("mocked", null, 1));

        texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1");

        ArgumentCaptor<Tex2PdfExecutor.Request> captor =
                ArgumentCaptor.forClass(Tex2PdfExecutor.Request.class);
        verify(executor).compile(captor.capture());
        Tex2PdfExecutor.Request req = captor.getValue();
        assertThat(req.mainDocument()).isEqualTo("thesis.tex");
        assertThat(req.effectiveEngine()).isEqualTo("xelatex");
        assertThat(req.tenantId()).isEqualTo(TENANT);
        assertThat(req.projectId()).isEqualTo(PROJECT);
        assertThat(req.processId()).isEqualTo("proc-1");
    }

    @Test
    void resolveExecutor_defaultsToLocal_whenSettingAbsent() {
        assertThat(texService.resolveExecutor(TENANT, PROJECT, "proc-1"))
                .isSameAs(executor);
    }

    @Test
    void resolveExecutor_usesSettingFromCascade() {
        when(settings.getStringValueCascade(TENANT, PROJECT, "proc-1",
                TexService.SETTING_EXECUTOR))
                .thenReturn("local");
        assertThat(texService.resolveExecutor(TENANT, PROJECT, "proc-1"))
                .isSameAs(executor);
    }

    @Test
    void resolveExecutor_selectsRbehzadan_whenSettingSaysSo() {
        Tex2PdfExecutor rbehzadan = mock(Tex2PdfExecutor.class);
        when(rbehzadan.type()).thenReturn("rbehzadan");
        TexService service = new TexService(
                documentService, workspaceService, settings, List.of(executor, rbehzadan));
        when(settings.getStringValueCascade(TENANT, PROJECT, null,
                TexService.SETTING_EXECUTOR))
                .thenReturn("rbehzadan");

        assertThat(service.resolveExecutor(TENANT, PROJECT, null))
                .isSameAs(rbehzadan);
    }

    @Test
    void resolveExecutor_throws_whenTypeUnknown() {
        when(settings.getStringValueCascade(TENANT, PROJECT, null,
                TexService.SETTING_EXECUTOR))
                .thenReturn("nonexistent");
        assertThatThrownBy(() -> texService.resolveExecutor(TENANT, PROJECT, null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void compile_success_importsPdfAndReturnsPath() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - thesis.tex
                """);
        mockSourceFile("thesis.tex", "content");
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(mockRootDir());
        byte[] pdfBytes = "%PDF-1.4 fake".getBytes();
        when(executor.compile(any()))
                .thenReturn(Tex2PdfExecutor.Result.success(pdfBytes, "log lines", 42));

        TexService.TexCompileResult result =
                texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1");

        assertThat(result.success()).isTrue();
        assertThat(result.pdfPath()).isEqualTo("thesis.pdf");
        verify(documentService).createOrReplaceBinary(
                eq(TENANT), eq(PROJECT), eq("thesis.pdf"),
                eq(pdfBytes), eq("application/pdf"),
                eq("thesis.pdf"), any(), eq(null), eq("tex2pdf"));
    }

    @Test
    void compile_failure_returnsErrorAndLogExcerpt() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - thesis.tex
                """);
        mockSourceFile("thesis.tex", "content");
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(mockRootDir());
        when(executor.compile(any()))
                .thenReturn(Tex2PdfExecutor.Result.failure("Compilation error", "log excerpt", 100));

        TexService.TexCompileResult result =
                texService.compile(TENANT, PROJECT, "compose.yaml", "proc-1");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Compilation error");
        assertThat(result.logExcerpt()).isEqualTo("log excerpt");
        // PDF should NOT be imported on failure
        verify(documentService, never()).createOrReplaceBinary(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── derivePdfPath ──────────────────────────────────────────────

    @Test
    void compile_derivesPdfPath_fromComposePath() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - thesis.tex
                """);
        mockSourceFile("docs/thesis/thesis.tex", "content");
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(mockRootDir());
        when(executor.compile(any())).thenReturn(Tex2PdfExecutor.Result.success(
                "fake".getBytes(), null, 1));

        texService.compile(TENANT, PROJECT, "docs/thesis/tex-compose.yaml", "proc-1");

        // Verify compose doc was looked up at the right path
        verify(documentService).findByPath(TENANT, PROJECT, "docs/thesis/tex-compose.yaml");
    }

    @Test
    void compile_resolvesFilesRelativeToComposeDir() {
        mockComposeDoc("""
                main: thesis.tex
                files:
                  - thesis.tex
                """);
        // mock at the resolved path, not the bare path
        mockSourceFile("docs/thesis/thesis.tex", "content");
        when(workspaceService.createRootDir(any(RootDirSpec.class))).thenReturn(mockRootDir());
        when(executor.compile(any())).thenReturn(Tex2PdfExecutor.Result.failure("mocked", null, 1));

        texService.compile(TENANT, PROJECT, "docs/thesis/tex-compose.yaml", "proc-1");

        // Verify source file was looked up at the resolved path
        verify(documentService).findByPath(TENANT, PROJECT, "docs/thesis/thesis.tex");
    }

    // ── result types ──────────────────────────────────────────────

    @Test
    void texCompileResult_success_hasPdfPath() {
        var result = TexService.TexCompileResult.success("out.pdf", 500);
        assertThat(result.success()).isTrue();
        assertThat(result.pdfPath()).isEqualTo("out.pdf");
        assertThat(result.elapsedMs()).isEqualTo(500);
        assertThat(result.error()).isNull();
        assertThat(result.logExcerpt()).isNull();
    }

    @Test
    void texCompileResult_failure_hasError() {
        var result = TexService.TexCompileResult.failure("boom", "log lines", 300);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("boom");
        assertThat(result.logExcerpt()).isEqualTo("log lines");
        assertThat(result.elapsedMs()).isEqualTo(300);
        assertThat(result.pdfPath()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────

    private void mockComposeDoc(String yaml) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("compose-id");
        when(documentService.findByPath(TENANT, PROJECT, "compose.yaml"))
                .thenReturn(Optional.of(doc));
        when(documentService.findByPath(TENANT, PROJECT, "docs/thesis/tex-compose.yaml"))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(yaml);
    }

    private void mockSourceFile(String path, String content) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("doc-" + path);
        when(documentService.findByPath(TENANT, PROJECT, path))
                .thenReturn(Optional.of(doc));
        when(documentService.loadContent(doc))
                .thenReturn(new ByteArrayInputStream(content.getBytes()));
    }

    private RootDirHandle mockRootDir() {
        Path buildDir = tempDir.resolve("tex-build-test");
        try {
            Files.createDirectories(buildDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        WorkspaceDescriptor desc = WorkspaceDescriptor.builder()
                .tenant(TENANT)
                .projectId(PROJECT)
                .dirName("tex-build-test")
                .label("tex2pdf: thesis.tex")
                .type("temp")
                .creatorProcessId("proc-1")
                .createdAt("2026-06-24T10:00:00Z")
                .deleteOnCreatorClose(true)
                .build();
        return RootDirHandle.builder()
                .tenantId(TENANT)
                .projectId(PROJECT)
                .dirName("tex-build-test")
                .type("temp")
                .path(buildDir)
                .descriptor(desc)
                .build();
    }
}
