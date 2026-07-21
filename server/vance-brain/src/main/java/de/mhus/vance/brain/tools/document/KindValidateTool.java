package de.mhus.vance.brain.tools.document;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.kind.validate.KindValidationResult;
import de.mhus.vance.shared.document.kind.validate.KindValidationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent self-check: validate content (or an already-saved document) against its
 * document <b>kind</b>. Kind-agnostic — resolves the kind (explicit or from the
 * {@code $meta.kind} header), finds the matching {@code KindHandler} and returns
 * its findings. Advisory only: it never blocks a write; the agent uses the
 * findings to self-correct before telling the user it's done.
 *
 * <p>Exactly one of {@code content} (pre-write check) or {@code path}
 * (post-write check) must be given. Response is the shared
 * {@code { target, ok, errors, warnings, findings[] }} envelope, identical to
 * {@code workbook_validate}.
 */
@Component
@Slf4j
public class KindValidateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("content", Map.of("type", "string",
                        "description", "The content to check, e.g. what you just "
                                + "generated (PRE-write self-check). Give exactly one "
                                + "of content / path."));
                put("path", Map.of("type", "string",
                        "description", "Path of an already-saved document to check "
                                + "(POST-write self-check). Give exactly one of "
                                + "content / path."));
                put("kind", Map.of("type", "string",
                        "description", "Optional. The document kind to validate "
                                + "against; inferred from the $meta.kind header when "
                                + "omitted. Set it only for bare content without a "
                                + "header."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of());

    private final EddieContext eddieContext;
    private final KindValidationService validationService;

    public KindValidateTool(EddieContext eddieContext,
                            KindValidationService validationService) {
        this.eddieContext = eddieContext;
        this.validationService = validationService;
    }

    @Override public String name() { return "kind_validate"; }

    @Override
    public String description() {
        return "Validate content or a saved document against its document kind. "
                + "Give exactly one of 'content' (check what you just built before "
                + "saving) or 'path' (check a stored doc). 'kind' is optional — "
                + "inferred from the $meta.kind header; set it only for bare content. "
                + "Read-only, advisory (never blocks). Returns { ok, errors, "
                + "warnings, findings[] }. Unknown kinds return a warning, not an "
                + "error. Use it to self-check before telling the user it's done.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("read-only", "document");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawContent = params == null ? null : params.get("content");
        Object rawPath = params == null ? null : params.get("path");
        boolean hasContent = rawContent instanceof String c && !c.isBlank();
        boolean hasPath = rawPath instanceof String p && !p.isBlank();
        if (hasContent == hasPath) {
            throw new ToolException("give exactly one of 'content' or 'path'");
        }
        Object rawKind = params == null ? null : params.get("kind");
        String kind = rawKind instanceof String k && !k.isBlank() ? k.trim() : null;

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String projectId = project.getName();

        KindValidationResult result;
        if (hasPath) {
            result = validationService.validateByPath(
                    ctx.tenantId(), projectId, ((String) rawPath).trim());
        } else {
            result = validationService.validateContent(
                    ctx.tenantId(), projectId, kind, (String) rawContent, null);
        }
        log.info("KindValidateTool mode={} target='{}' ok={} findings={}",
                hasPath ? "path" : "content", result.target(), result.ok(),
                result.findings().size());
        return result.toMap();
    }
}
