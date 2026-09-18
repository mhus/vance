package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.addon.brain.scribble.model.ScribbleSheet;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Server-side PDF export of handwriting sheets (planning/scribble-ocr-pdf.md
 * §4): each {@link ScribbleSheet} renders through {@link ScribbleRenderer} at
 * native dpi and lands as exactly one page. A4 sheets are raster-true
 * (1240×1754 is A4 @150 dpi), so the image fills the page; other sheet sizes
 * keep their aspect, scaled to fit. The book export skips sheets the user
 * disabled via the {@code enabled} flag.
 *
 * <p>PDFs are export artifacts: regenerated on every call, machine-owned,
 * stored next to their source ({@code <name>.scribble.pdf} for a sheet,
 * {@code <folder>/<folder>.pdf} for a book).
 */
@Service
public class ScribblePdfService {

    /** Fixed page format — the sheet raster is A4-native by default anyway. */
    private static final PDRectangle PAGE = PDRectangle.A4;

    private final ScribbleService scribbleService;
    private final ScribblebookFolderReader folderReader;
    private final DocumentService documentService;
    private final SecurityContextFactory contextFactory;

    public ScribblePdfService(
            ScribbleService scribbleService,
            ScribblebookFolderReader folderReader,
            DocumentService documentService,
            SecurityContextFactory contextFactory) {
        this.scribbleService = scribbleService;
        this.folderReader = folderReader;
        this.documentService = documentService;
        this.contextFactory = contextFactory;
    }

    public record SheetExport(String pdfPath) {}

    /** @param skipped titles of sheets the user disabled; they are not in the PDF. */
    public record BookExport(String pdfPath, int pageCount, List<String> skipped) {}

    /** Single sheet → one-page PDF next to it ({@code <name>.scribble.pdf}). */
    public SheetExport exportSheet(String tenantId, String projectId, DocumentDocument doc, @Nullable String userId) {
        ScribbleSheet sheet = scribbleService.readSheet(doc);
        String pdfPath = sheetPdfPath(doc.getPath());
        storePdf(tenantId, projectId, pdfPath, List.of(sheet), doc.getTitle(), userId);
        return new SheetExport(pdfPath);
    }

    /** Whole book → one PDF next to the manifest, enabled sheets only, scan order. */
    public BookExport exportBook(String tenantId, String projectId, String folder, @Nullable String userId) {
        ScribblebookFolderReader.Scan scan = folderReader.scan(tenantId, projectId, folder);
        List<ScribbleSheet> sheets = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ScribblebookFolderReader.Page page : scan.pages()) {
            if (!page.enabled()) {
                skipped.add(page.title());
                continue;
            }
            sheets.add(scribbleService.readSheet(page.doc()));
        }
        if (sheets.isEmpty()) {
            throw new ToolException("No enabled sheets in '" + folder + "' to export — skipped: " + skipped);
        }
        String pdfPath = bookPdfPath(scan.folder());
        storePdf(tenantId, projectId, pdfPath, sheets, scan.manifest().getTitle(), userId);
        return new BookExport(pdfPath, sheets.size(), skipped);
    }

    // ── Storage ────────────────────────────────────────────────────

    private void storePdf(
            String tenantId,
            String projectId,
            String pdfPath,
            List<ScribbleSheet> sheets,
            @Nullable String title,
            @Nullable String userId) {
        byte[] pdf;
        try {
            pdf = pdfOf(sheets);
        } catch (IOException e) {
            throw new ToolException("Could not assemble PDF at '" + pdfPath + "': " + e.getMessage(), e);
        }
        try {
            documentService.createOrReplaceBinary(
                    tenantId,
                    projectId,
                    pdfPath,
                    pdf,
                    "application/pdf",
                    (title != null && !title.isBlank() ? title : "Scribble") + " — PDF export",
                    /*tags*/ null,
                    /*headers*/ null,
                    userId,
                    contextFactory.writeActor(tenantId, userId, pdfPath));
        } catch (RuntimeException e) {
            throw new ToolException("Could not store PDF at '" + pdfPath + "': " + e.getMessage(), e);
        }
    }

    // ── Assembly (pure) ───────────────────────────────────────────

    /** Several sheets → one PDF, one page each, in the given (scan) order. */
    public static byte[] pdfOf(List<ScribbleSheet> sheets) throws IOException {
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("Cannot build a PDF from zero sheets");
        }
        try (PDDocument doc = new PDDocument()) {
            doc.getDocumentInformation().setTitle("Scribble export");
            doc.getDocumentInformation()
                    .setCreator("Vance scribble addon — rendered "
                            + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            for (ScribbleSheet sheet : sheets) {
                BufferedImage image = ScribbleRenderer.renderImage(sheet, ScribbleRenderer.NATIVE_DPI, null);
                PDImageXObject ximage = LosslessFactory.createFromImage(doc, image);
                PDPage page = new PDPage(PAGE);
                doc.addPage(page);
                drawFullPage(doc, page, ximage);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void drawFullPage(PDDocument doc, PDPage page, PDImageXObject image) throws IOException {
        float pageW = page.getMediaBox().getWidth();
        float pageH = page.getMediaBox().getHeight();
        // Fit the raster into the page, keep the aspect: A4-native sheets
        // fill 1:1, other rasters scale to fit. The page is in points and
        // always smaller than the raster, so this can only shrink.
        float scale = Math.min(pageW / image.getWidth(), pageH / image.getHeight());
        float w = image.getWidth() * scale;
        float h = image.getHeight() * scale;
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(image, (pageW - w) / 2, (pageH - h) / 2, w, h);
        }
    }

    /** Path convention for a single sheet's export: {@code <name>.scribble.pdf}. */
    public static String sheetPdfPath(String sheetPath) {
        return replaceExtension(sheetPath, ".pdf");
    }

    /** Path convention for a book's export: {@code <folder>/<folder>.pdf}. */
    public static String bookPdfPath(String folder) {
        return folder + "/" + folder.substring(folder.lastIndexOf('/') + 1) + ".pdf";
    }

    static String replaceExtension(String path, String newExt) {
        String leaf = path;
        String dir = "";
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            leaf = path.substring(slash + 1);
            dir = path.substring(0, slash + 1);
        }
        int dot = leaf.lastIndexOf('.');
        String stem = dot <= 0 ? leaf : leaf.substring(0, dot);
        return dir + stem + newExt;
    }
}
