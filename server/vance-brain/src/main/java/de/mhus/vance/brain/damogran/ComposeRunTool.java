package de.mhus.vance.brain.damogran;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The {@code compose_run} tool — runs a Damogran compose. Wraps
 * {@link DamogranComposeService}: provision the named workspace, import
 * documents, run the tasks linearly, export results.
 *
 * <p>Runs are <b>async</b>: quick composes return their result inline within a
 * short fast-path; a longer one returns {@code {runId, running:true}} and the
 * calling process gets a {@link ProcessEventType#COMPOSE_FINISHED} event when it
 * completes — so a model process can end its turn and sleep (hours if need be),
 * resuming on the event. Takes {@code composePath} or inline {@code composeYaml}.
 */
@Component
public class ComposeRunTool implements Tool {

    /** How long the tool blocks for a quick result before handing back a runId. */
    private static final long FAST_PATH_WAIT_MS = 15_000;

    private final DamogranComposeService composeService;
    private final DocumentService documentService;
    private final ComposeFinishedNotifier finishedNotifier;

    public ComposeRunTool(DamogranComposeService composeService,
                          DocumentService documentService,
                          ComposeFinishedNotifier finishedNotifier) {
        this.composeService = composeService;
        this.documentService = documentService;
        this.finishedNotifier = finishedNotifier;
    }

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "composePath", Map.of(
                            "type", "string",
                            "description", "Path to a compose document (YAML manifest) to run."),
                    "composeYaml", Map.of(
                            "type", "string",
                            "description", "Inline compose manifest (YAML) to run instead of a document.")));

    @Override
    public String name() {
        return "compose_run";
    }

    @Override
    public String description() {
        return "Run a Damogran workspace compose: provision a named workspace, "
                + "import documents/URLs into it, run a linear list of tasks "
                + "(exec / js / python / spawn / llm / addon tasks such as tex), "
                + "and export results back to documents. Takes composePath (a "
                + "compose document) or inline composeYaml. Runs async: a quick "
                + "compose returns per-task status + outputs inline; a long one "
                + "returns {runId, running:true} and you receive a COMPOSE_FINISHED "
                + "event when it completes — end your turn and resume on the event "
                + "rather than blocking. Halts at the first failing task.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Set<String> labels() {
        return Set.of("write");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("compose_run requires a tenant scope");
        }
        String projectId = ctx.resolveLocalProjectId();
        String composePath = readString(params, "composePath");
        String yaml = resolveYaml(params, ctx.tenantId(), projectId);
        // Relative vance: paths resolve against the compose document's directory.
        String baseDir = composePath != null ? DamogranUri.parentDir(composePath) : null;

        ComposeRun run;
        try {
            run = composeService.runAsync(ctx.tenantId(), projectId, ctx.processId(), yaml, baseDir);
        } catch (DamogranException e) {
            throw new ToolException(e.getMessage());
        }
        try {
            run.awaitDone(FAST_PATH_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (run.isTerminal() && run.result() != null) {
            return DamogranResponse.toMap(run.result());
        }
        // Still running: notify this process on completion so it can sleep.
        String ownerProcessId = ctx.processId();
        if (ownerProcessId != null && !ownerProcessId.isBlank()) {
            run.onDone(finished -> finishedNotifier.notifyFinished(finished, ownerProcessId));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.runId());
        out.put("running", true);
        out.put("status", "running");
        out.put("workspace", run.workspaceName());
        out.put("note", "Compose is running in the background; end your turn — you will "
                + "receive a COMPOSE_FINISHED event with the result when it completes.");
        return out;
    }

    private String resolveYaml(Map<String, Object> params, String tenantId, String projectId) {
        String composeYaml = readString(params, "composeYaml");
        if (composeYaml != null) {
            return composeYaml;
        }
        String composePath = readString(params, "composePath");
        if (composePath == null) {
            throw new ToolException("compose_run requires 'composePath' or 'composeYaml'");
        }
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, composePath)
                .orElseThrow(() -> new ToolException("compose document not found: " + composePath));
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("failed to read compose document " + composePath + ": " + e.getMessage());
        }
    }

    private static String readString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }
}
