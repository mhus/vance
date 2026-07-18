package de.mhus.vance.brain.damogran;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The {@code compose_run} tool — runs a Damogran compose. Wraps
 * {@link DamogranComposeService}: provision the named workspace, import
 * documents, run the tasks linearly, export results.
 *
 * <p>Takes either {@code composePath} (a {@code compose} document to load) or
 * an inline {@code composeYaml}. Returns the overall status, the workspace
 * name, and per-task results (status + produced outputs + any error).
 */
@Component
public class ComposeRunTool implements Tool {

    private final DamogranComposeService composeService;
    private final DocumentService documentService;

    public ComposeRunTool(DamogranComposeService composeService, DocumentService documentService) {
        this.composeService = composeService;
        this.documentService = documentService;
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
                + "compose document) or inline composeYaml. Returns per-task "
                + "status and produced outputs; halts at the first failing task.";
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
        String yaml = resolveYaml(params, ctx.tenantId(), projectId);

        try {
            DamogranComposeResult result =
                    composeService.run(ctx.tenantId(), projectId, ctx.processId(), yaml);
            return DamogranResponse.toMap(result);
        } catch (DamogranException e) {
            throw new ToolException(e.getMessage());
        }
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
