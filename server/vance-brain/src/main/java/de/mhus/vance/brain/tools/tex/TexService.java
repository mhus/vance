package de.mhus.vance.brain.tools.tex;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Orchestrates LaTeX compilation following the Fenchurch pattern:
 * synchronous {@code @Service} that does the real work, called by a
 * thin {@code Tool} wrapper.
 *
 * <p>Flow (see {@code planning/latex-support.md} §3):
 * <ol>
 *   <li>Load + parse the tex-compose manifest document</li>
 *   <li>Create a temp workspace RootDir</li>
 *   <li>Transport all declared files from document storage into the RootDir
 *       (same-project and cross-project references)</li>
 *   <li>Delegate compilation to {@link Tex2PdfExecutor}</li>
 *   <li>On success: import the PDF as a binary document</li>
 *   <li>Dispose the temp RootDir (always, even on failure)</li>
 * </ol>
 *
 * <p>The executor is selected at runtime via the {@code tex.executor}
 * setting (Project → {@code _tenant} cascade), falling back to the
 * {@code vance.tex.executor} application property. The executor handles
 * only the compilation — manifest parsing, file transport, and PDF
 * import stay here.
 */
@Service
@Slf4j
public class TexService {

    static final String SETTING_EXECUTOR = "tex.executor";

    private final DocumentService documentService;
    private final WorkspaceService workspaceService;
    private final SettingService settings;
    private final List<Tex2PdfExecutor> executors;

    @Value("${vance.tex.executor:local}")
    private String defaultExecutorType;

    public TexService(
            DocumentService documentService,
            WorkspaceService workspaceService,
            SettingService settings,
            List<Tex2PdfExecutor> executors) {
        this.documentService = documentService;
        this.workspaceService = workspaceService;
        this.settings = settings;
        this.executors = executors != null ? executors : List.of();
    }

    public TexCompileResult compile(String tenantId, String projectId, String composePath,
                                     @Nullable String processId) {
        long start = System.currentTimeMillis();

        // 1. Load compose manifest
        DocumentDocument composeDoc = documentService
                .findByPath(tenantId, projectId, composePath)
                .orElseThrow(() -> new ToolException(
                        "tex-compose document not found: " + composePath));
        TexComposeManifest manifest = parseManifest(documentService.readContent(composeDoc));
        String composeDir = composePath.contains("/")
                ? composePath.substring(0, composePath.lastIndexOf('/'))
                : "";

        // 2. Create temp workspace RootDir
        String dirName = "tex-build-" + UUID.randomUUID();
        RootDirSpec spec = RootDirSpec.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .type("temp")
                .creatorProcessId(processId != null ? processId : "tex2pdf-standalone")
                .deleteOnCreatorClose(true)
                .labelHint("tex2pdf: " + manifest.main())
                .build();
        RootDirHandle handle = workspaceService.createRootDir(spec);
        Path rootDir = handle.getPath();
        String actualDirName = handle.getDirName();

        try {
            // 3. Transport files (same-project + cross-project references)
            transportFiles(tenantId, projectId, composePath, manifest, rootDir);

            // 4. Resolve executor from settings cascade
            Tex2PdfExecutor executor = resolveExecutor(tenantId, projectId, processId);

            // 5. Compile via executor
            Tex2PdfExecutor.Request execReq = new Tex2PdfExecutor.Request(
                    manifest.main(),
                    manifest.effectiveEngine(),
                    rootDir,
                    tenantId,
                    projectId,
                    processId);
            Tex2PdfExecutor.Result result = executor.compile(execReq);

            long elapsed = System.currentTimeMillis() - start;

            if (!result.success()) {
                return TexCompileResult.failure(
                        result.error(), result.log(), elapsed);
            }

            // 6. Import PDF as document
            byte[] pdfBytes = result.pdf();
            String pdfDocPath = manifest.resolvePdfDocPath(composeDir);

            documentService.createOrReplaceBinary(
                    tenantId, projectId, pdfDocPath,
                    pdfBytes, "application/pdf",
                    manifest.effectiveOutput(),
                    List.of("tex2pdf"), null, "tex2pdf");

            return TexCompileResult.success(pdfDocPath, elapsed);

        } catch (IOException e) {
            log.warn("tex2pdf IO error for {}/{}: {}", tenantId, projectId, e.toString());
            throw new ToolException("tex2pdf IO error: " + e.getMessage());
        } finally {
            // 6. Cleanup — always
            try {
                workspaceService.disposeRootDir(tenantId, projectId, actualDirName);
            } catch (RuntimeException e) {
                log.warn("Failed to dispose tex2pdf RootDir {}: {}", actualDirName, e.toString());
            }
        }
    }

    // ──────────────────── manifest parsing ────────────────────

    @SuppressWarnings("unchecked")
    TexComposeManifest parseManifest(@Nullable String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new ToolException("tex-compose manifest is empty");
        }
        Yaml yamlParser = new Yaml();
        Map<String, Object> map;
        try {
            map = yamlParser.load(yaml);
        } catch (RuntimeException e) {
            throw new ToolException("tex-compose manifest is not valid YAML: " + e.getMessage());
        }
        if (map == null) {
            throw new ToolException("tex-compose manifest is empty");
        }

        String main = readString(map, "main");
        if (main == null || main.isBlank()) {
            throw new ToolException("tex-compose manifest: 'main' is required");
        }

        Object filesRaw = map.get("files");
        if (!(filesRaw instanceof List<?> rawList) || rawList.isEmpty()) {
            throw new ToolException("tex-compose manifest: 'files' is required and must be non-empty");
        }
        List<TexComposeManifest.FileEntry> files = new ArrayList<>();
        for (Object f : rawList) {
            files.add(parseFileEntry(f));
        }

        return new TexComposeManifest(
                main.trim(),
                readString(map, "engine"),
                readString(map, "passes"),
                readString(map, "output"),
                readString(map, "outputPath"),
                files);
    }

    @SuppressWarnings("unchecked")
    private TexComposeManifest.FileEntry parseFileEntry(Object f) {
        if (f instanceof String s) {
            if (s.isBlank()) {
                throw new ToolException("tex-compose manifest: 'files' entries must be non-empty");
            }
            return new TexComposeManifest.FileEntry.LocalFile(s.trim());
        }
        if (f instanceof Map<?, ?> map) {
            String project = readString((Map<String, Object>) map, "project");
            String path = readString((Map<String, Object>) map, "path");
            String target = readString((Map<String, Object>) map, "target");
            if (project == null || project.isBlank()) {
                throw new ToolException("tex-compose manifest: cross-project file entry requires 'project'");
            }
            if (path == null || path.isBlank()) {
                throw new ToolException("tex-compose manifest: cross-project file entry requires 'path'");
            }
            return new TexComposeManifest.FileEntry.CrossProjectFile(
                    project.trim(), path.trim(),
                    target != null ? target.trim() : path.trim());
        }
        throw new ToolException("tex-compose manifest: 'files' entries must be strings or maps with project/path/target");
    }

    // ──────────────────── executor resolution ────────────────────

    /**
     * Resolves the executor type from the settings cascade
     * ({@code think-process → project → _tenant}), falling back to the
     * {@code vance.tex.executor} application property. Then finds the
     * matching {@link Tex2PdfExecutor} bean by {@link Tex2PdfExecutor#type()}.
     */
    Tex2PdfExecutor resolveExecutor(String tenantId, @Nullable String projectId,
                                     @Nullable String processId) {
        String type = settings.getStringValueCascade(
                tenantId, projectId, processId, SETTING_EXECUTOR);
        if (type == null || type.isBlank()) {
            type = defaultExecutorType != null ? defaultExecutorType : "local";
        }
        type = type.trim();

        for (Tex2PdfExecutor exec : executors) {
            if (exec.type().equalsIgnoreCase(type)) {
                return exec;
            }
        }
        throw new ToolException(
                "No tex2pdf executor found for type '" + type
                        + "'. Available: " + executors.stream()
                                .map(Tex2PdfExecutor::type)
                                .toList()
                        + ". Check the '" + SETTING_EXECUTOR
                        + "' setting or 'vance.tex.executor' property.");
    }

    private static @Nullable String readString(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }

    // ──────────────────── file transport ────────────────────

    private void transportFiles(String tenantId, String projectId, String composePath,
                                TexComposeManifest manifest, Path rootDir) throws IOException {
        String composeDir = composePath.contains("/")
                ? composePath.substring(0, composePath.lastIndexOf('/'))
                : "";
        for (TexComposeManifest.FileEntry entry : manifest.files()) {
            switch (entry) {
                case TexComposeManifest.FileEntry.LocalFile f ->
                    transportLocal(tenantId, projectId, composeDir, f, rootDir);
                case TexComposeManifest.FileEntry.CrossProjectFile f ->
                    transportCrossProject(tenantId, composeDir, f, rootDir);
            }
        }
        log.debug("tex2pdf: transported {} files to {}", manifest.files().size(), rootDir);
    }

    private void transportLocal(String tenantId, String projectId, String composeDir,
                               TexComposeManifest.FileEntry.LocalFile f, Path rootDir) throws IOException {
        String filePath = f.path();
        // File paths in the manifest are relative to the compose document's directory.
        // Resolve them to full document paths for lookup.
        String fullDocPath = composeDir.isEmpty() ? filePath : composeDir + "/" + filePath;
        DocumentDocument doc = documentService
                .findByPath(tenantId, projectId, fullDocPath)
                .orElseThrow(() -> new ToolException(
                        "tex2pdf: source file not found: " + fullDocPath));

        // In the workspace, keep the relative path so latexmk can find them.
        Path target = rootDir.resolve(filePath);
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        try (InputStream in = documentService.loadContent(doc)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void transportCrossProject(String tenantId, String composeDir,
                                       TexComposeManifest.FileEntry.CrossProjectFile f, Path rootDir) throws IOException {
        // documentService.findByPath checks ACL — throws if the user lacks read access
        DocumentDocument doc = documentService
                .findByPath(tenantId, f.project(), f.path())
                .orElseThrow(() -> new ToolException(
                        "tex2pdf: cross-project source file not found: " + f.project() + "/" + f.path()));

        Path target = rootDir.resolve(f.target());
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        try (InputStream in = documentService.loadContent(doc)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("tex2pdf: transported cross-project file {}/{} → {}",
                f.project(), f.path(), f.target());
    }

    // ──────────────────── result types ────────────────────

    public record TexCompileResult(
            boolean success,
            @Nullable String pdfPath,
            @Nullable String error,
            @Nullable String logExcerpt,
            long elapsedMs) {
        public static TexCompileResult success(String pdfPath, long elapsedMs) {
            return new TexCompileResult(true, pdfPath, null, null, elapsedMs);
        }
        public static TexCompileResult failure(@Nullable String error, @Nullable String logExcerpt, long elapsedMs) {
            return new TexCompileResult(false, null, error, logExcerpt, elapsedMs);
        }
    }
}
