package de.mhus.vance.brain.tools;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.history.HistoryTagBuilder;
import de.mhus.vance.brain.history.HistoryTagSink;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
public final class ContextToolsApi implements ToolBus {

    private final ToolDispatcher dispatcher;
    private final ToolInvocationContext ctx;
    private final Set<String> allowed;
    private final Set<String> primary;
    private final Set<String> deferred;
    private final Set<String> activatedDeferred;
    private final ToolInvocationListener listener;
    private final java.util.function.Consumer<String> activationRefresh;
    private final HistoryTagBuilder historyTagBuilder;
    private final HistoryTagSink historyTagSink;
    private final @org.jspecify.annotations.Nullable ToolResultStorage toolResultStorage;

    public ContextToolsApi(ToolDispatcher dispatcher, ToolInvocationContext ctx) {
        this(dispatcher, ctx, Set.of(), Set.of(), Set.of(), Set.of(),
                ToolInvocationListener.NOOP, null, null, HistoryTagSink.NOOP, null);
    }

    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed) {
        this(dispatcher, ctx, allowed, allowed, Set.of(), Set.of(),
                ToolInvocationListener.NOOP, null, null, HistoryTagSink.NOOP, null);
    }

    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed,
            ToolInvocationListener listener) {
        this(dispatcher, ctx, allowed, allowed, Set.of(), Set.of(),
                listener, null, null, HistoryTagSink.NOOP, null);
    }

    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed,
            Set<String> primary,
            Set<String> deferred,
            Set<String> activatedDeferred,
            ToolInvocationListener listener) {
        this(dispatcher, ctx, allowed, primary, deferred, activatedDeferred,
                listener, null, null, HistoryTagSink.NOOP, null);
    }

    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed,
            Set<String> primary,
            Set<String> deferred,
            Set<String> activatedDeferred,
            ToolInvocationListener listener,
            java.util.function.@org.jspecify.annotations.Nullable Consumer<String> activationRefresh) {
        this(dispatcher, ctx, allowed, primary, deferred, activatedDeferred,
                listener, activationRefresh, null, HistoryTagSink.NOOP, null);
    }

    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed,
            Set<String> primary,
            Set<String> deferred,
            Set<String> activatedDeferred,
            ToolInvocationListener listener,
            java.util.function.@org.jspecify.annotations.Nullable Consumer<String> activationRefresh,
            @org.jspecify.annotations.Nullable HistoryTagBuilder historyTagBuilder,
            @org.jspecify.annotations.Nullable HistoryTagSink historyTagSink) {
        this(dispatcher, ctx, allowed, primary, deferred, activatedDeferred,
                listener, activationRefresh, historyTagBuilder, historyTagSink, null);
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
     *
     * <p>{@code historyTagBuilder} + {@code historyTagSink}, when both
     * non-null/non-NOOP, install the history-tagging hook: on every
     * successful {@link #invoke} the builder computes marker tags from
     * the resolved tool's labels and the result map, and the sink
     * receives them. Engines wire the sink to the assistant
     * {@code ChatMessageDocument} they are currently building so the
     * tags land on the right turn. Default is {@link HistoryTagSink#NOOP}
     * — older call sites stay tag-less without code changes. See
     * {@code planning/process-history-search.md} §5.
     */
    /**
     * Full constructor (11-arg variant). Adds {@code toolResultStorage}
     * to install the output-truncation hook: when a tool returns a
     * result whose JSON-serialized form exceeds the configured threshold,
     * the original is persisted to disk and the LLM receives a stub map
     * with first-2KB preview + storage path. See
     * {@code planning/brain-context-assembler.md} §7.
     *
     * <p>Tag computation runs <em>before</em> truncation so the
     * history-search markers still extract the real {@code documentId} /
     * {@code path} from the full result — the truncation only affects
     * what the LLM sees this turn.
     */
    public ContextToolsApi(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> allowed,
            Set<String> primary,
            Set<String> deferred,
            Set<String> activatedDeferred,
            ToolInvocationListener listener,
            java.util.function.@org.jspecify.annotations.Nullable Consumer<String> activationRefresh,
            @org.jspecify.annotations.Nullable HistoryTagBuilder historyTagBuilder,
            @org.jspecify.annotations.Nullable HistoryTagSink historyTagSink,
            @org.jspecify.annotations.Nullable ToolResultStorage toolResultStorage) {
        this.dispatcher = dispatcher;
        this.ctx = ctx;
        this.allowed = allowed == null ? Set.of() : Set.copyOf(allowed);
        this.primary = primary == null ? Set.of() : Set.copyOf(primary);
        this.deferred = deferred == null ? Set.of() : Set.copyOf(deferred);
        this.activatedDeferred = activatedDeferred == null ? Set.of() : Set.copyOf(activatedDeferred);
        this.listener = listener == null ? ToolInvocationListener.NOOP : listener;
        this.activationRefresh = activationRefresh;
        this.historyTagBuilder = historyTagBuilder == null ? new HistoryTagBuilder() : historyTagBuilder;
        this.historyTagSink = historyTagSink == null ? HistoryTagSink.NOOP : historyTagSink;
        this.toolResultStorage = toolResultStorage;
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
                .append("These tools are listed by name + hint only (full schemas "
                        + "are kept out of the manifest to save tokens). You can "
                        + "call them directly — the engine activates them on first "
                        + "use. If you need the full parameter schema first, call "
                        + "`describe_tool(name=\"<name>\")`.\n\n");
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
    /**
     * LLM-emitted tool call.
     *
     * <ul>
     *   <li>In {@link #primary()} or {@link #activatedDeferred()} —
     *       dispatched normally.</li>
     *   <li>In {@link #deferred()} but not yet activated —
     *       auto-activated on the spot (Mongo stamp via
     *       {@code activationRefresh}) and then dispatched. The
     *       discovery block already told the LLM the tool exists;
     *       insisting on a separate {@code describe_tool} round-trip
     *       is pure ceremony for tools the model can call by name.
     *       Activation persists for the session (subject to TTL
     *       decay), so subsequent {@link #tools()} snapshots will
     *       carry the tool in {@link #primaryAsLc4j()} too.</li>
     *   <li>Not in this engine's allow-set — hard fail.</li>
     * </ul>
     *
     * <p>Engine action handlers that need to invoke any allow-set tool
     * (e.g. Arthur's DELEGATE handler calling {@code process_create}
     * in selector-routed mode) use {@link #invokeInternal} which
     * checks against the broader dispatch pool.
     */
    public Map<String, Object> invoke(String name, Map<String, Object> params) {
        if (isLlmVisible(name)) {
            return doInvoke(name, params);
        }
        if (deferred.contains(name)) {
            // Auto-activate on first direct call. Failure to stamp
            // Mongo is non-fatal — the tool dispatch itself is what
            // the LLM cares about; the activation only affects future
            // turns. Surface the activation failure via the listener
            // path, not by rejecting the call.
            if (activationRefresh != null) {
                try {
                    activationRefresh.accept(name);
                } catch (RuntimeException ignored) {
                    // Sliding-TTL refresh logic in doInvoke will not
                    // re-attempt (this name is not in activatedDeferred
                    // for the current snapshot); next ctx.tools() call
                    // re-reads Mongo and will fix things if it recovered.
                }
            }
            return doInvoke(name, params);
        }
        throw new ToolException(
                "Tool '" + name + "' is not available to this engine");
    }

    /**
     * Engine-internal invocation — bypasses the LLM-visibility check.
     * Used by think-engine action handlers that route LLM-emitted
     * actions through fixed tool calls (e.g. Arthur's DELEGATE action
     * dispatching to {@code process_create} in selector-routed mode
     * regardless of whether the LLM has the tool in its manifest).
     *
     * <p>Still gated by the dispatch allow-set: a tool not in
     * {@link #allowed()} cannot be invoked even internally.
     */
    public Map<String, Object> invokeInternal(String name, Map<String, Object> params) {
        if (!isInDispatch(name)) {
            throw new ToolException(
                    "Tool '" + name + "' is not in this engine's dispatch pool");
        }
        return doInvoke(name, params);
    }

    private Map<String, Object> doInvoke(String name, Map<String, Object> params) {
        listener.before(name);
        long startMs = System.currentTimeMillis();
        // Resolve once up-front so the history hook can inspect the
        // tool's labels without a second resolve. Cheap (map lookup).
        Optional<ToolDispatcher.Resolved> resolved = dispatcher.resolve(name, ctx);
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
            emitHistoryTags(historyTagBuilder.onSuccess(
                    name,
                    resolved.map(ToolDispatcher.Resolved::tool).orElse(null),
                    params, result, ctx));
            // Output-truncation comes AFTER tag extraction — the
            // builder needs the full result to find a documentId /
            // path. The LLM only sees the (possibly stubbed) form
            // returned here.
            return maybeTruncateResult(name, result);
        } catch (RuntimeException e) {
            listener.after(name, System.currentTimeMillis() - startMs, e);
            emitHistoryTags(historyTagBuilder.onError(name));
            throw e;
        }
    }

    /**
     * Best-effort emit to the history-tag sink. Tag-write failures must
     * not cascade back to the LLM — they are surfaced only via the
     * sink's own logging, never as a thrown exception from the tool
     * call path.
     */
    private void emitHistoryTags(Set<String> tags) {
        if (historyTagSink == HistoryTagSink.NOOP || tags.isEmpty()) return;
        try {
            historyTagSink.emit(tags);
        } catch (RuntimeException ignored) {
            // Sink errors are non-fatal — see HistoryTagSink Javadoc.
        }
    }

    /**
     * Passes the result through {@link ToolResultStorage#truncateIfLarge}
     * when storage is wired. Caller gets back either the original map
     * (small result) or a stub map with first-2KB preview + on-disk
     * storage path (large result). No-op when storage is null
     * (e.g. test ctors).
     */
    private Map<String, Object> maybeTruncateResult(String toolName, Map<String, Object> result) {
        if (toolResultStorage == null) return result;
        try {
            ToolResultPayload p = toolResultStorage.truncateIfLarge(result, ctx);
            return p.result();
        } catch (RuntimeException ignored) {
            // Truncation must never fail the tool call. Storage's own
            // fail-open contract handles disk errors; this catch covers
            // any other surprise. Return the original — noisy LLM
            // context beats a crashed turn.
            return result;
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

    /**
     * Deduplicated non-empty {@link Tool#promptHint() promptHints} for
     * every tool currently reachable in this scope — primary or
     * deferred. Engines join these into a single block and append them
     * to the system message, so each pack's calling conventions surface
     * at exactly the moment the LLM has the pack available. Empty when
     * no reachable tool carries a hint.
     *
     * <p>Pack-level hints normally repeat across all sub-tools of one
     * pack; we dedupe by hint content so the prompt carries each unique
     * note exactly once. Order is stable across calls (insertion order),
     * which preserves cache markers when nothing changed between turns.
     */
    public List<String> activePromptHints() {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (ToolDispatcher.Resolved r : dispatcher.resolveAll(ctx)) {
            String hint = r.tool().promptHint();
            if (hint == null || hint.isBlank()) continue;
            seen.add(hint.strip());
        }
        return List.copyOf(seen);
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

    /**
     * Mirror of {@link #withAdditional} for the intersection direction:
     * returns a new surface whose allow-set is {@code allowed ∩ keep}.
     * Used by the script engine when a {@code @allowTools} header
     * tightens the caller's scope — a header can only restrict, never
     * widen. If this surface is unrestricted (empty allow-set), the
     * intersection becomes {@code keep} itself: the header turns an
     * unrestricted scope into a restricted one.
     *
     * <p>{@code keep == null} or empty is treated as "no narrowing"
     * and returns {@code this}.
     */
    public ContextToolsApi narrowTo(Set<String> keep) {
        if (keep == null || keep.isEmpty()) {
            return this;
        }
        Set<String> narrowedAllowed;
        if (allowed.isEmpty()) {
            // Unrestricted parent: header alone defines the bounds.
            narrowedAllowed = new LinkedHashSet<>(keep);
        } else {
            narrowedAllowed = new LinkedHashSet<>(allowed);
            narrowedAllowed.retainAll(keep);
            if (narrowedAllowed.equals(allowed)) {
                // Header was a superset of the existing allow-list —
                // nothing actually changed.
                return this;
            }
        }
        Set<String> narrowedPrimary = new LinkedHashSet<>(primary);
        narrowedPrimary.retainAll(narrowedAllowed);
        Set<String> narrowedDeferred = new LinkedHashSet<>(deferred);
        narrowedDeferred.retainAll(narrowedAllowed);
        Set<String> narrowedActivated = new LinkedHashSet<>(activatedDeferred);
        narrowedActivated.retainAll(narrowedAllowed);
        return new ContextToolsApi(
                dispatcher, ctx, narrowedAllowed, narrowedPrimary,
                narrowedDeferred, narrowedActivated, listener);
    }

    /**
     * Public view of the allow-set membership check used by
     * {@link #invoke}. Returns {@code true} for unrestricted scopes
     * ({@link #allowed} empty), or when {@code toolName} is in the
     * allow-set. Used by the script engine to validate
     * {@code @requiresTools} declarations pre-eval.
     */
    public boolean isAllowed(String toolName) {
        return isInDispatch(toolName);
    }

    private boolean isInDispatch(String toolName) {
        return allowed.isEmpty() || allowed.contains(toolName);
    }

    /**
     * What the LLM is allowed to invoke this turn — primary plus any
     * activated deferred tools. Unrestricted engines (no classification,
     * no allow-set) get a pass — the dispatcher handles validation.
     */
    private boolean isLlmVisible(String toolName) {
        // Unclassified / unrestricted: same lenient check as before the
        // primary/deferred split. Engines that opt into classification
        // (Arthur via the recipe-cascade) get strict LLM-visibility.
        if (primary.isEmpty() && deferred.isEmpty()) {
            return isInDispatch(toolName);
        }
        return primary.contains(toolName) || activatedDeferred.contains(toolName);
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
        return classify(dispatcher, ctx, base, filter, activatedDeferred, null);
    }

    /**
     * Variant with explicit {@code profile} gate (see
     * {@code engine-message-routing.md} §4.1.1). The {@code profile} is
     * matched against each tool's {@link Tool#allowedForProfile()};
     * tools whose set is non-empty and does not contain {@code profile}
     * drop out of {@code base} <i>before</i> Remove/Add/Defer overlays
     * are applied. {@code null} profile = no profile gate (legacy
     * behaviour).
     */
    public static Classification classify(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> base,
            de.mhus.vance.brain.recipe.RecipeResolver.ToolFilter filter,
            Set<String> activatedDeferred,
            @org.jspecify.annotations.Nullable String profile) {
        Set<String> remove = filter == null ? Set.of() : Set.copyOf(filter.remove());
        Set<String> add = filter == null ? Set.of() : Set.copyOf(filter.add());
        Set<String> defer = filter == null ? Set.of() : Set.copyOf(filter.defer());
        boolean filterEmpty = remove.isEmpty() && add.isEmpty() && defer.isEmpty();

        if (base == null || base.isEmpty()) {
            if (filterEmpty) {
                // No engine restriction and no per-turn overlay — caller
                // falls back to per-tool primary() via visibleResolved.
                return new Classification(Set.of(), Set.of(), Set.of(), Set.of());
            }
            // Engine doesn't restrict, but the recipe carries a filter.
            // Expand the base to every dispatchable tool so add/remove/defer
            // can operate. Without this expansion, allowedToolsAdd in a
            // Ford-style recipe would collapse to "ONLY the added tools",
            // hiding workspace_*, find_tools, describe_tool, etc.
            Set<String> all = new java.util.LinkedHashSet<>();
            for (ToolDispatcher.Resolved r : dispatcher.resolveAll(ctx)) {
                all.add(r.tool().name());
            }
            base = all;
        }

        // Profile gate (Remove pre-step): drop tools whose
        // allowedForProfile() is non-empty and does not contain `profile`.
        Set<String> profileFiltered = new LinkedHashSet<>(base);
        if (profile != null) {
            profileFiltered.removeIf(name -> {
                Set<String> allowed = dispatcher.resolve(name, ctx)
                        .map(r -> r.tool().allowedForProfile())
                        .orElse(Set.of());
                return allowed != null && !allowed.isEmpty() && !allowed.contains(profile);
            });
        }

        // Effective dispatch pool = profileFiltered − remove
        Set<String> effective = new LinkedHashSet<>(profileFiltered);
        effective.removeAll(remove);

        // Resolve each tool to consult its default deferred() flag.
        // Order: explicit allowedToolsAdd wins over allowedToolsDefer
        // — that lets a recipe say "defer @side-effect but promote
        // kit_install" by adding the one tool name to the add list.
        // Without this, a label-expansion in defer (which produces a
        // concrete tool-name set) would block any narrower promotion,
        // because there'd be no way to override a label cluster
        // selectively in YAML.
        Set<String> primary = new LinkedHashSet<>();
        Set<String> deferred = new LinkedHashSet<>();
        for (String name : effective) {
            boolean isDeferred;
            if (add.contains(name)) {
                isDeferred = false;
            } else if (defer.contains(name)) {
                isDeferred = true;
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
