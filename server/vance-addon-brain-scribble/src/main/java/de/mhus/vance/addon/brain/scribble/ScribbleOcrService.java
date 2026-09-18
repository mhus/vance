package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Handwriting transcription (planning/scribble-ocr-pdf.md §3): renders the
 * sheet server-side and sends the PNG through a {@link LightLlmService} call
 * configured by the internal {@code scribble-ocr} recipe (this addon ships
 * it) — the recipe's model must be VISION-capable, the service gates that
 * fail-fast. The transcript always lands in the same machine-owned file
 * next to the sheet ({@code <name>.scribble.md}); a re-run overwrites it,
 * never versions it.
 */
@Service
public class ScribbleOcrService {

    /** The internal recipe this addon ships (vance-defaults/_vance/recipes). */
    public static final String RECIPE_NAME = "scribble-ocr";

    /** Transcription runs finer than the vision default — line ends matter. */
    static final int OCR_DPI = 300;

    private static final String USER_PROMPT = "Transcribe the handwritten content of the attached image.";

    private final ScribbleService scribbleService;
    private final LightLlmService lightLlmService;
    private final DocumentService documentService;
    private final SecurityContextFactory contextFactory;

    public ScribbleOcrService(
            ScribbleService scribbleService,
            LightLlmService lightLlmService,
            DocumentService documentService,
            SecurityContextFactory contextFactory) {
        this.scribbleService = scribbleService;
        this.lightLlmService = lightLlmService;
        this.documentService = documentService;
        this.contextFactory = contextFactory;
    }

    /** Where the transcript lives: {@code <name>.scribble.md} next to the sheet. */
    public static String mdPathFor(String sheetPath) {
        return ScribblePdfService.replaceExtension(sheetPath, ".md");
    }

    public record OcrResult(String mdPath, String markdown) {}

    public OcrResult transcribe(String tenantId, String projectId, DocumentDocument doc, @Nullable String userId) {
        ScribbleSheet sheet = scribbleService.readSheet(doc);
        if (sheet.strokes().isEmpty()) {
            throw new ToolException("Sheet '" + doc.getPath() + "' has no strokes — nothing to transcribe.");
        }
        byte[] png;
        try {
            png = ScribbleRenderer.renderPng(sheet, OCR_DPI, null);
        } catch (IOException e) {
            throw new ToolException("Could not render scribble '" + doc.getPath() + "': " + e.getMessage(), e);
        }
        String markdown = lightLlmService.call(LightLlmRequest.builder()
                .recipeName(RECIPE_NAME)
                .userPrompt(USER_PROMPT)
                .pebbleVars(Map.of("sheetTitle", sheet.title() != null ? sheet.title() : doc.getPath()))
                .imageData(png)
                .imageMime("image/png")
                .tenantId(tenantId)
                .projectId(projectId)
                .build());

        String mdPath = mdPathFor(doc.getPath());
        String body = "<!-- scribble-ocr of " + doc.getPath() + ", generated "
                + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                + "; regenerate with scribble_ocr -->\n\n" + markdown.strip() + "\n";
        try {
            documentService.createOrReplaceBinary(
                    tenantId,
                    projectId,
                    mdPath,
                    body.getBytes(StandardCharsets.UTF_8),
                    "text/markdown",
                    (doc.getTitle() != null && !doc.getTitle().isBlank() ? doc.getTitle() : "Sheet")
                            + " — transcription",
                    /*tags*/ null,
                    /*headers*/ null,
                    userId,
                    contextFactory.writeActor(tenantId, userId, mdPath));
        } catch (RuntimeException e) {
            throw new ToolException("Could not store transcription at '" + mdPath + "': " + e.getMessage(), e);
        }
        return new OcrResult(mdPath, body);
    }
}
