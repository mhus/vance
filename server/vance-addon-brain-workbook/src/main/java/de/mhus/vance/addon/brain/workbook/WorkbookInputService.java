package de.mhus.vance.addon.brain.workbook;

import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Backend for the {@code vance-input} block — a single editable text value
 * bound to a plain text document. The bound file is treated <b>verbatim</b>:
 * the whole content is the value, there is no front-matter header split. The
 * recompute {@code saveScript} is block-specific and comes from the fence
 * (mirroring {@link WorkbookFormService}); embedded views of the same
 * document refresh live via the documents channel.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkbookInputService {

    private final DocumentService documentService;
    private final ScriptExecutor scriptExecutor;
    private final ToolDispatcher toolDispatcher;
    private final SessionService sessionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    /** Wall-clock cap for an onSave recompute script. */
    private static final Duration ON_SAVE_TIMEOUT = Duration.ofSeconds(30);

    /** Full text content of the bound document (verbatim, no header split). */
    public String loadInput(String tenantId, String projectId, String docPath) {
        Optional<DocumentDocument> doc = documentService.findByPath(tenantId, projectId, docPath);
        if (doc.isEmpty()) return "";
        return read(doc.get());
    }

    /**
     * Persist the edited content into the bound document verbatim, then run
     * the fence {@code saveScript} recompute hook (if any). A missing document
     * is created. When {@code session} is set, the script runs inside a
     * per-input system session (fence {@code session: true}).
     */
    public void saveInput(
            String tenantId, String projectId, String docPath,
            @Nullable String content, @Nullable String saveScript,
            boolean session, String editorId) {
        String full = content != null ? content : "";
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, docPath);
        if (existing.isPresent()) {
            documentService.replaceContent(
                    existing.get().getId(),
                    new ByteArrayInputStream(full.getBytes(StandardCharsets.UTF_8)),
                    DocumentService.mimeFromPath(docPath),
                    DocumentService.WriterIdentity.of(editorId, null, null),
                    contextFactory.writeActor(tenantId, editorId, docPath));
        } else {
            documentService.createText(
                    tenantId, projectId, docPath, null, null, full, editorId,
                    contextFactory.writeActor(tenantId, editorId, docPath));
        }
        log.info("WorkbookInputService.saveInput tenant='{}' doc='{}' len={}",
                tenantId, docPath, full.length());

        runOnSave(tenantId, projectId, docPath, saveScript, session, editorId);
    }

    /**
     * Create a fresh empty text document in {@code folder}, returning its
     * path so the caller can insert a {@code vance-input} block for it.
     * With a {@code name} the file is {@code <slug>.<ext>} — the extension
     * the user typed ({@code yoyoyo.txt} → {@code yoyoyo.txt}) is preserved
     * and decides the document kind; without one it defaults to {@code .md}
     * (error if it exists). Without a {@code name} the first free
     * {@code input-<n>.md} is used.
     */
    public String createInput(
            String tenantId, String projectId, String folder,
            @Nullable String name, String editorId) {
        String base = folder == null ? "" : folder.strip();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String fileName = fileNameFrom(name);
        if (fileName != null) {
            String path = (base.isEmpty() ? "" : base + "/") + fileName;
            if (documentService.findByPath(tenantId, projectId, path).isPresent()) {
                throw new ToolException("document already exists: " + path);
            }
            documentService.createText(tenantId, projectId, path, null, null, "", editorId,
                    contextFactory.writeActor(tenantId, editorId, path));
            log.info("WorkbookInputService.createInput tenant='{}' doc='{}'", tenantId, path);
            return path;
        }
        for (int n = 1; n <= 9999; n++) {
            String path = (base.isEmpty() ? "" : base + "/") + "input-" + n + ".md";
            if (documentService.findByPath(tenantId, projectId, path).isEmpty()) {
                documentService.createText(tenantId, projectId, path, null, null, "", editorId,
                    contextFactory.writeActor(tenantId, editorId, path));
                log.info("WorkbookInputService.createInput tenant='{}' doc='{}'", tenantId, path);
                return path;
            }
        }
        throw new ToolException("Could not allocate a free input document name in " + base);
    }

    // ---- onSave --------------------------------------------------------

    /**
     * Run the fence {@code saveScript} synchronously after the body was
     * written: the script reads fresh data via {@code vance.documents.*} and
     * recomputes derived files (live-push updates embeds). A failure surfaces
     * as {@link ToolException} (→ HTTP 500); the data stays written. No
     * {@code saveScript} → no-op. When {@code session} is set, the run gets a
     * per-input system session so session-bound tools / LLM are available.
     */
    private void runOnSave(
            String tenantId, String projectId, String docPath,
            @Nullable String fenceScript, boolean session, String editorId) {
        if (fenceScript == null || fenceScript.isBlank()) return;
        String scriptPath = resolveRelative(
                docPath, WorkbookFormService.stripVanceScheme(fenceScript).strip());
        if (!scriptPath.toLowerCase(Locale.ROOT).endsWith(".js")) {
            throw new ToolException(
                    "saveScript must be a .js document (got '" + scriptPath
                            + "') — only in-JVM JavaScript is supported in v1");
        }
        DocumentDocument scriptDoc = documentService.findByPath(tenantId, projectId, scriptPath)
                .orElseThrow(() -> new ToolException("saveScript not found: " + scriptPath));
        String code = read(scriptDoc);

        String sessionId = session
                ? resolveInputSession(tenantId, projectId, docPath, editorId)
                : null;
        ToolInvocationContext scope =
                new ToolInvocationContext(tenantId, projectId, sessionId, null, editorId);
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope);
        try {
            scriptExecutor.run(new ScriptRequest(
                    "js", code, "input-saveScript:" + scriptPath, tools, ON_SAVE_TIMEOUT)
                    .withDocumentBasePath(WorkbookFormService.parentPath(scriptPath)));
            log.info("WorkbookInputService.runOnSave tenant='{}' script='{}' session={} ok",
                    tenantId, scriptPath, session);
        } catch (ScriptExecutionException e) {
            log.warn("WorkbookInputService.runOnSave tenant='{}' script='{}' failed [{}]: {}",
                    tenantId, scriptPath, e.errorClass(), e.getMessage());
            throw new ToolException(
                    "saveScript '" + scriptPath + "' failed: " + e.getMessage());
        }
    }

    /**
     * Reuse-or-create a per-input system session (deterministic display name
     * {@code _input_<docPath>}, {@code system=true}) so a fence {@code saveScript}
     * that opted in ({@code session: true}) runs inside a session scope. Same
     * lazy-reuse pattern as {@code WorkbookFormService#resolveFormSession}.
     */
    private String resolveInputSession(
            String tenantId, String projectId, String docPath, String runAs) {
        String displayName = "_input_" + docPath.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sessionService.findSystemSession(tenantId, projectId, displayName)
                .map(SessionDocument::getSessionId)
                .orElseGet(() -> {
                    SessionDocument created = sessionService.create(
                            tenantId, runAs, projectId, displayName,
                            Profiles.DAEMON, "workbook-input", null, /*system*/ true);
                    sessionService.markBootstrapped(created.getSessionId());
                    return created.getSessionId();
                });
    }

    // ---- helpers -------------------------------------------------------

    /**
     * Build a {@code <slug>.<ext>} file name from a free-text user name.
     * The basename is slugified (non-alphanumerics collapsed to {@code -});
     * an extension the user typed is preserved and lower-cased so the
     * document kind follows it ({@code yoyoyo.txt} stays text, not
     * markdown). Without an extension the file defaults to {@code .md}.
     * Returns {@code null} for a blank name (caller falls back to
     * {@code input-<n>.md}).
     */
    static @Nullable String fileNameFrom(@Nullable String name) {
        if (name == null) return null;
        String trimmed = name.strip();
        if (trimmed.isEmpty()) return null;
        String base = trimmed;
        String ext = "md";
        int dot = trimmed.lastIndexOf('.');
        if (dot > 0 && dot < trimmed.length() - 1) {
            String candidate = trimmed.substring(dot + 1);
            if (candidate.matches("[A-Za-z0-9]{1,8}")) {
                base = trimmed.substring(0, dot);
                ext = candidate.toLowerCase(Locale.ROOT);
            }
        }
        String slug = slugify(base);
        return slug.isBlank() ? null : slug + "." + ext;
    }

    private static String slugify(String name) {
        return name.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    }

    /**
     * Resolve {@code rel} against the folder of {@code basePath}. A
     * leading-slash {@code rel} is treated as project-absolute.
     */
    static String resolveRelative(String basePath, String rel) {
        if (rel.startsWith("/")) return rel.substring(1);
        int slash = basePath.lastIndexOf('/');
        String parent = slash >= 0 ? basePath.substring(0, slash) : "";
        return parent.isEmpty() ? rel : parent + "/" + rel;
    }

    private String read(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Could not read '" + doc.getPath() + "': " + e.getMessage());
        }
    }
}
