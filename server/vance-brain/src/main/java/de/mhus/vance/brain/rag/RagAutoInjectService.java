package de.mhus.vance.brain.rag;

import de.mhus.vance.shared.rag.RagBackend.SearchHit;
import de.mhus.vance.shared.rag.RagDocument;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Pre-turn RAG auto-injection (Variante C: Pre-Turn-Hybrid with score
 * threshold). When the recipe's {@code rag.autoInject} param is on (or the
 * cascade setting {@code rag.autoInject.enabled} overrides to true), the
 * service embeds the last user message, queries the project-default RAG
 * ({@code _documents}), filters by minimum score and renders a compact
 * markdown block that engines append to their system prompt.
 *
 * <p>Pure read path — never mutates the RAG. Returns {@code null} when:
 * no RAG exists, auto-inject is off, query text is blank, or no hit
 * passes the threshold. Engines treat {@code null} as "skip the block".
 *
 * <p>Recipe-param defaults (see {@code specification/rag.md} §5):
 * <ul>
 *   <li>{@code rag.autoInject} — default {@code false}</li>
 *   <li>{@code rag.topK} — default {@code 5}</li>
 *   <li>{@code rag.minScore} — default {@code 0.65}</li>
 * </ul>
 * Cascade override: {@code rag.autoInject.enabled} (Tenant/Project) wins
 * over the recipe when set.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagAutoInjectService {

    /** Recipe-param key for the auto-inject master toggle. */
    public static final String PARAM_AUTO_INJECT = "rag.autoInject";
    /** Recipe-param key for the score threshold. */
    public static final String PARAM_MIN_SCORE = "rag.minScore";
    /** Recipe-param key for top-K. */
    public static final String PARAM_TOP_K = "rag.topK";
    /** Cascade-setting key — Tenant/Project override for the recipe param. */
    public static final String SETTING_AUTO_INJECT_OVERRIDE = "rag.autoInject.enabled";

    private static final double DEFAULT_MIN_SCORE = 0.65;
    private static final int DEFAULT_TOP_K = 5;

    private final ProjectRagService projectRagService;
    private final RagService ragService;
    private final SettingService settingService;

    /**
     * Render a {@code <rag-context>...</rag-context>} block for the given
     * process turn. Returns {@code null} when the block should be skipped.
     *
     * @param process     the current think process — provides tenant /
     *                    project / engine-params (the recipe params live
     *                    on the process).
     * @param queryText   the user message to query the RAG with (typically
     *                    this turn's freshly-arrived inbox text).
     */
    public @Nullable String composeBlock(ThinkProcessDocument process, @Nullable String queryText) {
        if (queryText == null || queryText.isBlank()) return null;
        if (!isEnabled(process)) return null;

        Optional<RagDocument> defaultRag = projectRagService.findDefaultRag(
                process.getTenantId(), process.getProjectId());
        if (defaultRag.isEmpty()) return null;

        int topK = readIntParam(process, PARAM_TOP_K, DEFAULT_TOP_K);
        double minScore = readDoubleParam(process, PARAM_MIN_SCORE, DEFAULT_MIN_SCORE);

        List<SearchHit> hits;
        try {
            hits = ragService.query(defaultRag.get().getId(), queryText, topK);
        } catch (RuntimeException e) {
            log.warn("RAG auto-inject query failed tenant='{}' project='{}': {}",
                    process.getTenantId(), process.getProjectId(), e.toString());
            return null;
        }
        if (hits.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (SearchHit hit : hits) {
            if (hit.score() < minScore) continue;
            if (kept == 0) {
                sb.append("<rag-context>\n");
                sb.append("Top relevant excerpts from the project's documents. ")
                  .append("Cite the source path when you use these.\n\n");
            }
            kept++;
            String path = sourcePath(hit);
            String kind = sourceKind(hit);
            sb.append("- ").append(path);
            if (kind != null && !"content".equals(kind)) sb.append(" [").append(kind).append(']');
            sb.append(" (score ").append(String.format("%.2f", hit.score())).append(")\n");
            sb.append("  ").append(oneLine(hit.chunk().getContent())).append("\n\n");
        }
        if (kept == 0) return null;
        sb.append("</rag-context>");
        log.debug("RAG auto-inject tenant='{}' project='{}' query-len={} hits={}/{}",
                process.getTenantId(), process.getProjectId(),
                queryText.length(), kept, hits.size());
        return sb.toString();
    }

    private boolean isEnabled(ThinkProcessDocument process) {
        // Cascade override wins when explicitly set (true/false). When the
        // setting is absent the recipe param decides.
        String raw = settingService.getStringValueCascade(
                process.getTenantId(), process.getProjectId(),
                /*thinkProcessId*/ null, SETTING_AUTO_INJECT_OVERRIDE);
        if (raw != null && !raw.isBlank()) {
            String n = raw.trim().toLowerCase();
            if ("true".equals(n) || "1".equals(n) || "yes".equals(n) || "on".equals(n)) return true;
            if ("false".equals(n) || "0".equals(n) || "no".equals(n) || "off".equals(n)) return false;
        }
        return readBoolParam(process, PARAM_AUTO_INJECT, false);
    }

    private static boolean readBoolParam(ThinkProcessDocument process, String key, boolean defaultValue) {
        Object v = readParam(process.getEngineParams(), key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) {
            String n = s.trim().toLowerCase();
            if ("true".equals(n) || "1".equals(n) || "yes".equals(n) || "on".equals(n)) return true;
            if ("false".equals(n) || "0".equals(n) || "no".equals(n) || "off".equals(n)) return false;
        }
        return defaultValue;
    }

    private static int readIntParam(ThinkProcessDocument process, String key, int defaultValue) {
        Object v = readParam(process.getEngineParams(), key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return defaultValue;
    }

    private static double readDoubleParam(ThinkProcessDocument process, String key, double defaultValue) {
        Object v = readParam(process.getEngineParams(), key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return defaultValue;
    }

    /**
     * Walks a dotted key ({@code rag.autoInject}) across a possibly-nested
     * params map so recipes can write either flat (`"rag.autoInject": true`)
     * or nested (`rag: { autoInject: true }`) — Pebble exposes both
     * identically.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Object readParam(@Nullable Map<String, Object> params, String dottedKey) {
        if (params == null || params.isEmpty()) return null;
        if (params.containsKey(dottedKey)) return params.get(dottedKey);
        int dot = dottedKey.indexOf('.');
        if (dot < 0) return null;
        Object head = params.get(dottedKey.substring(0, dot));
        if (head instanceof Map<?, ?> nested) {
            return readParam((Map<String, Object>) nested, dottedKey.substring(dot + 1));
        }
        return null;
    }

    private static String sourcePath(SearchHit hit) {
        Object pathMeta = hit.chunk().getMetadata() == null ? null
                : hit.chunk().getMetadata().get("path");
        if (pathMeta instanceof String s && !s.isBlank()) return s;
        return hit.chunk().getSourceRef() == null ? "<unknown>" : hit.chunk().getSourceRef();
    }

    private static @Nullable String sourceKind(SearchHit hit) {
        Object kindMeta = hit.chunk().getMetadata() == null ? null
                : hit.chunk().getMetadata().get("kind");
        return kindMeta instanceof String s && !s.isBlank() ? s : null;
    }

    private static String oneLine(@Nullable String content) {
        if (content == null) return "";
        String collapsed = content.replaceAll("\\s+", " ").trim();
        if (collapsed.length() > 500) {
            return collapsed.substring(0, 497) + "...";
        }
        return collapsed;
    }
}
