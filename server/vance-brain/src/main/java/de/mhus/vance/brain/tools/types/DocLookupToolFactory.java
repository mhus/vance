package de.mhus.vance.brain.tools.types;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code doc_lookup} — exposes a fixed document path as its own tool.
 * The factory captures the {@link ServerToolDocument} by value; on
 * invocation the tool reads the configured path through
 * {@link DocumentService#lookupCascade} so content changes propagate
 * without re-spawning processes.
 *
 * <p>The {@code parameters} map carries one key:
 * <pre>{ "path": "documents/how_to_arthur.md" }</pre>
 * Tools of this type take no invocation arguments — the LLM calls
 * them by {@code name} and gets the document content back.
 */
@Component
@RequiredArgsConstructor
public class DocLookupToolFactory implements ToolFactory {

    public static final String TYPE_ID = "doc_lookup";

    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description",
                                    "Document path inside the project, "
                                            + "e.g. 'documents/how_to_arthur.md'. "
                                            + "Resolved via the project → _vance → "
                                            + "classpath cascade.")),
            "required", List.of("path"));

    private static final Map<String, Object> INVOKE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", false);

    private final DocumentService documentService;

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    public Tool create(ServerToolDocument document) {
        Object pathRaw = document.getParameters().get("path");
        if (!(pathRaw instanceof String path) || path.isBlank()) {
            throw new IllegalArgumentException(
                    "doc_lookup tool '" + document.getName()
                            + "' is missing required parameter 'path'");
        }
        return new DocLookupTool(document, path, documentService);
    }

    private static final class DocLookupTool implements Tool {

        private final ServerToolDocument doc;
        private final String path;
        private final DocumentService documentService;

        DocLookupTool(ServerToolDocument doc, String path, DocumentService documentService) {
            this.doc = doc;
            this.path = path;
            this.documentService = documentService;
        }

        @Override
        public String name() {
            return doc.getName();
        }

        @Override
        public String description() {
            return doc.getDescription();
        }

        @Override
        public boolean primary() {
            return doc.isPrimary();
        }

        @Override
        public Map<String, Object> paramsSchema() {
            return INVOKE_SCHEMA;
        }

        @Override
        public Set<String> labels() {
            List<String> ls = doc.getLabels();
            return ls == null ? Set.of() : Set.copyOf(ls);
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
                throw new ToolException(name() + " requires a tenant scope");
            }
            Optional<LookupResult> hit = documentService.lookupCascade(
                    ctx.tenantId(), ctx.projectId(), path);
            if (hit.isEmpty()) {
                throw new ToolException(
                        "Doc not found at path '" + path + "' for tool '" + name() + "'");
            }
            LookupResult result = hit.get();
            String content = result.content() == null ? "" : result.content();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", result.path());
            out.put("content", content);
            out.put("chars", content.length());
            out.put("source", result.source().name().toLowerCase());
            return out;
        }
    }
}
