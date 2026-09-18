package de.mhus.vance.addon.brain.scribble.tool;

import de.mhus.vance.addon.brain.scribble.ScribblePdfService;
import de.mhus.vance.addon.brain.scribble.ScribblebookFolderReader;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The two PDF export tools (planning/scribble-ocr-pdf.md §4):
 * {@code scribble_sheet_pdf(path)} renders one sheet, {@code
 * scribble_book_pdf(folder)} the whole notebook — enabled sheets only, in
 * scan order. Both write a machine-owned export artifact that a re-run
 * overwrites: {@code <name>.scribble.pdf} next to the sheet, {@code
 * <folder>/<folder>.pdf} next to the manifest.
 *
 * <p>Two tools instead of one with {@code path|folder}: the param shapes
 * differ (path vs. folder), so schema-based discovery names them apart.
 */
@Component
public class ScribblePdfTools {

    private ScribblePdfTools() {}

    abstract static class AbstractPdfTool implements Tool {
        private final String name;
        private final String description;
        private final Map<String, Object> schema;

        AbstractPdfTool(String name, String description, Map<String, Object> schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public boolean primary() {
            return false;
        }

        @Override
        public Set<String> labels() {
            return Set.of("eddie", "read", "document", "scribble", "export");
        }

        @Override
        public Map<String, Object> paramsSchema() {
            return schema;
        }
    }

    /** One sheet → a one-page PDF next to it. */
    @Component
    public static class SheetPdfTool extends AbstractPdfTool {

        private final EddieContext eddieContext;
        private final DocumentService documentService;
        private final ScribblePdfService pdfService;

        public SheetPdfTool(EddieContext eddieContext, DocumentService documentService, ScribblePdfService pdfService) {
            super(
                    "scribble_sheet_pdf",
                    "Export a handwriting sheet (kind: scribble) as a one-page PDF, stored next "
                            + "to the sheet (<name>.scribble.pdf). A re-run overwrites the export. "
                            + "Read-only for the sheet itself.",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            new LinkedHashMap<String, Object>() {
                                {
                                    put(
                                            "path",
                                            Map.of(
                                                    "type",
                                                    "string",
                                                    "description",
                                                    "Scribble document path to export."));
                                    put("projectId", Map.of("type", "string"));
                                }
                            },
                            "required",
                            List.of("path")));
            this.eddieContext = eddieContext;
            this.documentService = documentService;
            this.pdfService = pdfService;
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            ScribbleToolSupport.Resolved resolved =
                    ScribbleToolSupport.resolveByPath(eddieContext, documentService, params, ctx);
            ScribblePdfService.SheetExport export =
                    pdfService.exportSheet(resolved.tenantId(), resolved.projectName(), resolved.doc(), ctx.userId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("pdfPath", export.pdfPath());
            return out;
        }
    }

    /** Whole notebook → one PDF next to the manifest, enabled sheets only. */
    @Component
    public static class BookPdfTool extends AbstractPdfTool {

        private final ScribblePdfService pdfService;

        public BookPdfTool(ScribblePdfService pdfService) {
            super(
                    "scribble_book_pdf",
                    "Export a scribblebook folder as one PDF — every enabled sheet in scan "
                            + "order, one page per sheet. Sheets disabled via their 'enabled' flag "
                            + "are skipped and listed. Stored next to the manifest "
                            + "(<folder>/<folder>.pdf); a re-run overwrites the export.",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            new LinkedHashMap<String, Object>() {
                                {
                                    put(
                                            "folder",
                                            Map.of(
                                                    "type",
                                                    "string",
                                                    "description",
                                                    "Scribblebook folder (the app manifest's parent folder)."));
                                    put("projectId", Map.of("type", "string"));
                                }
                            },
                            "required",
                            List.of("folder")));
            this.pdfService = pdfService;
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            String folder = ScribblebookFolderReader.normaliseFolder(ScribbleToolSupport.paramString(params, "folder"));
            ScribblePdfService.BookExport export =
                    pdfService.exportBook(ctx.tenantId(), ctx.projectId(), folder, ctx.userId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("pdfPath", export.pdfPath());
            out.put("pageCount", export.pageCount());
            if (!export.skipped().isEmpty()) {
                out.put("skippedSheets", export.skipped());
            }
            return out;
        }
    }
}
