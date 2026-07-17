package de.mhus.vance.addon.brain.tex;

import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface for the tex2pdf compilation — allows the Web-UI to
 * trigger LaTeX compilation directly (e.g. a "Compile" button in the
 * code editor), without going through the LLM tool path.
 *
 * <p>Single endpoint:
 * <ul>
 *   <li>{@code POST /brain/{tenant}/tex/compile} — takes a compose
 *       path, delegates to {@link TexService}, returns the PDF path
 *       or an error with log excerpt.</li>
 * </ul>
 *
 * <p>Behind the regular Vance access filter (user JWT). The user must
 * have access to the project and all referenced projects (cross-project
 * references are checked by {@code DocumentService.findByPath}).
 */
@RestController
@RequestMapping("/brain/{tenant}/tex")
@RequiredArgsConstructor
@Slf4j
public class Tex2PdfController {

    private final TexService texService;

    /**
     * Compile a tex-compose document to PDF.
     *
     * @param tenant  tenant from the URL
     * @param body    request body with {@code composePath} and optional {@code projectId}
     * @param request the HTTP request (for user context)
     * @return compilation result: {@code {success, pdfPath, elapsedMs}}
     *         or {@code {success: false, error, logExcerpt?, elapsedMs}}
     */
    @PostMapping("/compile")
    public Map<String, Object> compile(
            @PathVariable("tenant") String tenant,
            @RequestBody CompileRequest body) {

        if (body.composePath() == null || body.composePath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'composePath' is required");
        }

        String projectId = body.projectId();
        if (projectId == null || projectId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'projectId' is required");
        }

        try {
            TexService.TexCompileResult result = texService.compile(
                    tenant, projectId, body.composePath().trim(), null);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", result.success());
            if (result.success()) {
                out.put("pdfPath", result.pdfPath());
            } else {
                out.put("error", result.error());
                if (result.logExcerpt() != null) {
                    out.put("logExcerpt", result.logExcerpt());
                }
            }
            out.put("elapsedMs", result.elapsedMs());
            return out;

        } catch (ToolException e) {
            log.debug("tex2pdf compile failed for {}/{}: {}", tenant, projectId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Request body for the compile endpoint.
     */
    public record CompileRequest(
            String composePath,
            @Nullable String projectId) {}
}
