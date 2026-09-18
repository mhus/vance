package de.mhus.vance.addon.brain.scribble.tool;

import de.mhus.vance.addon.brain.scribble.ScribbleValidationService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Read-only structural check of a {@code kind: scribble} sheet — the same
 * validator {@code kind_validate} runs. Reports strokes the lenient codec
 * would drop (by index) and ink that runs off the sheet raster.
 */
@Component
@Slf4j
public class ScribbleValidateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            new LinkedHashMap<String, Object>() {
                {
                    put("path", Map.of("type", "string", "description", "Scribble document path."));
                    put("projectId", Map.of("type", "string"));
                }
            },
            "required",
            List.of("path"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;

    public ScribbleValidateTool(EddieContext eddieContext, DocumentService documentService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
    }

    @Override
    public String name() {
        return "scribble_validate";
    }

    @Override
    public String description() {
        return "Validate the structure of a handwriting sheet (kind: scribble): parse "
                + "errors, strokes that would be dropped on the next save (reported by "
                + "index), and ink outside the sheet raster. Read-only. Handwriting "
                + "content itself is not text and cannot be read.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "read", "document", "scribble");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ScribbleToolSupport.Resolved resolved =
                ScribbleToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
        DocumentDocument doc = resolved.doc();

        String mime = doc.getMimeType() != null ? doc.getMimeType() : "application/yaml";
        String body;
        try (InputStream in = documentService.loadContent(doc)) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Could not load scribble '" + doc.getPath() + "': " + e.getMessage(), e);
        }

        List<Finding> findings = ScribbleValidationService.validate(body, mime, doc.getPath());
        log.info("ScribbleValidateTool path='{}' findings={}", doc.getPath(), findings.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", findings.stream().noneMatch(f -> f.level() == Finding.Level.ERROR));
        result.put(
                "errorCount",
                findings.stream().filter(f -> f.level() == Finding.Level.ERROR).count());
        result.put(
                "warningCount",
                findings.stream()
                        .filter(f -> f.level() == Finding.Level.WARNING)
                        .count());
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (Finding f : findings) serialized.add(f.toMap());
        result.put("findings", serialized);
        return result;
    }
}
