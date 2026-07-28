package de.mhus.vance.brain.zarniwoop.tools;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.brain.zarniwoop.ResearchDocumentResult;
import de.mhus.vance.brain.zarniwoop.ResearchDocumentService;
import de.mhus.vance.brain.zarniwoop.ZarniwoopException;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * LLM-facing entry point for "research a question and leave me a document".
 * Runs the curated research pass, synthesizes a Markdown document, saves it,
 * and attaches every source as a sticky-note citation — then returns a
 * <em>pointer</em> (path + summary + tags), NOT the body.
 *
 * <p>Demarcation from {@code research_investigate}: that tool hands the raw
 * ranked corpus back into the turn for the caller to reason over inline;
 * this tool persists the result and keeps the context window clean. Pick
 * this when the answer should live in a document (large, reusable, or handed
 * to sub-work); pick {@code research_investigate} when you want the material
 * in front of you right now. Both go through the same research pipeline.
 *
 * <p>Mutating: it creates a document, so it is gated on {@code CREATE} in the
 * target project and writes under the caller's authority (no SYSTEM bypass).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResearchDocumentTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "question", Map.of(
                            "type", "string",
                            "description",
                                    "The research question in natural language. Pass the "
                                            + "user's wording verbatim; the pipeline plans its "
                                            + "own search queries internally."),
                    "path", Map.of(
                            "type", "string",
                            "description",
                                    "Optional target document path inside the project, e.g. "
                                            + "'research/tokamak-cooling.md'. Defaults to "
                                            + "'research/<slug-of-question>.md'. A name clash is "
                                            + "resolved by appending '-2', '-3', …"),
                    "tags", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description",
                                    "Optional extra tags, merged with the 'research' marker "
                                            + "tag and the topical tags the synthesizer proposes."),
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional project name. Defaults to the active project.")),
            "required", List.of("question"));

    private final ResearchDocumentService service;
    private final KindToolSupport support;

    @Override
    public String name() {
        return "research_document";
    }

    @Override
    public String description() {
        return "Research a question across the project's configured sources, "
                + "synthesize a Markdown document that answers it, save it, and "
                + "attach each source as a sticky-note citation. Returns a "
                + "POINTER — path + a short summary + tags — not the full body, "
                + "so you can keep working on the (possibly large) document via "
                + "doc_read ranges / grep while using the summary. Use this "
                + "instead of research_investigate when the result should "
                + "PERSIST as a document or be handed off to other work; use "
                + "research_investigate when you want the raw corpus in your own "
                + "context to reason over right now. Creates a document, so you "
                + "need write access to the project.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Set<String> labels() {
        // No "read-only" label → safety() reports MUTATING (this creates a doc).
        return Set.of("research", "documents", "write");
    }

    @Override
    public Set<String> prakLabels() {
        return Set.of("research", "documents", "knowledge");
    }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Long-running: it researches, then calls an LLM to write the "
                + "document. On 'no usable sources' narrow the question or check "
                + "research_providers.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null) {
            throw new ToolException("research_document requires a tool invocation context");
        }
        if (params == null) {
            throw new ToolException("'question' is required");
        }
        Object raw = params.get("question");
        if (!(raw instanceof String question) || StringUtils.isBlank(question)) {
            throw new ToolException("'question' is required");
        }
        if (StringUtils.isBlank(ctx.projectId())) {
            throw new ToolException("research tools require a project scope");
        }

        // Resolve the project the same way doc_write does, then pin both the
        // research scope and the document to it.
        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        String projectName = project.getName();

        String requestedPath = KindToolSupport.paramString(params, "path");
        String basePath = requestedPath != null
                ? requestedPath
                : ResearchDocumentService.deriveDefaultPath(question);

        // Fail fast before the expensive research/LLM work if the caller may
        // not create documents here. uniquePath() may land on a '-N' variant
        // in the same project — the CREATE verdict is identical for those.
        support.enforceDocWrite(ctx, projectName, basePath, Action.CREATE);

        List<String> tags = params.get("tags") instanceof List<?> l
                ? l.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();

        ResearchDocumentResult result;
        try {
            result = service.createDocument(
                    question, basePath, tags, projectName, ctx,
                    support.writeActor(ctx, basePath));
        } catch (ZarniwoopException e) {
            throw new ToolException(e.getMessage());
        }
        return shape(result);
    }

    private static Map<String, Object> shape(ResearchDocumentResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.docId());
        out.put("projectId", r.projectId());
        out.put("path", r.path());
        out.put("title", r.title());
        if (r.summary() != null) {
            out.put("summary", r.summary());
        }
        out.put("sourceCount", r.sourceCount());
        out.put("tags", r.tags());
        if (!r.gaps().isEmpty()) {
            out.put("gaps", r.gaps());
        }
        out.put("hint", "Document saved. The summary above is a cheap stand-in "
                + "for the full body — read the body with doc_read (path='"
                + r.path() + "') only when you need detail; the source URLs are "
                + "attached as notes (doc_note_list).");
        return out;
    }
}
