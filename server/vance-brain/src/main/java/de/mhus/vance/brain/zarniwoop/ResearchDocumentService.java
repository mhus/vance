package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.RankedHit;
import de.mhus.vance.toolpack.research.RankedHitSet;
import de.mhus.vance.toolpack.research.SearchScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Curated research → persisted document. Sits one layer above
 * {@link ZarniwoopResearchService}: it runs the same {@code investigate}
 * corpus pass, then synthesizes the ranked hits into a full Markdown
 * document via the {@link LightLlmService} (single-shot, no process spawn),
 * writes it through {@link DocumentService}, stamps a machine-readable
 * {@code summary}, and attaches every source as a sticky-note citation.
 *
 * <p><b>Why this exists next to {@code research_investigate}.</b> The
 * investigate tool returns the whole corpus <em>into the turn</em> — good
 * when the caller reasons over it inline. This service returns only a
 * pointer + summary: the corpus lands in a document instead of the context
 * window, so a parent process can hand the (possibly large) document off and
 * keep working on it with ranged reads / grep while using the cheap summary.
 * It is a superset of {@code investigate}, never a parallel research path.
 *
 * <p>All writes run under the caller's {@link WriteActor}; the tool layer
 * has already enforced {@code CREATE} on the target project before we get
 * here. Nothing writes as SYSTEM — a caller can only produce a document
 * where it may create one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResearchDocumentService {

    /** Internal LightLlm recipe (config profile) that synthesizes the body. */
    static final String RECIPE_NAME = "research-synthesize";

    /** Permissive schema — the recipe template pins the real reply shape. */
    static final Map<String, Object> SYNTHESIS_SCHEMA = Map.of("type", "object");

    /** Cap on how many ranked hits are inlined into the synthesis prompt. */
    static final int MAX_SOURCES_IN_PROMPT = 40;

    /** Cap on source notes attached to the document (well under NOTES_MAX). */
    static final int MAX_SOURCE_NOTES = 100;

    private static final String METRIC = "vance.research.document";
    private static final String MARKER_TAG = "research";
    private static final String DEFAULT_DIR = "research/";
    private static final String NOTE_AUTHOR_FALLBACK = "_research";

    private final ZarniwoopResearchService researchService;
    private final LightLlmService lightLlm;
    private final DocumentService documentService;
    private final MetricService metricService;

    /**
     * Research {@code question}, synthesize a document, persist it, and
     * annotate it with its sources.
     *
     * @param question    the natural-language research question
     * @param basePath    concrete target path (the tool derives a default
     *                    from the question when the caller gave none); a
     *                    collision is resolved by appending {@code -N}
     * @param extraTags   caller-supplied tags, merged with the marker tag
     *                    and the tags the synthesizer proposes
     * @param projectName the resolved project the research and the document
     *                    both belong to
     * @param ctx         invocation context (tenant / user / process) —
     *                    forwarded to the research pass for scope + error
     *                    attribution
     * @param actor       the caller's write authority for every persistence
     *                    step
     * @throws ZarniwoopException on missing scope, no usable sources, an
     *                            empty synthesis, or a synthesis failure
     */
    public ResearchDocumentResult createDocument(
            String question,
            String basePath,
            List<String> extraTags,
            String projectName,
            ToolInvocationContext ctx,
            WriteActor actor) {

        if (StringUtils.isBlank(question)) {
            throw new ZarniwoopException("question is required");
        }
        if (StringUtils.isBlank(projectName)) {
            throw new ZarniwoopException("research_document requires a project scope");
        }

        String tenantId = ctx.tenantId();
        SearchScope scope = new SearchScope(tenantId, projectName, ctx.processId(), ctx.userId());

        RankedHitSet hits = researchService.investigate(question, scope, ctx);
        if (hits.keptHits().isEmpty()) {
            metricService.counter(METRIC, "outcome", "no_sources").increment();
            throw new ZarniwoopException("research produced no usable sources for: " + question);
        }

        Map<String, Object> synth = synthesize(question, hits, tenantId, projectName, ctx.processId());

        String title = firstNonBlank(str(synth.get("title")), question);
        String body = str(synth.get("body"));
        if (StringUtils.isBlank(body)) {
            metricService.counter(METRIC, "outcome", "empty_body").increment();
            throw new ZarniwoopException("synthesis returned an empty document body");
        }
        String summary = str(synth.get("summary"));
        List<String> tags = mergeTags(extraTags, stringList(synth.get("tags")));

        String path = uniquePath(tenantId, projectName, basePath);
        String createdBy = ctx.userId() != null ? ctx.userId() : MARKER_TAG;

        DocumentDocument doc;
        try {
            doc = documentService.createText(
                    tenantId, projectName, path, title, tags, body, createdBy, actor);
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            // Lost a race between uniquePath() and create — surface cleanly.
            metricService.counter(METRIC, "outcome", "path_conflict").increment();
            throw new ZarniwoopException(e.getMessage(), e);
        }

        if (!StringUtils.isBlank(summary)) {
            documentService.setSummary(doc.getId(), summary, actor);
        }

        int noteCount = attachSourceNotes(doc.getId(), hits.keptHits(), ctx.userId(), actor);

        metricService.counter(METRIC, "outcome", "success").increment();
        log.trace("research_document created path='{}' sources={} notes={}",
                doc.getPath(), hits.keptHits().size(), noteCount);

        return new ResearchDocumentResult(
                doc.getId(),
                projectName,
                doc.getPath(),
                title,
                StringUtils.isBlank(summary) ? null : summary,
                noteCount,
                tags,
                List.copyOf(hits.gaps()));
    }

    // ── Synthesis ─────────────────────────────────────────────────

    private Map<String, Object> synthesize(
            String question, RankedHitSet hits, String tenantId,
            String projectName, @Nullable String processId) {

        List<Map<String, Object>> sources = new ArrayList<>();
        int index = 1;
        for (RankedHit hit : hits.keptHits()) {
            if (index > MAX_SOURCES_IN_PROMPT) {
                break;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("index", index++);
            source.put("title", hit.title());
            source.put("url", hit.url());
            if (!StringUtils.isBlank(hit.snippet())) {
                source.put("snippet", hit.snippet());
            }
            if (!StringUtils.isBlank(hit.relevanceNote())) {
                source.put("relevanceNote", hit.relevanceNote());
            }
            sources.add(source);
        }

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("question", question);
        vars.put("sources", sources);
        vars.put("gaps", new ArrayList<>(hits.gaps()));

        try {
            return lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE_NAME)
                    .userPrompt(question)
                    .pebbleVars(vars)
                    .schema(SYNTHESIS_SCHEMA)
                    .tenantId(tenantId)
                    .projectId(projectName)
                    .processId(processId)
                    .build());
        } catch (RuntimeException e) {
            metricService.counter(METRIC, "outcome", "synthesis_failed").increment();
            throw new ZarniwoopException("research synthesis failed: " + e.getMessage(), e);
        }
    }

    // ── Source notes ──────────────────────────────────────────────

    private int attachSourceNotes(
            String docId, List<RankedHit> keptHits, @Nullable String userId, WriteActor actor) {
        String author = userId != null ? userId : NOTE_AUTHOR_FALLBACK;
        int count = 0;
        for (RankedHit hit : keptHits) {
            if (count >= MAX_SOURCE_NOTES) {
                break;
            }
            try {
                documentService.addNote(docId, noteText(hit), author, null, null, actor);
                count++;
            } catch (DocumentService.NotesLimitExceededException e) {
                log.trace("notes cap reached on doc {} after {} source notes", docId, count);
                break;
            }
        }
        return count;
    }

    private static String noteText(RankedHit hit) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(sanitizeLinkText(hit.title())).append("](").append(hit.url()).append(')');
        if (!StringUtils.isBlank(hit.relevanceNote())) {
            sb.append(" — ").append(hit.relevanceNote().strip());
        }
        return sb.toString();
    }

    /** Keep a title from breaking the surrounding Markdown link. */
    private static String sanitizeLinkText(String title) {
        return title.replace('\n', ' ').replace('\r', ' ').replace(']', ')').strip();
    }

    // ── Path derivation ───────────────────────────────────────────

    /**
     * Default document path for a question when the caller supplies none:
     * {@code research/<slug>.md}. Public so the tool derives the exact same
     * path it enforces {@code CREATE} on before delegating.
     */
    public static String deriveDefaultPath(String question) {
        String slug = slugify(question);
        return DEFAULT_DIR + (slug.isEmpty() ? MARKER_TAG : slug) + ".md";
    }

    private static String slugify(String s) {
        String slug = s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        return slug;
    }

    private String uniquePath(String tenantId, String projectName, String basePath) {
        if (documentService.findByPath(tenantId, projectName, basePath).isEmpty()) {
            return basePath;
        }
        int dot = basePath.lastIndexOf('.');
        int slash = basePath.lastIndexOf('/');
        String stem = dot > slash ? basePath.substring(0, dot) : basePath;
        String ext = dot > slash ? basePath.substring(dot) : "";
        for (int n = 2; n <= 1000; n++) {
            String candidate = stem + "-" + n + ext;
            if (documentService.findByPath(tenantId, projectName, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new ZarniwoopException("could not find a free document path near '" + basePath + "'");
    }

    // ── Tags + small helpers ──────────────────────────────────────

    private static List<String> mergeTags(List<String> extraTags, List<String> llmTags) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.add(MARKER_TAG);
        addTags(tags, llmTags);
        addTags(tags, extraTags);
        return new ArrayList<>(tags);
    }

    private static void addTags(Set<String> into, @Nullable List<String> from) {
        if (from == null) {
            return;
        }
        for (String tag : from) {
            if (!StringUtils.isBlank(tag)) {
                into.add(tag.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static @Nullable String str(@Nullable Object o) {
        return o instanceof String s ? s : null;
    }

    private static String firstNonBlank(@Nullable String a, String b) {
        return StringUtils.isBlank(a) ? b : a;
    }

    private static List<String> stringList(@Nullable Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object e : list) {
            if (e instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }
}
