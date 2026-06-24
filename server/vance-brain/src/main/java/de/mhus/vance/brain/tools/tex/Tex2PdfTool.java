package de.mhus.vance.brain.tools.tex;

import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The {@code tex2pdf} tool — wraps {@link TexService} following the
 * Fenchurch pattern (synchronous service + thin tool wrapper, analog
 * to {@code ImageGenerateTool} / {@code FenchurchService}).
 *
 * <p>Reads a {@code tex-compose} document (YAML manifest), transports
 * all declared files into a temp workspace, runs {@code latexmk}, and
 * imports the resulting PDF as a binary document. Returns the PDF path
 * and a {@code markdownLink} for chat embedding.
 *
 * <p>Synchronous — a single call can take from a few seconds (simple
 * document) up to 2 minutes (complex document with bibliography). The
 * service enforces a 120s latexmk timeout + 150s hard process timeout.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class Tex2PdfTool implements Tool {

    private final TexService texService;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "composePath", Map.of(
                            "type", "string",
                            "description",
                                    "Path to the tex-compose document (YAML manifest). "
                                            + "The manifest declares the main .tex file, "
                                            + "all source files to transport, the engine "
                                            + "(pdflatex/xelatex/lualatex), and the output "
                                            + "PDF name.")),
            "required", List.of("composePath"));

    @Override
    public String name() {
        return "tex2pdf";
    }

    @Override
    public String description() {
        return "Compile a tex-compose document to PDF. Reads the compose "
                + "manifest (YAML), transports all declared files into a "
                + "temp workspace, runs latexmk with the configured engine "
                + "(pdflatex, xelatex, or lualatex), and imports the "
                + "resulting PDF as a document. Returns the PDF path and a "
                + "markdown link. Synchronous — can take up to 2 minutes for "
                + "complex documents. On failure, returns the LaTeX log excerpt.";
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
            throw new ToolException("tex2pdf requires a tenant scope");
        }
        String composePath = readNonBlank(params, "composePath");
        String projectId = ctx.resolveLocalProjectId();

        TexService.TexCompileResult result = texService.compile(
                ctx.tenantId(), projectId, composePath, ctx.processId());

        if (result.success()) {
            return successResponse(result, ctx);
        } else {
            return errorResponse(result);
        }
    }

    private Map<String, Object> successResponse(TexService.TexCompileResult r, ToolInvocationContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("pdfPath", r.pdfPath());

        // Build markdown link for the freshly imported PDF
        String markdownLink = buildMarkdownLink(r.pdfPath(), ctx);
        out.put("markdownLink", markdownLink);
        out.put("elapsedMs", r.elapsedMs());
        return out;
    }

    private static Map<String, Object> errorResponse(TexService.TexCompileResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", r.error());
        if (r.logExcerpt() != null) {
            out.put("logExcerpt", r.logExcerpt());
        }
        out.put("elapsedMs", r.elapsedMs());
        return out;
    }

    private String buildMarkdownLink(String pdfPath, ToolInvocationContext ctx) {
        try {
            DocumentDocument pdfDoc = documentService
                    .findByPath(ctx.tenantId(), ctx.resolveLocalProjectId(), pdfPath)
                    .orElse(null);
            if (pdfDoc != null) {
                return linkBuilder.linkFor(pdfDoc, ctx.projectId());
            }
        } catch (RuntimeException e) {
            log.debug("Could not build markdown link for {}: {}", pdfPath, e.toString());
        }
        return "vance:/" + pdfPath;
    }

    private static String readNonBlank(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }
}
