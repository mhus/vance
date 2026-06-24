package de.mhus.vance.brain.tools.tex;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
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
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
 *   <li>Transport all declared files from document storage into the RootDir</li>
 *   <li>Run {@code latexmk} with the configured engine</li>
 *   <li>On success: read the PDF and import it as a binary document</li>
 *   <li>Dispose the temp RootDir (always, even on failure)</li>
 * </ol>
 *
 * <p>Timeout: 150s hard {@code Process.waitFor} dead-man's-switch.
 * Concurrency: each
 * build gets its own UUID-named RootDir, so parallel builds are isolated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TexService {

    private final DocumentService documentService;
    private final WorkspaceService workspaceService;

    private static final long PROCESS_TIMEOUT_SECONDS = 150;
    private static final int LOG_EXCERPT_LINES = 50;

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
            // 3. Transport files
            transportFiles(tenantId, projectId, composePath, manifest, rootDir);

            // 4. Run latexmk
            LatexmkResult result = runLatexmk(rootDir, manifest);

            long elapsed = System.currentTimeMillis() - start;

            if (!result.success()) {
                return TexCompileResult.failure(
                        result.error(), result.logExcerpt(), elapsed);
            }

            // 5. Read PDF + import as document
            Path pdfPath = rootDir.resolve(manifest.effectiveOutput());
            if (!Files.exists(pdfPath)) {
                return TexCompileResult.failure(
                        "latexmk exited 0 but output PDF not found: "
                                + manifest.effectiveOutput(),
                        result.logExcerpt(), elapsed);
            }

            byte[] pdfBytes = Files.readAllBytes(pdfPath);
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
    private TexComposeManifest parseManifest(@Nullable String yaml) {
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
        List<String> files = new ArrayList<>();
        for (Object f : rawList) {
            if (!(f instanceof String s) || s.isBlank()) {
                throw new ToolException("tex-compose manifest: 'files' entries must be non-empty strings");
            }
            files.add(s.trim());
        }

        return new TexComposeManifest(
                main.trim(),
                readString(map, "engine"),
                readString(map, "passes"),
                readString(map, "output"),
                readString(map, "outputPath"),
                files);
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
        for (String filePath : manifest.files()) {
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
        log.debug("tex2pdf: transported {} files to {}", manifest.files().size(), rootDir);
    }

    // ──────────────────── latexmk execution ────────────────────

    /**
     * On macOS, TeX Live installs into {@code /Library/TeX/texbin} which is
     * added to PATH by {@code path_helper} in login shells — but the JVM
     * inherits only the minimal GUI PATH. We prepend the common TeX
     * locations so that {@code latexmk} is found without manual PATH setup.
     */
    /**
     * Resolves the absolute path to {@code latexmk}. On macOS, the JVM
     * inherits only the minimal GUI PATH which does not include TeX Live
     * locations. Instead of relying on PATH resolution, we check the known
     * locations directly and return the first match. Falls back to
     * "latexmk" (bare name) so that on Linux/Docker the normal PATH lookup
     * still works.
     */
    private String resolveLatexmkBin() {
        List<String> candidates = List.of(
                "/Library/TeX/texbin/latexmk",                                   // macOS — MacTeX
                "/usr/local/texlive/2024/bin/universal-darwin/latexmk",          // TeX Live 2024
                "/usr/local/texlive/2024/bin/x86_64-darwin/latexmk",
                "/usr/local/texlive/2023/bin/universal-darwin/latexmk",          // TeX Live 2023
                "/usr/local/texlive/2023/bin/x86_64-darwin/latexmk",
                "/usr/local/bin/latexmk",                                        // Homebrew
                "/opt/homebrew/bin/latexmk",                                     // Apple Silicon Homebrew
                "/usr/bin/latexmk");                                             // Linux
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                log.debug("tex2pdf: found latexmk at {}", candidate);
                return candidate;
            }
        }
        log.debug("tex2pdf: latexmk not found in known locations, falling back to bare 'latexmk'");
        return "latexmk";
    }

    private void augmentPath(ProcessBuilder pb) {
        String path = pb.environment().get("PATH");
        if (path == null) path = "";
        List<String> extra = List.of(
                "/Library/TeX/texbin",           // macOS — MacTeX
                "/usr/local/texlive/2024/bin/universal-darwin",
                "/usr/local/texlive/2023/bin/universal-darwin",
                "/opt/homebrew/bin");             // Apple Silicon homebrew
        List<String> toAdd = new ArrayList<>();
        for (String dir : extra) {
            if (!path.contains(dir)) toAdd.add(dir);
        }
        if (!toAdd.isEmpty()) {
            String newPath = String.join(":", toAdd) + (path.isEmpty() ? "" : ":" + path);
            pb.environment().put("PATH", newPath);
            log.debug("tex2pdf: augmented PATH to {}", newPath);
        }
    }

    private LatexmkResult runLatexmk(Path rootDir, TexComposeManifest manifest) throws IOException {
        String engineFlag = switch (manifest.effectiveEngine()) {
            case "xelatex" -> "-pdfxe";
            case "lualatex" -> "-pdflua";
            default -> "-pdf";
        };

        String latexmkBin = resolveLatexmkBin();
        List<String> cmd = List.of(
                latexmkBin,
                engineFlag,
                "-interaction=nonstopmode",
                "-halt-on-error",
                manifest.main());

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(rootDir.toFile())
                .redirectErrorStream(true);
        augmentPath(pb);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return new LatexmkResult(false,
                    "Cannot start latexmk — is TeX Live installed? " + e.getMessage(), null);
        }

        String output;
        try {
            boolean finished = p.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new LatexmkResult(false,
                        "TIMEOUT: latexmk did not finish within " + PROCESS_TIMEOUT_SECONDS + "s", null);
            }
            output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new LatexmkResult(false, "Interrupted", null);
        }

        int exit = p.exitValue();
        if (exit == 0) {
            return new LatexmkResult(true, null, null);
        }

        // Read .log file for error details
        String logFileName = manifest.main().replaceAll("\\.tex$", ".log");
        Path logPath = rootDir.resolve(logFileName);
        String logExcerpt = readLogExcerpt(logPath, LOG_EXCERPT_LINES);
        return new LatexmkResult(false,
                logExcerpt != null ? logExcerpt : truncate(output, 2000),
                logExcerpt);
    }

    private @Nullable String readLogExcerpt(Path logPath, int maxLines) {
        if (!Files.exists(logPath)) return null;
        try {
            List<String> lines = Files.readAllLines(logPath, java.nio.charset.StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - maxLines);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            log.debug("Could not read log file {}: {}", logPath, e.toString());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    // ──────────────────── result types ────────────────────

    public record LatexmkResult(boolean success, @Nullable String error, @Nullable String logExcerpt) {}

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
