package de.mhus.vance.brain.tools;

import de.mhus.vance.api.tools.ToolSpec;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-call tools surface exposed to a think-engine through
 * {@code ThinkEngineContext.tools()}. Wraps the {@link ToolDispatcher}
 * with a pre-bound {@link ToolInvocationContext} so engines don't have
 * to re-build the scope on every call.
 *
 * <p>Two-bucket visibility model (see
 * {@code planning/tool-schema-deferral.md} §4):
 *
 * <ul>
 *   <li><b>Primary</b> — tools whose schemas are sent on every turn.
 *       The LLM can call them directly. {@link #primaryAsLc4j()} returns
 *       {@code primary ∪ (deferred ∩ activated)} ready to drop into a
 *       {@code ChatRequest}.</li>
 *   <li><b>Deferred</b> — tools the LLM only sees as
 *       {@code (name, searchHint)} in the system-prompt discovery block.
 *       The LLM activates one by calling {@code describe_tool(name)}.
 *       Activation is tracked on the process via
 *       {@link de.mhus.vance.shared.thinkprocess.ThinkProcessDocument#getActivatedDeferredTools()}.
 *       {@link #listDeferredForDiscovery()} returns the entries that
 *       still need a discovery-block line (deferred minus activated).</li>
 * </ul>
 *
 * <p><b>Allow-list filter.</b> The {@code allowed} set is the effective
 * dispatcher pool — every tool the engine may invoke this turn. It
 * unions primary + deferred. {@link #invoke} rejects anything outside
 * the pool.
 *
 * <p>The classification (which allowed tools are primary, which are
 * deferred) and the live activation set are pre-computed by
 * {@link DefaultThinkEngineContext#tools()} via
 * {@link #classify(ToolDispatcher, ToolInvocationContext, java.util.Set,
 * de.mhus.vance.brain.recipe.RecipeResolver.ToolFilter, java.util.Set)}.
 */
public final class ContextToolsApi {

    private final ToolDispatcher dispatcher;
    private final ToolInvocationContext ctx;
    private final Set<String> allowed;
    private final Set<String> primary;
    private final Set<String> deferred;
    private final Set<String> activatedDeferred;
    private final ToolInvocationListener listener;
    private final java.util.function.Consumer<String> activationRefresh;

    public ContextToolsApi(ToolDispatcher dispatcher, ToolInvocationContext ctx) {
        this(dispatcher, ctx, Set.of(), Set.of(), Set.of(), Set.of(),
                ToolInvocationListener.NOOP, null);
    }

    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed) {
        this(dispatcher, ctx, allowed, allowed, Set.of(), Set.of(),
                ToolInvocationListener.NOOP, null);
    }

    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed,
            ToolInvocationListener listener) {
        this(dispatcher, ctx, allowed, allowed, Set.of(), Set.of(), listener, null);
    }

    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed,
            Set<String> primary,
            Set<String> deferred,
            Set<String> activatedDeferred,
            ToolInvocationListener listener) {
        this(dispatcher, ctx, allowed, primary, deferred, activatedDeferred, listener, null);
    }

    /**
     * Full constructor. {@code primary} and {@code deferred} must be
     * disjoint subsets of {@code allowed}. {@code activatedDeferred}
     * must be a subset of {@code deferred}; entries outside that set
     * are silently ignored.
     *
     * <p>{@code activationRefresh}, when non-null, is called with the
     * tool name on every successful {@link #invoke} of a tool in the
     * {@code activatedDeferred} set. The wiring layer uses it to bump
     * the activation timestamp on the process so frequently-used
     * deferred tools resist TTL decay (sliding TTL, see §6).
     */
    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed,
            Set<String> primary,
            Set<String> deferred,
            Set<String> activatedDeferred,
            ToolInvocationListener listener,
            java.util.function.@org.jspecify.annotations.Nullable Consumer<String> activationRefresh) {
        this.dispatcher = dispatcher;
        this.ctx = ctx;
        this.allowed = allowed == null ? Set.of() : Set.copyOf(allowed);
        this.primary = primary == null ? Set.of() : Set.copyOf(primary);
        this.deferred = deferred == null ? Set.of() : Set.copyOf(deferred);
        this.activatedDeferred = activatedDeferred == null ? Set.of() : Set.copyOf(activatedDeferred);
        this.listener = listener == null ? ToolInvocationListener.NOOP : listener;
        this.activationRefresh = activationRefresh;
    }

    /** All tools visible in this scope (after the engine's allow-filter). */
    public List<ToolSpec> listAll() {
        return ToolDispatcher.specs(filter(dispatcher.resolveAll(ctx)));
    }

    /**
     * Tools the LLM sees on every turn — i.e. primary plus any deferred
     * tools that have been activated. Sorted alphabetically by name so
     * the prompt-cache marker stays stable across turns. For
     * unrestricted engines (no allow-set, no classification), falls back
     * to the per-tool {@link Tool#primary()} flag.
     */
    public List<ToolSpec> listPrimary() {
        return ToolDispatcher.specs(visibleResolved());
    }

    /**
     * Deferred tools to render in the system-prompt discovery block —
     * <i>all</i> tools currently in the deferred bucket, regardless of
     * activation. Per spec the discovery block is cache-stable
     * (per-engine + per-recipe + per-mode), so listing already-activated
     * tools here is intentional: an activated tool also shows up in the
     * primary tool manifest, so the LLM finds it there for direct calls.
     * See {@code planning/tool-schema-deferral.md} §4.5 / §7.
     *
     * <p>Sorted alphabetically by name.
     */
    public List<ToolSpec> listDeferredForDiscovery() {
        if (deferred.isEmpty()) return List.of();
        return ToolDispatcher.specs(
                dispatcher.resolveAll(ctx).stream()
                        .filter(r -> deferred.contains(r.tool().name()))
                        .sorted(java.util.Comparator.comparing(r -> r.tool().name()))
                        .toList());
    }

    /**
     * Markdown rendering of {@link #listDeferredForDiscovery()} for
     * direct inclusion in the engine system prompt. Empty string when
     * no deferred tools exist (caller can skip the block entirely).
     */
    public String discoveryBlockMarkdown() {
        List<ToolSpec> entries = listDeferredForDiscovery();
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Available deferred tools\n\n")
                .append("These tools are visible to you only by name + hint. To use one, "
                        + "first call `describe_tool(name=\"<name>\")` — it returns the "
                        + "full schema and activates the tool, so subsequent turns can "
                        + "call it directly.\n\n");
        for (ToolSpec spec : entries) {
            String hint = spec.getSearchHint() == null || spec.getSearchHint().isBlank()
                    ? spec.getDescription()
                    : spec.getSearchHint();
            sb.append("- `").append(spec.getName()).append("` — ").append(hint).append('\n');
        }
        return sb.toString();
    }

    /**
     * Invoke by name. Unknown tool, denied tool, or failure
     * → {@link ToolException}. The wired
     * {@link ToolInvocationListener} (if any) is called before and
     * after dispatch — including on the failure path.
     */
    public Map<String, Object> invoke(String name, Map<String, Object> params) {
        if (!isAllowed(name)) {
            throw new ToolException(
                    "Tool '" + name + "' is not in this engine's allowed tool-pool");
        }
        listener.before(name);
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> result = dispatcher.invoke(name, params, ctx, this);
            listener.after(name, System.currentTimeMillis() - startMs, null);
            // Sliding TTL: bump the activation timestamp on every use of
            // an activated deferred tool so the discovery cycle doesn't
            // rip a frequently-used tool out from under the LLM.
            if (activationRefresh != null && activatedDeferred.contains(name)) {
                try {
                    activationRefresh.accept(name);
                } catch (RuntimeException refreshErr) {
                    // Refresh failures are non-fatal — the tool call
                    // already succeeded; let the caller see the result.
                }
            }
            return result;
        } catch (RuntimeException e) {
            listener.after(name, System.currentTimeMillis() - startMs, e);
            throw e;
        }
    }

    /**
     * Primary tools projected to langchain4j {@link ToolSpecification}s
     * — ready to drop into {@code ChatRequest.builder().toolSpecifications(...)}.
     */
    public List<ToolSpecification> primaryAsLc4j() {
        return visibleResolved().stream()
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

    /** Names classified as primary for this turn. */
    public Set<String> primary() {
        return primary;
    }

    /** Names classified as deferred for this turn. */
    public Set<String> deferred() {
        return deferred;
    }

    /** Activated deferred tool-names — visible to the LLM on top of {@link #primary()}. */
    public Set<String> activatedDeferred() {
        return activatedDeferred;
    }

    /**
     * Returns a new {@link ContextToolsApi} whose allow-set is the
     * union of this one's plus {@code extra}. New entries land in the
     * primary bucket (skill-required tools should always be visible to
     * the LLM in this turn). Used by the lane-turn pipeline to expose
     * skill-required tools without mutating the persisted
     * {@code allowedToolsOverride} on the process.
     *
     * <p>If this surface is unrestricted (empty allow-set), it is
     * returned as-is — adding tools to "see everything" is a no-op.
     * If {@code extra} is null/empty, the surface is also returned
     * as-is.
     */
    public ContextToolsApi withAdditional(Set<String> extra) {
        if (allowed.isEmpty() || extra == null || extra.isEmpty()) {
            return this;
        }
        Set<String> mergedAllowed = new LinkedHashSet<>(allowed);
        Set<String> mergedPrimary = new LinkedHashSet<>(primary);
        boolean changed = false;
        for (String e : extra) {
            if (mergedAllowed.add(e)) changed = true;
            if (mergedPrimary.add(e)) changed = true;
        }
        if (!changed) return this;
        return new ContextToolsApi(
                dispatcher, ctx, mergedAllowed, mergedPrimary,
                deferred, activatedDeferred, listener);
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
     * Resolves what the LLM sees this turn:
     * <ul>
     *   <li><i>Classified engine</i> — primary plus activated deferred,
     *       sorted by name.</li>
     *   <li><i>Restricted, unclassified engine</i> — every allowed tool,
     *       regardless of {@link Tool#primary()} (legacy fallback).</li>
     *   <li><i>Unrestricted engine</i> — per-tool {@code primary()}
     *       flag (Ford default).</li>
     * </ul>
     */
    private List<ToolDispatcher.Resolved> visibleResolved() {
        Set<String> visible;
        if (!primary.isEmpty() || !deferred.isEmpty()) {
            // Classified path: primary + activated deferred.
            visible = new LinkedHashSet<>(primary);
            visible.addAll(activatedDeferred);
        } else if (!allowed.isEmpty()) {
            // Restricted-only legacy: full allow-set acts as primary.
            visible = allowed;
        } else {
            // Unrestricted Ford-style: per-tool primary().
            return dispatcher.resolvePrimary(ctx).stream()
                    .sorted(java.util.Comparator.comparing(r -> r.tool().name()))
                    .toList();
        }
        if (visible.isEmpty()) return List.of();
        // Resolve, dedup by name (dispatcher already does first-wins),
        // and sort alphabetically — cache-marker stability requirement.
        List<ToolDispatcher.Resolved> all = dispatcher.resolveAll(ctx);
        List<ToolDispatcher.Resolved> out = new ArrayList<>(visible.size());
        for (ToolDispatcher.Resolved r : all) {
            if (visible.contains(r.tool().name())) out.add(r);
        }
        out.sort(java.util.Comparator.comparing(r -> r.tool().name()));
        return out;
    }

    /**
     * Classifies an effective dispatcher pool into (primary, deferred)
     * according to the per-turn {@code ToolFilter} and each tool's own
     * {@link Tool#deferred()} default. Apply order is Remove → Add →
     * Defer (§14.2): {@code remove} entries are subtracted from the
     * {@code base}; {@code add} promotes a tool to primary even if its
     * default would put it in deferred; {@code defer} demotes a tool
     * to deferred even if its default would put it in primary.
     *
     * @param base   effective dispatcher pool (already resolved).
     *               Empty → unrestricted; classification returns
     *               ({@link Set#of()}, {@link Set#of()}) and callers
     *               fall back to per-tool {@link Tool#primary()}.
     * @param filter per-turn overlays from {@code RecipeResolver.toolFilterFor}
     */
    public static Classification classify(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> base,
            de.mhus.vance.brain.recipe.RecipeResolver.ToolFilter filter,
            Set<String> activatedDeferred) {
        if (base == null || base.isEmpty()) {
            return new Classification(Set.of(), Set.of(), Set.of(), Set.of());
        }
        Set<String> remove = filter == null ? Set.of() : Set.copyOf(filter.remove());
        Set<String> add = filter == null ? Set.of() : Set.copyOf(filter.add());
        Set<String> defer = filter == null ? Set.of() : Set.copyOf(filter.defer());

        // Effective dispatch pool = base − remove
        Set<String> effective = new LinkedHashSet<>(base);
        effective.removeAll(remove);

        // Resolve each tool to consult its default deferred() flag.
        Set<String> primary = new LinkedHashSet<>();
        Set<String> deferred = new LinkedHashSet<>();
        for (String name : effective) {
            boolean isDeferred;
            if (defer.contains(name)) {
                isDeferred = true;
            } else if (add.contains(name)) {
                isDeferred = false;
            } else {
                isDeferred = dispatcher.resolve(name, ctx)
                        .map(r -> r.tool().deferred())
                        .orElse(false);
            }
            if (isDeferred) deferred.add(name);
            else primary.add(name);
        }
        Set<String> activated = activatedDeferred == null
                ? Set.of()
                : activatedDeferred.stream()
                        .filter(deferred::contains)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new Classification(effective, primary, deferred, activated);
    }

    /**
     * Result of {@link #classify}. Holds the four sets the engine
     * passes into the {@link ContextToolsApi} constructor.
     */
    public record Classification(
            Set<String> allowed,
            Set<String> primary,
            Set<String> deferred,
            Set<String> activatedDeferred) {
    }
}
