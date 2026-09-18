package de.mhus.vance.addon.brain.scribble.tool;

import de.mhus.vance.addon.brain.scribble.ScribbleOcrService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code scribble_ocr(path)} — transcribe a handwriting sheet to Markdown
 * via a vision model (internal {@code scribble-ocr} recipe,
 * {@link ScribbleOcrService}). The transcript always lands in the same
 * machine-owned file next to the sheet ({@code <name>.scribble.md}); a
 * re-run overwrites it. The full text is NOT echoed into the chat — the
 * model reads the file via {@code doc_read} when it needs it.
 */
@Component
public class ScribbleOcrTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            new LinkedHashMap<String, Object>() {
                {
                    put("path", Map.of("type", "string", "description", "Scribble document path to transcribe."));
                    put("projectId", Map.of("type", "string"));
                }
            },
            "required",
            List.of("path"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final ScribbleOcrService ocrService;

    public ScribbleOcrTool(EddieContext eddieContext, DocumentService documentService, ScribbleOcrService ocrService) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.ocrService = ocrService;
    }

    @Override
    public String name() {
        return "scribble_ocr";
    }

    @Override
    public String description() {
        return "Transcribe the handwriting of a sheet (kind: scribble) to Markdown using a "
                + "vision model. The result is always stored in the same file next to the "
                + "sheet (<name>.scribble.md), overwriting the previous transcription — read "
                + "it with doc_read. The recipe's model must have vision; a failed call "
                + "names the model that lacks it. Read-only for the sheet itself.";
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
        ScribbleOcrService.OcrResult result =
                ocrService.transcribe(resolved.tenantId(), resolved.projectName(), doc, ctx.userId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("mdPath", result.mdPath());
        out.put("chars", result.markdown().length());
        out.put(
                "note",
                "Transcription stored — read it with doc_read('" + result.mdPath() + "') when you need the text.");
        return out;
    }
}
