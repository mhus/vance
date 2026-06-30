package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.FrontMatter;
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
 * bound to a plain text document. The document may carry a front-matter
 * header (same {@code --- key: value ---} format as markdown, supported for
 * {@code .txt} too); the header holds the optional {@code onSave} config and
 * is split off so the block edits only the body. Save writes the body back
 * under the preserved header and runs the {@code onSave} script (mirroring
 * {@link WorkspaceFormService}); embedded views of the same document refresh
 * live via the documents channel.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceInputService {

    private final DocumentService documentService;
    private final ScriptExecutor scriptExecutor;
    private final ToolDispatcher toolDispatcher;
    private final SessionService sessionService;

    /** Wall-clock cap for an onSave recompute script. */
    private static final Duration ON_SAVE_TIMEOUT = Duration.ofSeconds(30);
    /** Front-matter header key holding the onSave script path. */
    private static final String KEY_ON_SAVE = "onSave";
    /** Front-matter header key holding the onSave session flag. */
    private static final String KEY_SESSION = "session";

    /**
     * Editable body of the bound document (header stripped) plus its
     * {@code onSave} config read from the front-matter header.
     */
    public record LoadedInput(
            String content,
            @Nullable String onSaveScript,
            boolean onSaveSession) {}

    /** Body text + onSave config of the bound document (empty if missing). */
    public LoadedInput loadInput(String tenantId, String projectId, String docPath) {
        Optional<DocumentDocument> doc = documentService.findByPath(tenantId, projectId, docPath);
        if (doc.isEmpty()) return new LoadedInput("", null, false);
        FrontMatter fm = FrontMatter.parse(read(doc.get()));
        return new LoadedInput(fm.body(), fm.get(KEY_ON_SAVE), fm.getBoolean(KEY_SESSION));
    }

    /**
     * Persist the edited body into the bound document, preserving the
     * front-matter header, then run the {@code onSave} recompute hook
     * declared in that header. A new document is created header-less.
     */
    public void saveInput(
            String tenantId, String projectId, String docPath,
            @Nullable String content, String editorId) {
        String body = content != null ? content : "";
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, docPath);
        FrontMatter fm = existing.map(d -> FrontMatter.parse(read(d)))
                .orElseGet(() -> FrontMatter.parse(""));
        fm.setBody(body);
        String full = fm.render();
        byte[] bytes = full.getBytes(StandardCharsets.UTF_8);
        if (existing.isPresent()) {
            documentService.replaceContent(
                    existing.get().getId(),
                    new ByteArrayInputStream(bytes),
                    DocumentService.mimeFromPath(docPath),
                    editorId);
        } else {
            documentService.createText(
                    tenantId, projectId, docPath, null, null, full, editorId);
        }
        log.info("WorkspaceInputService.saveInput tenant='{}' doc='{}' len={}",
                tenantId, docPath, body.length());

        runOnSave(tenantId, projectId, docPath, fm, editorId);
    }

    /**
     * Update the design-mode {@code onSave} settings (script path + session
     * flag) in the bound document's front-matter header, preserving the body.
     */
    public void saveSettings(
            String tenantId, String projectId, String docPath,
            @Nullable String runScript, boolean session, String editorId) {
        DocumentDocument docDoc = documentService.findByPath(tenantId, projectId, docPath)
                .orElseThrow(() -> new ToolException("input document '" + docPath + "' not found"));
        FrontMatter fm = FrontMatter.parse(read(docDoc));
        fm.set(KEY_ON_SAVE, runScript);
        fm.setBoolean(KEY_SESSION, session);
        documentService.replaceContent(
                docDoc.getId(),
                new ByteArrayInputStream(fm.render().getBytes(StandardCharsets.UTF_8)),
                DocumentService.mimeFromPath(docPath),
                editorId);
        log.info("WorkspaceInputService.saveSettings tenant='{}' doc='{}' session={} script='{}'",
                tenantId, docPath, session, runScript);
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
            documentService.createText(tenantId, projectId, path, null, null, "", editorId);
            log.info("WorkspaceInputService.createInput tenant='{}' doc='{}'", tenantId, path);
            return path;
        }
        for (int n = 1; n <= 9999; n++) {
            String path = (base.isEmpty() ? "" : base + "/") + "input-" + n + ".md";
            if (documentService.findByPath(tenantId, projectId, path).isEmpty()) {
                documentService.createText(tenantId, projectId, path, null, null, "", editorId);
                log.info("WorkspaceInputService.createInput tenant='{}' doc='{}'", tenantId, path);
                return path;
            }
        }
        throw new ToolException("Could not allocate a free input document name in " + base);
    }

    // ---- onSave --------------------------------------------------------

    /**
     * Run the document's {@code onSave} script (from the front-matter header)
     * synchronously after the body was written. Same contract as
     * {@link WorkspaceFormService}: the script reads fresh data via
     * {@code vance.documents.*} and recomputes derived files; their writes
     * fan out to the client via the documents live-push. A script failure
     * surfaces as a {@link ToolException} (→ HTTP 500); the data stays
     * written. No header / no {@code onSave} key → no-op.
     */
    private void runOnSave(
            String tenantId, String projectId, String docPath,
            FrontMatter fm, String editorId) {
        String runScript = fm.get(KEY_ON_SAVE);
        if (runScript == null || runScript.isBlank()) return;

        String scriptPath = resolveRelative(docPath, runScript.strip());
        if (!scriptPath.toLowerCase(Locale.ROOT).endsWith(".js")) {
            throw new ToolException(
                    "onSave script must be a .js document (got '" + scriptPath
                            + "') — only in-JVM JavaScript is supported in v1");
        }
        DocumentDocument scriptDoc = documentService.findByPath(tenantId, projectId, scriptPath)
                .orElseThrow(() -> new ToolException("onSave script not found: " + scriptPath));
        String code = read(scriptDoc);

        // When the input's settings request it, attach a per-input system
        // session so the script can use session-scoped tools / LLM.
        String sessionId = fm.getBoolean(KEY_SESSION)
                ? resolveInputSession(tenantId, projectId, docPath, editorId)
                : null;
        ToolInvocationContext scope =
                new ToolInvocationContext(tenantId, projectId, sessionId, null, editorId);
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope);
        try {
            scriptExecutor.run(new ScriptRequest(
                    "js", code, "input-onSave:" + scriptPath, tools, ON_SAVE_TIMEOUT));
            log.info("WorkspaceInputService.runOnSave tenant='{}' script='{}' ok",
                    tenantId, scriptPath);
        } catch (ScriptExecutionException e) {
            log.warn("WorkspaceInputService.runOnSave tenant='{}' script='{}' failed [{}]: {}",
                    tenantId, scriptPath, e.errorClass(), e.getMessage());
            throw new ToolException(
                    "onSave script '" + scriptPath + "' failed: " + e.getMessage());
        }
    }

    /**
     * Reuse-or-create a per-input system session (deterministic display name
     * {@code _input_<docPath>}, {@code system=true}) so an onSave script that
     * opted in ({@code session: true}) runs inside a session scope. Same lazy
     * pattern as the form service's {@code _form_<docPath>} session.
     */
    private String resolveInputSession(
            String tenantId, String projectId, String docPath, String runAs) {
        String displayName = "_input_" + docPath.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sessionService.findSystemSession(tenantId, projectId, displayName)
                .map(SessionDocument::getSessionId)
                .orElseGet(() -> {
                    SessionDocument created = sessionService.create(
                            tenantId, runAs, projectId, displayName,
                            Profiles.DAEMON, "workspace-input", null, /*system*/ true);
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
