package de.mhus.vance.brain.history;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Pure-functional translator from a tool invocation to its history
 * marker tags. The output is the set the tool-dispatcher hook hands to
 * the tag sink — see {@code planning/process-history-search.md} §5.1.
 *
 * <p>Tag layout:
 * <ul>
 *   <li>{@code TOOL_CALL:<tool-name>} — always, on success and on error.</li>
 *   <li>{@code RESOURCE:<typed-key>} — only when the tool carries a
 *       {@code write} label plus a resource-type label
 *       ({@code client-file}, {@code workspace}, {@code document}) and
 *       the result map (or, as a fallback, the params) exposes the
 *       expected key field.</li>
 *   <li>{@code FILE_EDIT} / {@code DOC_EDIT} — denormalised classifier
 *       so {@code history_search} can filter by category without
 *       prefix-matching on resource keys.</li>
 *   <li>{@code ERROR} — added by {@link #onError(String)} when a tool
 *       call threw.</li>
 * </ul>
 *
 * <p>No engine state is read here; the builder is stateless and safe
 * to call from any thread. Spring-managed component so the dispatcher
 * can {@code @Autowired} it; the no-arg public state lets unit tests
 * instantiate it without context.
 */
@Component
public class HistoryTagBuilder {

    /** Resource-type label values understood by this builder. */
    public static final String LABEL_WRITE = "write";
    public static final String LABEL_CLIENT_FILE = "client-file";
    public static final String LABEL_SCRATCH = "scratch";
    public static final String LABEL_DOCUMENT = "document";

    /** Tag prefixes (constants for cross-component reuse). */
    public static final String TAG_TOOL_CALL_PREFIX = "TOOL_CALL:";
    public static final String TAG_RESOURCE_PREFIX = "RESOURCE:";
    public static final String TAG_FILE_EDIT = "FILE_EDIT";
    public static final String TAG_DOC_EDIT = "DOC_EDIT";
    public static final String TAG_ERROR = "ERROR";

    /**
     * Compute marker tags for a successful tool invocation.
     *
     * @param toolName the dispatched name (used verbatim in the
     *                 {@code TOOL_CALL:} tag).
     * @param tool the resolved tool — its {@link Tool#labels()} drives
     *             resource-type detection. May be {@code null}; in that
     *             case only the {@code TOOL_CALL:} tag is returned.
     * @param params the params map passed to the tool, used as a path
     *               fallback when the result lacks one.
     * @param result the tool's return map.
     * @param ctx the invocation scope, used for the {@code SCRATCH:}
     *            key (which is process-scoped).
     */
    public Set<String> onSuccess(
            String toolName,
            @Nullable Tool tool,
            @Nullable Map<String, Object> params,
            @Nullable Map<String, Object> result,
            ToolInvocationContext ctx) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(TAG_TOOL_CALL_PREFIX + toolName);
        if (tool == null) return tags;

        Set<String> labels = tool.labels();
        if (labels == null || !labels.contains(LABEL_WRITE)) return tags;

        if (labels.contains(LABEL_CLIENT_FILE)) {
            String path = stringFrom(result, "path");
            if (path == null) path = stringFrom(params, "path");
            if (path != null) {
                tags.add(TAG_RESOURCE_PREFIX + "CLIENT_FILE:" + normalisePath(path));
                tags.add(TAG_FILE_EDIT);
            }
        } else if (labels.contains(LABEL_SCRATCH)) {
            String path = stringFrom(result, "path");
            if (path == null) path = stringFrom(params, "path");
            if (path != null && ctx.processId() != null) {
                tags.add(TAG_RESOURCE_PREFIX + "SCRATCH:" + ctx.processId() + "/" + path);
                tags.add(TAG_FILE_EDIT);
            }
        } else if (labels.contains(LABEL_DOCUMENT)) {
            String docId = documentIdFrom(result);
            if (docId == null) docId = documentIdFrom(params);
            if (docId != null) {
                tags.add(TAG_RESOURCE_PREFIX + "DOCUMENT:" + docId);
                tags.add(TAG_DOC_EDIT);
            }
        }
        return tags;
    }

    /**
     * Probes a result/params map for a document identifier under any of
     * the names the kind-tools use today. Order matches the conceptual
     * "what was just produced" → "what was operated on" → "what we
     * found by source".
     *
     * <p>Discrepancy in tool returns is the reason this kicks: <ul>
     *   <li>{@link de.mhus.vance.brain.tools.kinds.DocEditTool} returns
     *       {@code documentId} (canonical).</li>
     *   <li>{@link de.mhus.vance.brain.tools.kinds.DocCreateKindTool}
     *       returns {@code id} (Mongo-style short).</li>
     *   <li>{@link de.mhus.vance.brain.tools.kinds.DocPurgeTool}
     *       returns {@code purgedId}.</li>
     *   <li>{@code CrossDocCopy} / {@code CrossDocMove} / {@code DocCopy}
     *       return {@code newId} (the produced copy) plus
     *       {@code sourceId}; we tag against {@code newId} since that is
     *       the freshly written resource.</li>
     * </ul>
     * Standardising the tool returns would be cleaner long-term; this
     * fallback chain pays the cost in one place instead.
     */
    private static @Nullable String documentIdFrom(@Nullable Map<String, Object> map) {
        if (map == null) return null;
        String s = stringFrom(map, "documentId");
        if (s == null) s = stringFrom(map, "docId");
        // newId = freshly produced copy/created doc (CrossDocCopy/Move,
        // DocCopy, DocCreateKind family). Prefer over sourceId because the
        // edit landed on the new resource.
        if (s == null) s = stringFrom(map, "newId");
        if (s == null) s = stringFrom(map, "purgedId");
        if (s == null) s = stringFrom(map, "id");
        if (s == null) s = stringFrom(map, "sourceId");
        return s;
    }

    /**
     * Compute marker tags for a failed tool invocation. Engines emit
     * the call name plus {@code ERROR}; resource tags are deliberately
     * skipped because the tool may not have produced a usable result.
     */
    public Set<String> onError(String toolName) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(TAG_TOOL_CALL_PREFIX + toolName);
        tags.add(TAG_ERROR);
        return tags;
    }

    private static @Nullable String stringFrom(
            @Nullable Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v instanceof String s && !s.isBlank()) return s;
        return null;
    }

    /**
     * Best-effort path normalisation so that {@code /a/./b} and
     * {@code /a/b} collapse to the same resource key. Falls back to
     * the original string when the path can't be parsed (e.g. an
     * unusual scheme); we don't reject — the tag is best-effort.
     */
    private static String normalisePath(String raw) {
        try {
            return Path.of(raw).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return raw;
        }
    }
}
