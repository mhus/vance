package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Moves content between the document store and the workspace for a compose run.
 *
 * <ul>
 *   <li><b>import</b>: {@code vance:<path>} document → workspace file, or an
 *       {@code http(s)://…} URL → workspace file.</li>
 *   <li><b>export</b>: workspace file → {@code vance:<path>} document (textual
 *       mime → editable text document, otherwise binary document).</li>
 * </ul>
 *
 * <p>v1 supports the {@code WORK} target only (server-side workspace). CLIENT /
 * DAEMON transport (via the {@code file_*} dispatcher) is deferred — see
 * {@code planning/damogran-system.md} open points.
 */
@Slf4j
@Service
public class DamogranTransport {

    private static final String VANCE_SCHEME = "vance:";
    private static final String CREATED_BY = "damogran";
    /** Hard ceiling on a single exported file (50 MiB). */
    private static final long MAX_EXPORT_BYTES = 50L * 1024 * 1024;

    private final DocumentService documentService;
    private final WorkspaceService workspaceService;
    private final HttpClient httpClient;

    public DamogranTransport(DocumentService documentService, WorkspaceService workspaceService) {
        this.documentService = documentService;
        this.workspaceService = workspaceService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ──────────────────── import ────────────────────

    public void doImport(DamogranContext ctx, ImportEntry entry) {
        requireWork(ctx, "import");
        byte[] bytes = loadImportSource(ctx, entry.from());
        writeWorkspaceFile(ctx, entry.to(), bytes);
        log.debug("Damogran import: {} → {}/{}", entry.from(), ctx.workspaceDirName(), entry.to());
    }

    private byte[] loadImportSource(DamogranContext ctx, String from) {
        if (from.startsWith(VANCE_SCHEME)) {
            String path = stripVance(from);
            Optional<DocumentDocument> doc =
                    documentService.findByPath(ctx.tenantId(), ctx.projectId(), path);
            if (doc.isEmpty()) {
                throw new DamogranException("import source not found: " + from);
            }
            try (InputStream in = documentService.loadContent(doc.get())) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new DamogranException("import failed reading " + from + ": " + e.getMessage(), e);
            }
        }
        if (from.startsWith("http://") || from.startsWith("https://")) {
            return httpGet(from);
        }
        throw new DamogranException(
                "unsupported import source '" + from + "' (expected vance:<path> or http(s)://…)");
    }

    private byte[] httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new DamogranException(
                        "import HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (IOException e) {
            throw new DamogranException("import fetch failed for " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DamogranException("import fetch interrupted for " + url, e);
        }
    }

    private void writeWorkspaceFile(DamogranContext ctx, String to, byte[] bytes) {
        Path resolved = workspaceService.resolve(
                ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(), to);
        try {
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            Files.write(resolved, bytes);
        } catch (IOException e) {
            throw new DamogranException("import write failed for " + to + ": " + e.getMessage(), e);
        }
    }

    // ──────────────────── export ────────────────────

    public void doExport(DamogranContext ctx, ExportEntry entry) {
        requireWork(ctx, "export");
        if (!entry.to().startsWith(VANCE_SCHEME)) {
            throw new DamogranException(
                    "export target '" + entry.to() + "' must be a vance:<path> document URI");
        }
        String docPath = stripVance(entry.to());
        byte[] bytes = workspaceService.readBytes(
                ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(), entry.from(), MAX_EXPORT_BYTES);
        String mime = DamogranMime.mimeForPath(docPath);

        if (DamogranMime.isText(mime)) {
            documentService.upsertText(ctx.tenantId(), ctx.projectId(), docPath,
                    null, null, new String(bytes, StandardCharsets.UTF_8), CREATED_BY);
        } else {
            documentService.createOrReplaceBinary(ctx.tenantId(), ctx.projectId(), docPath,
                    bytes, mime, null, null, null, CREATED_BY);
        }
        log.debug("Damogran export: {}/{} → {} ({})",
                ctx.workspaceDirName(), entry.from(), entry.to(), mime);
    }

    // ──────────────────── helpers ────────────────────

    private void requireWork(DamogranContext ctx, String op) {
        if (!ctx.isWork()) {
            throw new DamogranException(
                    op + " is only supported for target WORK (was: " + ctx.target() + ")");
        }
    }

    private static String stripVance(String uri) {
        String path = uri.substring(VANCE_SCHEME.length());
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isBlank()) {
            throw new DamogranException("empty document path in URI: " + uri);
        }
        return path;
    }
}
