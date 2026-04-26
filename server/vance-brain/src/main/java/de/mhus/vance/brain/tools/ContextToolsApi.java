package de.mhus.vance.brain.tools;

import de.mhus.vance.api.tools.ToolSpec;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-call tools surface exposed to a think-engine through
 * {@code ThinkEngineContext.tools()}. Wraps the {@link ToolDispatcher}
 * with a pre-bound {@link ToolInvocationContext} so engines don't have
 * to re-build the scope on every call.
 *
 * <p><b>Allow-list filter.</b> If the calling engine declared an
 * {@code allowedTools()} set, every read here projects through that
 * set and {@link #invoke} rejects unknown names — the engine cannot
 * see or call tools outside its declared pool. An empty allow-set
 * means "no restriction" (Zaphod-style: see everything).
 *
 * <p><b>Primary-vs-secondary collapse for restricted engines.</b>
 * The per-tool {@code primary} flag exists so the LLM doesn't get
 * flooded with every tool the dispatcher knows. A restricted engine
 * has already curated its pool to the few tools the LLM actually
 * needs, so the per-tool flag becomes noise. When {@link #allowed}
 * is non-empty, every allowed tool is treated as primary — the LLM
 * sees the full declared pool every turn, and there's no
 * find-tools-then-invoke dance for tools the engine itself opted into.
 */
public final class ContextToolsApi {

    private final ToolDispatcher dispatcher;
    private final ToolInvocationContext ctx;
    private final Set<String> allowed;

    public ContextToolsApi(ToolDispatcher dispatcher, ToolInvocationContext ctx) {
        this(dispatcher, ctx, Set.of());
    }

    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed) {
        this.dispatcher = dispatcher;
        this.ctx = ctx;
        this.allowed = allowed == null ? Set.of() : Set.copyOf(allowed);
    }

    /** All tools visible in this scope (after the engine's allow-filter). */
    public List<ToolSpec> listAll() {
        return ToolDispatcher.specs(filter(dispatcher.resolveAll(ctx)));
    }

    /**
     * Primary tools — what the LLM sees every turn (after the
     * allow-filter). For restricted engines, the per-tool primary
     * flag is ignored: the LLM gets the full allowed pool.
     */
    public List<ToolSpec> listPrimary() {
        return ToolDispatcher.specs(primaryResolved());
    }

    /**
     * Invoke by name. Unknown tool, denied tool, or failure
     * → {@link ToolException}.
     */
    public Map<String, Object> invoke(String name, Map<String, Object> params) {
        if (!isAllowed(name)) {
            throw new ToolException(
                    "Tool '" + name + "' is not in this engine's allowed tool-pool");
        }
        return dispatcher.invoke(name, params, ctx);
    }

    /**
     * Primary tools projected to langchain4j {@link ToolSpecification}s
     * — ready to drop into {@code ChatRequest.builder().toolSpecifications(...)}.
     */
    public List<ToolSpecification> primaryAsLc4j() {
        return primaryResolved().stream()
                .map(r -> ToolSpecification.builder()
                        .name(r.tool().name())
                        .description(r.tool().description())
                        .parameters(Lc4jSchema.toObjectSchema(r.tool().paramsSchema()))
                        .build())
                .toList();
    }

    /** The scope this API is bound to — exposed for tools that need it. */
    public ToolInvocationContext scope() {
        return ctx;
    }

    /** Escape hatch: underlying dispatcher for resolve-then-invoke patterns. */
    public ToolDispatcher dispatcher() {
        return dispatcher;
    }

    /** The allow-set this surface was built with. Empty → unrestricted. */
    public Set<String> allowed() {
        return allowed;
    }

    private boolean isAllowed(String toolName) {
        return allowed.isEmpty() || allowed.contains(toolName);
    }

    private List<ToolDispatcher.Resolved> filter(List<ToolDispatcher.Resolved> resolved) {
        if (allowed.isEmpty()) return resolved;
        return resolved.stream()
                .filter(r -> allowed.contains(r.tool().name()))
                .toList();
    }

    /**
     * Resolves what the LLM should see this turn:
     * <ul>
     *   <li><i>Restricted engine</i> — every allowed tool, regardless
     *       of its per-tool {@code primary} flag.</li>
     *   <li><i>Unrestricted engine</i> — only tools whose own
     *       {@code primary()} returns {@code true}, the original
     *       Zaphod default.</li>
     * </ul>
     */
    private List<ToolDispatcher.Resolved> primaryResolved() {
        if (allowed.isEmpty()) {
            return dispatcher.resolvePrimary(ctx);
        }
        return filter(dispatcher.resolveAll(ctx));
    }
}
