package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs a project {@code .js} document synchronously in-JVM (GraalJS) with
 * {@code vance.documents.*} bound to the caller's tenant/project scope.
 * Backs the {@code vance-button} block ({@code type: script}); the script
 * reads/writes documents, embedded views refresh live via the documents
 * channel. A failure surfaces as {@link ToolException} (→ HTTP 500).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceScriptService {

    private final DocumentService documentService;
    private final ScriptExecutor scriptExecutor;
    private final ToolDispatcher toolDispatcher;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Execute the {@code .js} document at {@code scriptPath}. */
    public void run(String tenantId, String projectId, String scriptPath,
                    @Nullable String editorId) {
        if (scriptPath == null || scriptPath.isBlank()) {
            throw new ToolException("button script path must not be empty");
        }
        if (!scriptPath.toLowerCase(Locale.ROOT).endsWith(".js")) {
            throw new ToolException(
                    "button script must be a .js document (got '" + scriptPath
                            + "') — only in-JVM JavaScript is supported in v1");
        }
        DocumentDocument scriptDoc = documentService.findByPath(tenantId, projectId, scriptPath)
                .orElseThrow(() -> new ToolException("button script not found: " + scriptPath));
        String code = readContent(scriptDoc);

        ToolInvocationContext scope =
                new ToolInvocationContext(tenantId, projectId, null, null, editorId);
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope);
        try {
            scriptExecutor.run(new ScriptRequest(
                    "js", code, "button:" + scriptPath, tools, TIMEOUT));
            log.info("WorkspaceScriptService.run tenant='{}' script='{}' ok", tenantId, scriptPath);
        } catch (ScriptExecutionException e) {
            log.warn("WorkspaceScriptService.run tenant='{}' script='{}' failed [{}]: {}",
                    tenantId, scriptPath, e.errorClass(), e.getMessage());
            throw new ToolException(
                    "button script '" + scriptPath + "' failed: " + e.getMessage());
        }
    }

    private String readContent(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Could not read '" + doc.getPath() + "': " + e.getMessage());
        }
    }
}
