package de.mhus.vance.brain.tools;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.api.toolhealth.ToolHealthStatus;
import de.mhus.vance.brain.history.HistoryTagBuilder;
import de.mhus.vance.brain.history.HistoryTagSink;
import de.mhus.vance.brain.tools.budget.ToolBudget;
import de.mhus.vance.brain.tools.budget.ToolTriage;
import de.mhus.vance.shared.toolhealth.ToolHealthDocument;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *       The LLM activates one by calling {@code tool_description(names)}.
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
 * <p><b>Result pipeline.</b> Every dispatch passes through three hooks,
 * in this order: image harvest (lifts picture content out into a
 * document — see
 * {@link de.mhus.vance.brain.ai.attachment.ToolImageHarvester}), history
 * tags, then output truncation. The order is load-bearing: harvesting
 * first keeps a screenshot from tripping the truncation threshold, and
 * tagging before truncation lets the tag builder read a documentId the
 * stub would no longer carry.
 *
 * <p>The classification (which allowed tools are primary, which are
 * deferred) and the live activation set are pre-computed by
 * {@link DefaultThinkEngineContext#tools()} via
 * {@link #classify(ToolDispatcher, ToolInvocationContext, java.util.Set,
 * de.mhus.vance.brain.recipe.RecipeResolver.ToolFilter, java.util.Set)}.
 */
public final class ContextToolsApi implements ToolBus {

    private static final Logger log = LoggerFactory.getLogger(ContextToolsApi.class);

    /**
     * Capability floor — tools that {@link #classify} forces into the
     * primary bucket for <em>every</em> engine, immune to
     * {@code allowedToolsRemove}, {@code allowedToolsDefer} and to an
     * engine {@code allowedTools()} that simply forgets them.
     *
     * <p>Rationale: these two are the escape hatch out of a slim
     * manifest. A recipe (or a new engine's allow-set) that drops them
     * doesn't produce an error — it produces a model that answers "I
     * can't do that" while the tool sits right there in the dispatcher.
     * That failure mode is invisible in logs and expensive in trust, and
     * the floor costs two small schemas (~150 tokens).
     *
     * <p><b>The floor grants discovery, not access.</b> {@code tool_list}
     * and {@code tool_description} are both scoped to
     * {@link #invocableToolNames()}, so a narrowly caged engine sees
     * exactly its own cage — the allow-set stays the authority on what
     * may be invoked.
     *
     * <p>Deliberately <em>not</em> in the floor: {@code invoke_tool}
     * (deferred tools are directly callable, so it is convenience, and
     * its presence tempts models to wrap every call), {@code how_do_i}
     * (needs a recipe + LLM credentials, may legitimately be absent) and
     * {@code manual_read} (depends on manuals existing). Extending this
     * set is a policy decision — {@code ContextToolsApiClassifyTest}
     * pins the exact membership so it can't drift in silently.
     *
     * <p>Engines may still list these names in their own
     * {@code allowedTools()} (Ford, Frankie, Trillian do): that set is
     * also read as a plain declaration — {@code RecipeResolver} derives
     * {@code (engineDefault ∪ add) ∖ remove} from it and the Magrathea
     * controller surfaces it — so dropping them there would make the
     * declared surface under-report what the engine actually gets. The
     * floor makes the declaration optional, not wrong.
     */
    public static final Set<String> MANDATORY_TOOLS =
            Set.of("tool_list", "tool_description");

    private final ToolDispatcher dispatcher;
    private final ToolInvocationContext ctx;
    private final Set<String> allowed;
    private final Set<String> primary;
    private final Set<String> deferred;
    /**
     * The subset of {@link #deferred} that the budget stage pushed there
     * this turn — everything else in {@code deferred} follows from engine
     * + recipe + mode + profile and is therefore the same on every turn.
     *
     * <p>Kept apart only for prompt rendering: the discovery block is part
     * of the static system prefix that carries the prompt-cache marker,
     * and this half is <em>not</em> stable. It moves whenever an
     * activation, a usage count or a skill re-fit changes the ranking, so
     * folding it into the static block would invalidate the whole cached
     * prefix — the very cost the alphabetical tool array is sorted to
     * avoid. See {@link #demotedDiscoveryBlockMarkdown()} and
     * {@code specification/public/server-tools.md} §14.
     */
    private final Set<String> demoted;
    private final Set<String> activatedDeferred;
    private final ToolInvocationListener listener;
    private final java.util.function.Consumer<String> activationRefresh;
    private final HistoryTagBuilder historyTagBuilder;
    private final HistoryTagSink historyTagSink;
    private final @org.jspecify.annotations.Nullable ToolResultStorage toolResultStorage;
    private final de.mhus.vance.brain.ai.attachment.@org.jspecify.annotations.Nullable
            ToolImageHarvester imageHarvester;
    private final de.mhus.vance.brain.ai.attachment.ToolAttachmentSink attachmentSink;
    /**
     * Optional — when set, {@link #primaryAsLc4j()} suffixes the
     * description of each tool that has a non-OK health entry in the
     * scope cascade. Wired by the LLM-facing engine path
     * (DefaultThinkEngineContext); sub-tool / script paths pass
     * {@code null} since they don't render manifests for an LLM.
     */
    private final @org.jspecify.annotations.Nullable ToolHealthService toolHealthService;

    /**
     * The budget this surface was classified under, carried so that
     * post-classification additions ({@link #withAdditional}) can be
     * re-fitted instead of overflowing the endpoint's cap. {@code null}
     * = no budget known; every surface behaves as it did before the
     * budget existed. Set via {@link #withBudget}.
     */
    private final @org.jspecify.annotations.Nullable BudgetContext budgetContext;

    /**
     * Limit + ranking inputs of the classification, kept together so a
     * re-fit uses exactly the same rules as the original cut.
     */
    private record BudgetContext(ToolBudget budget, ToolTriage.Hints hints) {}

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
        this(dispatcher, ctx, allowed, primary, deferred, activatedDeferred,
                listener, activationRefresh, historyTagBuilder, historyTagSink,
                toolResultStorage, null);
    }

    /**
     * 12-arg constructor — adds the optional {@link ToolHealthService}
     * so the LLM manifest can annotate {@code DOWN} / {@code DEGRADED}
     * tools with a description suffix. See spec
     * {@code specification/tool-availability.md} §9.
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
            @org.jspecify.annotations.Nullable ToolResultStorage toolResultStorage,
            @org.jspecify.annotations.Nullable ToolHealthService toolHealthService) {
        this(dispatcher, ctx, allowed, primary, deferred, activatedDeferred, listener,
                activationRefresh, historyTagBuilder, historyTagSink, toolResultStorage,
                toolHealthService, null, null);
    }

    /**
     * Full constructor — adds the image-harvest hook. Tool results that
     * carry image content have it lifted into a document here, so the
     * model sees a picture next turn instead of a base64 blob in its
     * text channel. See {@link de.mhus.vance.brain.ai.attachment.ToolImageHarvester}.
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
            @org.jspecify.annotations.Nullable ToolResultStorage toolResultStorage,
            @org.jspecify.annotations.Nullable ToolHealthService toolHealthService,
            de.mhus.vance.brain.ai.attachment.@org.jspecify.annotations.Nullable
                    ToolImageHarvester imageHarvester,
            de.mhus.vance.brain.ai.attachment.@org.jspecify.annotations.Nullable
                    ToolAttachmentSink attachmentSink) {
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
        this.toolHealthService = toolHealthService;
        this.imageHarvester = imageHarvester;
        this.attachmentSink = attachmentSink == null
                ? de.mhus.vance.brain.ai.attachment.ToolAttachmentSink.NOOP : attachmentSink;
        this.budgetContext = null;
        this.demoted = Set.of();
    }

    /**
     * Private copy-constructor used by {@link #copyWith} — same state,
     * new visibility sets, budget carried forward.
     */
    private ContextToolsApi(ContextToolsApi src,
            Set<String> allowed, Set<String> primary,
            Set<String> deferred, Set<String> activatedDeferred,
            Set<String> demoted,
            @org.jspecify.annotations.Nullable BudgetContext budgetContext) {
        this.dispatcher = src.dispatcher;
        this.ctx = src.ctx;
        this.allowed = allowed == null ? Set.of() : Set.copyOf(allowed);
        this.primary = primary == null ? Set.of() : Set.copyOf(primary);
        this.deferred = deferred == null ? Set.of() : Set.copyOf(deferred);
        this.activatedDeferred = activatedDeferred == null
                ? Set.of() : Set.copyOf(activatedDeferred);
        // Demotion only ever names tools that ended up deferred; keeping
        // the two consistent here means no renderer has to re-check it.
        Set<String> demotedCopy = demoted == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(demoted);
        demotedCopy.retainAll(this.deferred);
        this.demoted = Set.copyOf(demotedCopy);
        this.listener = src.listener;
        this.activationRefresh = src.activationRefresh;
        this.historyTagBuilder = src.historyTagBuilder;
        this.historyTagSink = src.historyTagSink;
        this.toolResultStorage = src.toolResultStorage;
        this.toolHealthService = src.toolHealthService;
        this.imageHarvester = src.imageHarvester;
        this.attachmentSink = src.attachmentSink;
        this.budgetContext = budgetContext;
    }

    /**
     * Attaches the budget this surface was classified under. Called by
     * {@code DefaultThinkEngineContext.tools()} right after
     * {@link #classify}; without it a later {@link #withAdditional} has
     * no way to know the cap it must stay under.
     */
    public ContextToolsApi withBudget(
            @org.jspecify.annotations.Nullable ToolBudget budget,
            ToolTriage.@org.jspecify.annotations.Nullable Hints familyHints) {
        return withBudget(budget, familyHints, Set.of());
    }

    /**
     * As {@link #withBudget(ToolBudget, ToolTriage.Hints)}, plus the names
     * {@link #classify} demoted while fitting the surface to the cap
     * ({@link Classification#demoted()}).
     *
     * <p>They are already inside {@code deferred} and stay callable; the
     * set is carried only so the prompt renderer can keep them out of the
     * cache-anchored half of the discovery block — see {@link #demoted}.
     */
    public ContextToolsApi withBudget(
            @org.jspecify.annotations.Nullable ToolBudget budget,
            ToolTriage.@org.jspecify.annotations.Nullable Hints familyHints,
            @org.jspecify.annotations.Nullable Set<String> demotedByBudget) {
        boolean hasDemoted = demotedByBudget != null && !demotedByBudget.isEmpty();
        if ((budget == null || !budget.hasLimit()) && !hasDemoted) return this;
        BudgetContext bc = budget == null || !budget.hasLimit() ? budgetContext
                : new BudgetContext(
                        budget, familyHints == null ? ToolTriage.Hints.EMPTY : familyHints);
        Set<String> merged = new LinkedHashSet<>(demoted);
        if (hasDemoted) merged.addAll(demotedByBudget);
        return new ContextToolsApi(
                this, allowed, primary, deferred, activatedDeferred, merged, bc);
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
     * Markdown rendering of the <b>cache-stable</b> part of
     * {@link #listDeferredForDiscovery()} for direct inclusion in the
     * engine's static system prefix. Empty string when nothing qualifies
     * (caller can skip the block entirely).
     *
     * <p>Tools the budget stage demoted this turn are deliberately
     * <em>not</em> here — they belong in
     * {@link #demotedDiscoveryBlockMarkdown()}, which the engine renders
     * as a dynamic message. Nothing disappears; it just moves out of the
     * cached prefix.
     *
     * <p>Tools are grouped by pack-prefix (the substring before
     * {@code __}) so a multi-tool pack like {@code gmail_rest} renders
     * as one section with its {@link Tool#promptHint() promptHint}
     * preamble (the per-pack usage recipe) followed by the
     * name+searchHint list. Without this grouping the LLM would see
     * the recipe in {@link #activePromptHints} far away from the tool
     * names in the discovery block and routinely fail to connect them
     * — observed: refusing to mark a Gmail message as read despite
     * {@code gmail_users_messages_modify} being right there in the
     * discovery list.
     *
     * <p>Single-tool packs (no {@code __} in the name) skip the pack
     * heading and render their hint inline. Tools without a promptHint
     * just appear as plain bullets.
     */
    public String discoveryBlockMarkdown() {
        Set<String> stable = new LinkedHashSet<>(deferred);
        stable.removeAll(demoted);
        return renderDiscoveryBlock(stable,
                "\n\n## Available deferred tools\n\n"
                        + "These tools are listed by name + hint only (full schemas "
                        + "are kept out of the manifest to save tokens). You can "
                        + "call them directly — the engine activates them on first "
                        + "use. If you need the full parameter schema first, call "
                        + "`tool_description(names=[\"<name>\", ...])` — pass every "
                        + "candidate in one call. `tool_list` shows the complete "
                        + "name inventory including anything omitted here.\n");
    }

    /**
     * The volatile half of the discovery block: the tools the budget
     * stage moved out of the manifest this turn.
     *
     * <p>Rendered separately from {@link #discoveryBlockMarkdown()} so the
     * engine can put it in its own dynamic system message. Membership
     * here follows activation recency and measured usage, so it changes
     * between turns; appending it to the cache-anchored prefix would cost
     * a full re-read of engine prompt + recipe prefix + manual hooks
     * every time the ranking shifts. Empty string when the budget cut
     * nothing — the common case.
     */
    public String demotedDiscoveryBlockMarkdown() {
        return renderDiscoveryBlock(demoted,
                "\n\n## Tools not in this turn's manifest\n\n"
                        + "These are also available, but their schemas did not fit "
                        + "the endpoint's tool limit this turn. Calling one by name "
                        + "works — the engine activates it on first use. Use "
                        + "`tool_description(names=[\"<name>\", ...])` when you need "
                        + "the parameters first.\n");
    }

    /**
     * Shared renderer for both halves of the discovery block —
     * {@code names} must be a subset of {@link #deferred}.
     */
    private String renderDiscoveryBlock(Set<String> names, String header) {
        if (names.isEmpty()) return "";
        // Pull the tools directly from the dispatcher so we have access
        // to Tool#promptHint() — ToolSpec carries searchHint +
        // description but not the pack-level recipe.
        java.util.List<ToolDispatcher.Resolved> deferredResolved = new java.util.ArrayList<>();
        for (ToolDispatcher.Resolved r : dispatcher.resolveAll(ctx)) {
            if (names.contains(r.tool().name())) deferredResolved.add(r);
        }
        if (deferredResolved.isEmpty()) return "";
        deferredResolved.sort(java.util.Comparator.comparing(r -> r.tool().name()));

        StringBuilder sb = new StringBuilder();
        sb.append(header);

        // Group by pack-prefix (substring before the first "__").
        // Insertion order = sort order = stable for prompt-cache markers.
        java.util.LinkedHashMap<String, java.util.List<ToolDispatcher.Resolved>> byPack =
                new java.util.LinkedHashMap<>();
        for (ToolDispatcher.Resolved r : deferredResolved) {
            String name = r.tool().name();
            int sep = name.indexOf("__");
            String packKey = sep > 0 ? name.substring(0, sep) : "";
            byPack.computeIfAbsent(packKey, k -> new java.util.ArrayList<>()).add(r);
        }

        for (java.util.Map.Entry<String, java.util.List<ToolDispatcher.Resolved>> e : byPack.entrySet()) {
            String packKey = e.getKey();
            java.util.List<ToolDispatcher.Resolved> packTools = e.getValue();
            if (!packKey.isEmpty()) {
                sb.append("\n### ").append(packKey).append('\n');
            } else {
                sb.append('\n');
            }
            // Pack-level promptHint (dedup by content — pack sub-tools
            // share the same hint per RestApiPackBuilder convention).
            java.util.LinkedHashSet<String> packHints = new java.util.LinkedHashSet<>();
            for (ToolDispatcher.Resolved r : packTools) {
                String h = r.tool().promptHint();
                if (h != null && !h.isBlank()) packHints.add(h.strip());
            }
            for (String h : packHints) {
                sb.append('\n').append(h).append("\n");
            }
            sb.append('\n');
            for (ToolDispatcher.Resolved r : packTools) {
                ToolSpec spec = r.tool().toSpec(r.source().sourceId());
                String hint = spec.getSearchHint() == null || spec.getSearchHint().isBlank()
                        ? spec.getDescription()
                        : spec.getSearchHint();
                sb.append("- `").append(spec.getName()).append("` — ").append(hint).append('\n');
            }
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
     *       insisting on a separate {@code tool_description} round-trip
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
     * {@link ToolBus#invokeDelegate} — a wrapper delegating to its backend.
     * Routes to {@link #invokeInternal} so the call keeps the allow-set gate
     * but does <b>not</b> auto-activate a deferred backend: the LLM asked for
     * {@code file_read}, not for {@code work_file_read}, and promoting the
     * backend into the manifest would undo the wrapper's whole purpose.
     */
    @Override
    public Map<String, Object> invokeDelegate(String name, Map<String, Object> params) {
        if (!isInDispatch(name)) {
            throw new ToolException(
                    "Tool '" + name + "' is not in this engine's dispatch pool");
        }
        // Marked as delegated so demand-measuring listeners can skip it —
        // the wrapper call was already counted, and the backend leg is a
        // mechanical consequence of it, not a second ask.
        return doInvoke(name, params, /*delegated*/ true);
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
        return doInvoke(name, params, /*delegated*/ false);
    }

    private Map<String, Object> doInvoke(
            String name, Map<String, Object> params, boolean delegated) {
        if (delegated) listener.beforeDelegate(name);
        else listener.before(name);
        long startMs = System.currentTimeMillis();
        // Resolve once up-front so the history hook can inspect the
        // tool's labels without a second resolve. Cheap (map lookup).
        Optional<ToolDispatcher.Resolved> resolved = dispatcher.resolve(name, ctx);
        try {
            Map<String, Object> result = harvestImages(
                    name, dispatcher.invoke(name, params, ctx, this));
            notifyAfter(delegated, name, System.currentTimeMillis() - startMs, null);
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
            // returned here. Tools that surface previously-stored
            // results (tool_result_read) opt out — re-truncating their
            // output would spawn a fresh stub and loop forever.
            boolean bypass = resolved
                    .map(ToolDispatcher.Resolved::tool)
                    .map(Tool::bypassOutputTruncation)
                    .orElse(false);
            return bypass ? result : maybeTruncateResult(name, result);
        } catch (RuntimeException e) {
            notifyAfter(delegated, name, System.currentTimeMillis() - startMs, e);
            emitHistoryTags(historyTagBuilder.onError(name));
            // The ERROR tag alone only says "something failed in this
            // turn". Tool results are not persisted, so without the
            // detail the failure is invisible from the next turn on —
            // and the model's own (possibly wrong) claim about the turn
            // is all that survives. See ChatMessageDocument
            // .META_TOOL_FAILURES.
            emitHistoryFailure(name, e.getMessage());
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

    /** Best-effort counterpart of {@link #emitHistoryTags} for failures. */
    private void emitHistoryFailure(String toolName, @org.jspecify.annotations.Nullable String message) {
        if (historyTagSink == HistoryTagSink.NOOP) return;
        try {
            historyTagSink.emitFailure(toolName, message);
        } catch (RuntimeException ignored) {
            // Sink errors are non-fatal — see HistoryTagSink Javadoc.
        }
    }

    /**
     * Lifts image content out of the result into a document and queues
     * the reference for the engine. Runs <em>before</em> tag extraction
     * and truncation on purpose: the rewritten result is small, so a
     * screenshot no longer trips the 32 KB stub, and the tag builder
     * still sees a documentId to work with.
     */
    private Map<String, Object> harvestImages(String toolName, Map<String, Object> result) {
        if (imageHarvester == null) return result;
        try {
            de.mhus.vance.brain.ai.attachment.ToolImageHarvester.Harvest harvest =
                    imageHarvester.harvest(
                            result, ctx.tenantId(), ctx.projectId(), toolName, ctx.userId());
            attachmentSink.emit(harvest.attachments());
            return harvest.result();
        } catch (RuntimeException e) {
            // A picture that cannot be stored must not cost the caller
            // the tool result it already has — but it must not vanish
            // silently either, or a permanently broken image path looks
            // like a model that simply never asks for screenshots.
            log.warn("Image harvest failed for tool '{}' — returning the raw result: {}",
                    toolName, e.toString());
            return result;
        }
    }

    private void notifyAfter(
            boolean delegated, String name, long elapsedMs,
            @org.jspecify.annotations.Nullable Throwable error) {
        if (delegated) listener.afterDelegate(name, elapsedMs, error);
        else listener.after(name, elapsedMs, error);
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
        Instant now = Instant.now();
        return visibleResolved().stream()
                .map(r -> ToolSpecification.builder()
                        .name(r.tool().name())
                        .description(annotateDescription(r.tool(), now))
                        .parameters(Lc4jSchema.toObjectSchema(r.tool().paramsSchema()))
                        .build())
                .toList();
    }

    /**
     * Returns {@code tool.description()} with an availability suffix when
     * the cascade has a non-OK entry for this tool. Returns the unchanged
     * description when no health service is wired (sub-tool / script
     * paths), no entry exists, or {@code expectedRecoveryAt} has already
     * passed (implicit RETESTING — next call probes naively).
     */
    String annotateDescription(Tool tool, Instant now) {
        String base = tool.description();
        if (toolHealthService == null) return base;
        Optional<ToolHealthDocument> doc;
        try {
            doc = toolHealthService.lookup(
                    ctx.tenantId(), ctx.sessionId(), ctx.userId(),
                    ctx.projectId(), tool.name());
        } catch (RuntimeException e) {
            return base;
        }
        if (doc.isEmpty()) return base;
        ToolHealthDocument h = doc.get();
        if (h.getStatus() == ToolHealthStatus.OK) return base;
        // expectedRecoveryAt in the past → status is implicitly RETESTING;
        // hide the warning so the LLM gets a clean try.
        Instant eta = h.getExpectedRecoveryAt();
        if (eta != null && !eta.isAfter(now)) return base;
        String suffix = healthSuffix(h, eta);
        if (suffix.isEmpty()) return base;
        return base + "\n\n" + suffix;
    }

    private static final DateTimeFormatter HEALTH_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

    private static String healthSuffix(ToolHealthDocument h, @org.jspecify.annotations.Nullable Instant eta) {
        String head = switch (h.getStatus()) {
            case DOWN -> "⚠ Currently unavailable";
            case DEGRADED -> "⚠ Intermittent — recent failures detected";
            case OK -> "";
        };
        if (head.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(head);
        if (eta != null) {
            sb.append(" — expected back at ").append(HEALTH_TIME_FORMAT.format(eta));
        } else if (h.getSince() != null) {
            sb.append(" — since ").append(HEALTH_TIME_FORMAT.format(h.getSince()));
        }
        sb.append('.');
        if (h.getLastNote() != null && !h.getLastNote().isBlank()) {
            sb.append(' ').append(h.getLastNote());
        }
        return sb.toString();
    }

    /** The scope this API is bound to — exposed for tools that need it. */
    public ToolInvocationContext scope() {
        return ctx;
    }

    /**
     * Deduplicated non-empty {@link Tool#promptHint() promptHints} for
     * the <em>primary</em> tools in this scope. Engines join these
     * into a single block and append them to the system message, so
     * each pack's calling conventions surface at exactly the moment
     * the LLM has the pack available.
     *
     * <p>Deferred-tool promptHints land in {@link #discoveryBlockMarkdown}
     * (per pack, adjacent to the tool name listing) instead — that
     * keeps the recipe co-located with the tool names the LLM is
     * looking at, which avoids "I see the recipe but no tool" /
     * "I see the tool but no recipe" connection failures.
     *
     * <p>Pack-level hints normally repeat across all sub-tools of one
     * pack; we dedupe by hint content so the prompt carries each
     * unique note exactly once. Order is stable across calls
     * (insertion order), which preserves cache markers when nothing
     * changed between turns.
     */
    public List<String> activePromptHints() {
        // Filter to primary + activated-deferred only — deferred-tool
        // hints land in discoveryBlockMarkdown next to the tool names
        // instead. For unrestricted engines (no classification set),
        // fall back to per-tool primary() the same way visibleResolved
        // does, so Ford-style configurations keep working.
        boolean unclassified = primary.isEmpty() && deferred.isEmpty();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (ToolDispatcher.Resolved r : dispatcher.resolveAll(ctx)) {
            String name = r.tool().name();
            boolean included = unclassified
                    ? r.tool().primary()
                    : primary.contains(name) || activatedDeferred.contains(name);
            if (!included) continue;
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

    /**
     * {@inheritDoc}
     *
     * <p>Exposes the engine dispatch allow-set — the same set
     * {@link #isInDispatch} gates on. Empty for unrestricted engines,
     * which correctly reads as "don't filter" for discovery tools.
     */
    @Override
    public Set<String> invocableToolNames() {
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
     * Returns {@code true} if {@code toolNames} is non-empty and every
     * resolvable tool inside it reports
     * {@link de.mhus.vance.toolpack.Tool#contributesPrak()} {@code ==
     * false} — i.e. the turn was purely mechanical (plan-tracking,
     * discovery, lookups) and stamping {@code META_PRAK_SKIP} on the
     * assistant message is safe.
     *
     * <p>Returns {@code false} when:
     * <ul>
     *   <li>{@code toolNames} is null/empty (no tools ran — let
     *       CheapPathFilter decide on content),</li>
     *   <li>any named tool resolves to a {@code contributesPrak()=true}
     *       tool (the turn touched real content), or</li>
     *   <li>any name fails to resolve (be conservative — let Prak run
     *       rather than miss the signal).</li>
     * </ul>
     */
    public boolean allNonPrak(@org.jspecify.annotations.Nullable Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) return false;
        for (String name : toolNames) {
            boolean contributes = dispatcher.resolve(name, ctx)
                    .map(r -> r.tool().contributesPrak())
                    .orElse(true);
            if (contributes) return false;
        }
        return true;
    }

    /**
     * Unions {@link de.mhus.vance.toolpack.Tool#prakLabels()} across
     * every resolvable tool in {@code toolNames}. Empty input or fully
     * unresolved names → empty set. Used by the engine when stamping
     * {@code META_PRAK_TOOL_LABELS} on the assistant message so Prak's
     * promotion step can attach the domain tags to every insight
     * extracted from this turn.
     */
    public Set<String> unionPrakLabels(@org.jspecify.annotations.Nullable Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String name : toolNames) {
            dispatcher.resolve(name, ctx)
                    .ifPresent(r -> out.addAll(r.tool().prakLabels()));
        }
        return out;
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
     *
     * <p><b>Budget-aware.</b> This is the one path that grows the
     * manifest <em>after</em> {@link #classify} already fitted it to the
     * endpoint's {@code tools} cap, so it re-runs the triage on the
     * merged set (see {@code planning/tool-surface-budget.md}). The
     * added tools rank as "keep" — a skill is explicitly active, so its
     * tools outrank whatever the recipe left in the manifest by default
     * — and the overflow moves to deferred. Without this the surface
     * would silently exceed the cap and the provider would answer 400,
     * which is exactly the failure the budget exists to prevent.
     *
     * <p>An extra that was <em>already</em> deferred (say the model
     * activated it earlier this session) leaves the deferred bucket on
     * the way in. Otherwise it would sit in both buckets at once: counted
     * twice by the triage, so the surface looks a slot larger than it is,
     * and rendered into the discovery block while its full schema is
     * already in the manifest.
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
        Set<String> mergedDeferred = new LinkedHashSet<>(deferred);
        Set<String> mergedActivated = new LinkedHashSet<>(activatedDeferred);
        if (mergedDeferred.removeAll(extra)) changed = true;
        mergedActivated.removeAll(extra);
        if (!changed) return this;
        if (budgetContext == null) {
            return copyWith(mergedAllowed, mergedPrimary, mergedDeferred, mergedActivated);
        }
        return refitToBudget(mergedAllowed, mergedPrimary, mergedDeferred, mergedActivated, extra);
    }

    /**
     * Re-runs {@link ToolTriage} after {@link #withAdditional} widened the
     * primary bucket. {@code priorityNames} (the freshly added tools) are
     * merged into the keep list for this run only — the stored hints are
     * not mutated, so a later re-fit starts from the same baseline.
     */
    private ContextToolsApi refitToBudget(
            Set<String> mergedAllowed, Set<String> mergedPrimary,
            Set<String> deferredIn, Set<String> activatedIn, Set<String> priorityNames) {
        BudgetContext bc = budgetContext;
        Set<String> keep = new LinkedHashSet<>(bc.hints().keep());
        keep.addAll(priorityNames);
        ToolTriage.Hints hints = new ToolTriage.Hints(
                keep, bc.hints().add(), bc.hints().dropFirst(),
                bc.hints().keepFamilies(), bc.hints().dropFirstFamilies());
        Set<String> floor = new LinkedHashSet<>(MANDATORY_TOOLS);
        floor.retainAll(mergedPrimary);
        ToolTriage.Result triaged = ToolTriage.apply(
                mergedPrimary, activatedIn, floor, hints, bc.budget());
        if (!triaged.changed()) {
            return copyWith(mergedAllowed, mergedPrimary, deferredIn, activatedIn);
        }
        Set<String> mergedDeferred = new LinkedHashSet<>(deferredIn);
        Set<String> mergedDemoted = new LinkedHashSet<>(demoted);
        for (String name : triaged.demoted()) {
            if (mergedPrimary.contains(name)) {
                mergedDeferred.add(name);
                mergedDemoted.add(name);
            }
        }
        logDemotion(ctx, triaged,
                mergedPrimary.size() + activatedDeferred.size(), bc.budget());
        return copyWith(mergedAllowed, triaged.primary(), mergedDeferred,
                triaged.activated(), mergedDemoted);
    }

    /**
     * Re-shapes the allow/primary/deferred sets while carrying every
     * hook forward. Both clone paths route through here: building the
     * copy with the short constructor dropped output-truncation, history
     * tags, tool-health and the activation-TTL refresh, so a turn with an
     * active skill (the one case that calls {@link #withAdditional})
     * silently lost all four — including the 32 KB result cap.
     */
    private ContextToolsApi copyWith(
            Set<String> newAllowed, Set<String> newPrimary,
            Set<String> newDeferred, Set<String> newActivated) {
        return copyWith(newAllowed, newPrimary, newDeferred, newActivated, demoted);
    }

    /** {@link #copyWith} with an explicit budget-demoted set. */
    private ContextToolsApi copyWith(
            Set<String> newAllowed, Set<String> newPrimary,
            Set<String> newDeferred, Set<String> newActivated, Set<String> newDemoted) {
        return new ContextToolsApi(
                this, newAllowed, newPrimary, newDeferred, newActivated,
                newDemoted, budgetContext);
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
        return copyWith(narrowedAllowed, narrowedPrimary, narrowedDeferred, narrowedActivated);
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
        return classify(dispatcher, ctx, base, filter, activatedDeferred, null, null);
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
        return classify(dispatcher, ctx, base, filter, activatedDeferred, profile, null);
    }

    /**
     * Variant with explicit {@code engineRoles} gate (see
     * {@code specification/think-engines.md} §7b). Tools whose
     * {@code requiresEngineRoles()} set is non-empty drop out of
     * {@code base} unless every required role is carried by
     * {@code engineRoles}. {@code null} or empty engineRoles disables
     * the role gate entirely — only tools without role requirements
     * survive in that case.
     */
    public static Classification classify(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> base,
            de.mhus.vance.brain.recipe.RecipeResolver.ToolFilter filter,
            Set<String> activatedDeferred,
            @org.jspecify.annotations.Nullable String profile,
            @org.jspecify.annotations.Nullable Set<String> engineRoles) {
        return classify(dispatcher, ctx, base, filter, activatedDeferred, profile, engineRoles,
                /*budget*/ null, /*familyHints*/ null);
    }

    /**
     * Variant that enforces a tool-surface budget (see
     * {@code planning/tool-surface-budget.md}). After the buckets are
     * decided, {@link ToolTriage} demotes whole tool families from
     * primary to deferred until {@code primary ∪ activated} fits the
     * endpoint's {@code tools}-array cap. Demoted tools keep their
     * discovery-block line and stay invocable — the cost of a wrong
     * demotion is one round-trip, not a lost capability.
     *
     * <p>{@code budget == null} or {@link ToolBudget#hasLimit()} false
     * reproduces the unbudgeted behaviour exactly, including the
     * "unrestricted engine" short-circuit.
     *
     * @param budget      limit + measured ranking signals for this turn
     * @param familyHints deployment-level family overrides
     *                    ({@code vance.tools.budget.*}); the per-name
     *                    hints are taken from {@code filter}
     */
    public static Classification classify(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            Set<String> base,
            de.mhus.vance.brain.recipe.RecipeResolver.ToolFilter filter,
            Set<String> activatedDeferred,
            @org.jspecify.annotations.Nullable String profile,
            @org.jspecify.annotations.Nullable Set<String> engineRoles,
            @org.jspecify.annotations.Nullable ToolBudget budget,
            ToolTriage.@org.jspecify.annotations.Nullable Hints familyHints) {
        Set<String> remove = filter == null ? Set.of() : Set.copyOf(filter.remove());
        Set<String> add = filter == null ? Set.of() : Set.copyOf(filter.add());
        Set<String> defer = filter == null ? Set.of() : Set.copyOf(filter.defer());
        boolean filterEmpty = remove.isEmpty() && add.isEmpty() && defer.isEmpty();

        if (base == null || base.isEmpty()) {
            if (filterEmpty) {
                // No engine restriction and no per-turn overlay — caller
                // falls back to per-tool primary() via visibleResolved.
                // With a budget in play the surface still has to fit, so
                // materialise the same set the fallback would produce —
                // but only when it actually overflows, so an unrestricted
                // engine under a comfortable limit keeps the cheap path.
                Classification unrestricted =
                        new Classification(Set.of(), Set.of(), Set.of(), Set.of());
                if (budget == null || !budget.hasLimit()) {
                    return unrestricted;
                }
                // filter is visibility-empty here, but may still carry
                // keep/dropFirst — those are ranking-only and have to
                // survive into the triage.
                return budgetUnrestricted(
                        dispatcher, ctx, filter, activatedDeferred, budget,
                        familyHints, unrestricted);
            }
            // Engine doesn't restrict, but the recipe carries a filter.
            // Expand the base to every dispatchable tool so add/remove/defer
            // can operate. Without this expansion, allowedToolsAdd in a
            // Ford-style recipe would collapse to "ONLY the added tools",
            // hiding workspace_*, tool_list, tool_description, etc.
            Set<String> all = new java.util.LinkedHashSet<>();
            for (ToolDispatcher.Resolved r : dispatcher.resolveAll(ctx)) {
                all.add(r.tool().name());
            }
            base = all;
        }

        // Per-turn additive allow: an `allowedToolsAdd` entry naming a
        // tool outside `base` joins the dispatch pool instead of being
        // silently dropped. Needed for label selectors over
        // client-registered packs (`@browser` → `chrome__*`): those
        // resolve only per turn (session-scoped), so the spawn-frozen
        // allowedToolsOverride cannot carry them — see
        // RecipeResolver.expandLabelSelectors.
        //
        // Names already in `base` keep the established meaning ("promote
        // to primary"); names that only arrive here keep their own
        // deferred() default, so a 29-tool MCP pack becomes reachable
        // via tool_list without flooding every turn's manifest.
        Set<String> pool = new LinkedHashSet<>(base);
        Set<String> admittedByAdd = new LinkedHashSet<>();
        for (String name : add) {
            if (pool.contains(name)) continue;
            if (dispatcher.resolve(name, ctx).isEmpty()) continue;
            pool.add(name);
            admittedByAdd.add(name);
        }

        // Engine-role gate (Remove pre-step): drop tools whose
        // requiresEngineRoles is non-empty unless every required role
        // is carried by the engine. The default-empty engineRoles set
        // intentionally hides every role-gated tool.
        Set<String> roleFiltered = new LinkedHashSet<>(pool);
        Set<String> effectiveRoles = engineRoles == null ? Set.of() : engineRoles;
        roleFiltered.removeIf(name -> {
            Set<String> required = dispatcher.resolve(name, ctx)
                    .map(r -> r.tool().requiresEngineRoles())
                    .orElse(Set.of());
            if (required == null || required.isEmpty()) return false;
            return !effectiveRoles.containsAll(required);
        });

        // Profile gate (Remove pre-step): drop tools whose
        // allowedForProfile() is non-empty and does not contain `profile`.
        Set<String> profileFiltered = new LinkedHashSet<>(roleFiltered);
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
        //
        // Exception: names in `admittedByAdd` were not part of `base` —
        // there the add list widened the allow-set rather than promoting
        // an already-allowed tool, so defer / the tool's own flag decide
        // visibility.
        Set<String> primary = new LinkedHashSet<>();
        Set<String> deferred = new LinkedHashSet<>();
        for (String name : effective) {
            boolean isDeferred;
            if (add.contains(name) && !admittedByAdd.contains(name)) {
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
        // Capability floor — see MANDATORY_TOOLS. Applied last so it
        // survives the role gate, the profile gate, allowedToolsRemove
        // and allowedToolsDefer alike.
        for (String name : MANDATORY_TOOLS) {
            if (dispatcher.resolve(name, ctx).isEmpty()) continue;
            effective.add(name);
            deferred.remove(name);
            primary.add(name);
        }

        Set<String> activated = activatedDeferred == null
                ? Set.of()
                : activatedDeferred.stream()
                        .filter(deferred::contains)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        // Budget stage — last, so it sees the final buckets and can never
        // be undone by a later overlay.
        Set<String> demotedToDeferred = Set.of();
        if (budget != null && budget.hasLimit()) {
            Set<String> floor = new LinkedHashSet<>(MANDATORY_TOOLS);
            floor.retainAll(primary);
            ToolTriage.Result triaged = ToolTriage.apply(
                    primary, activated, floor,
                    hintsFrom(filter, familyHints), budget);
            if (triaged.changed()) {
                Set<String> cut = new LinkedHashSet<>(triaged.demoted());
                cut.retainAll(primary);
                deferred.addAll(cut);
                demotedToDeferred = cut;
                logDemotion(ctx, triaged, primary.size() + activated.size(), budget);
                primary = triaged.primary();
                activated = triaged.activated();
            }
        }
        return new Classification(effective, primary, deferred, activated, demotedToDeferred);
    }

    /**
     * Budget path for an engine that doesn't restrict its tools (Ford
     * style). Materialises the implicit classification — primary = the
     * per-tool {@link Tool#primary()} flag, deferred = the rest — and
     * triages it. Returns {@code unrestricted} unchanged while the
     * surface fits, so the cheap path stays cheap and behaviour only
     * changes where it has to.
     *
     * <p>{@code filter} reaches here <em>ranking-only</em>: this path is
     * taken precisely when the recipe carries no visibility overlay, but
     * {@code allowedToolsKeep} / {@code allowedToolsDropFirst} carry no
     * visibility effect by design, so a recipe may well hold nothing but
     * those. Dropping them would silently ignore the one statement the
     * author made about what to give up first — on the engines with the
     * widest surface, which are exactly the ones the cut hits.
     */
    private static Classification budgetUnrestricted(
            ToolDispatcher dispatcher,
            ToolInvocationContext ctx,
            de.mhus.vance.brain.recipe.RecipeResolver.@org.jspecify.annotations.Nullable
                    ToolFilter filter,
            @org.jspecify.annotations.Nullable Set<String> activatedDeferred,
            ToolBudget budget,
            ToolTriage.@org.jspecify.annotations.Nullable Hints familyHints,
            Classification unrestricted) {
        Set<String> primary = new LinkedHashSet<>();
        for (ToolDispatcher.Resolved r : dispatcher.resolvePrimary(ctx)) {
            primary.add(r.tool().name());
        }
        Set<String> all = new LinkedHashSet<>();
        for (ToolDispatcher.Resolved r : dispatcher.resolveAll(ctx)) {
            all.add(r.tool().name());
        }
        Set<String> deferred = new LinkedHashSet<>(all);
        deferred.removeAll(primary);
        Set<String> activated = activatedDeferred == null
                ? Set.of()
                : activatedDeferred.stream()
                        .filter(deferred::contains)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (primary.size() + activated.size() <= budget.effectiveLimit()) {
            return unrestricted;
        }
        Set<String> floor = new LinkedHashSet<>(MANDATORY_TOOLS);
        floor.retainAll(primary);
        ToolTriage.Result triaged = ToolTriage.apply(
                primary, activated, floor,
                hintsFrom(filter, familyHints), budget);
        Set<String> demotedToDeferred = new LinkedHashSet<>(triaged.demoted());
        demotedToDeferred.retainAll(primary);
        deferred.addAll(demotedToDeferred);
        logDemotion(ctx, triaged, primary.size() + activated.size(), budget);
        return new Classification(all, triaged.primary(), deferred, triaged.activated(),
                demotedToDeferred);
    }

    /**
     * Merges the recipe's per-name priority hints with the deployment's
     * family-level overrides into one {@link ToolTriage.Hints}.
     */
    private static ToolTriage.Hints hintsFrom(
            de.mhus.vance.brain.recipe.RecipeResolver.@org.jspecify.annotations.Nullable
                    ToolFilter filter,
            ToolTriage.@org.jspecify.annotations.Nullable Hints familyHints) {
        Set<String> keep = filter == null ? Set.of() : Set.copyOf(filter.keep());
        Set<String> add = filter == null ? Set.of() : Set.copyOf(filter.add());
        Set<String> dropFirst = filter == null ? Set.of() : Set.copyOf(filter.dropFirst());
        Set<String> keepFamilies = familyHints == null ? Set.of() : familyHints.keepFamilies();
        Set<String> dropFirstFamilies =
                familyHints == null ? Set.of() : familyHints.dropFirstFamilies();
        return new ToolTriage.Hints(keep, add, dropFirst, keepFamilies, dropFirstFamilies);
    }

    /**
     * One INFO line per demotion, plus the full name list at TRACE. Loud
     * on purpose: a silently truncated manifest reads to the model like
     * "that tool does not exist", and then it tells the user exactly
     * that. Whoever debugs such a report has to be able to see in the log
     * <em>which</em> tools left the manifest and in what order they were
     * given up — the INFO line names the families, TRACE names every
     * tool.
     */
    private static void logDemotion(
            ToolInvocationContext ctx,
            ToolTriage.Result triaged,
            int surfaceBefore,
            ToolBudget budget) {
        log.info("Tool-surface budget: {} → {} schemas (maxTools={} reserved={}) "
                        + "tenant='{}' project='{}' process='{}' — demoted {} tool(s) "
                        + "in families {} to deferred; still reachable via tool_list",
                surfaceBefore, triaged.primary().size() + triaged.activated().size(),
                budget.maxTools(), budget.reserved(),
                ctx == null ? "?" : ctx.tenantId(),
                ctx == null ? "?" : ctx.projectId(),
                ctx == null ? "?" : ctx.processId(),
                triaged.demoted().size(), triaged.demotedFamilies());
        if (log.isTraceEnabled()) {
            // Demotion order = the order the families were given up, so
            // the list reads as "this went first, then this".
            log.trace("Tool-surface budget: demoted process='{}' limit={} → {}",
                    ctx == null ? "?" : ctx.processId(),
                    triaged.limit(),
                    String.join(",", triaged.demoted()));
            log.trace("Tool-surface budget: kept process='{}' primary={} activated={}",
                    ctx == null ? "?" : ctx.processId(),
                    String.join(",", triaged.primary()),
                    String.join(",", triaged.activated()));
        }
    }

    /**
     * Result of {@link #classify}. Holds the four sets the engine
     * passes into the {@link ContextToolsApi} constructor, plus
     * {@link #demoted}.
     *
     * @param demoted the subset of {@link #deferred} that the budget
     *        stage put there — already inside {@code deferred}, listed
     *        separately only so the engine can keep the volatile half of
     *        the discovery block out of its cached system prefix. Pass it
     *        on via {@link #withBudget(ToolBudget, ToolTriage.Hints, Set)}.
     */
    public record Classification(
            Set<String> allowed,
            Set<String> primary,
            Set<String> deferred,
            Set<String> activatedDeferred,
            Set<String> demoted) {

        /** Nothing was demoted — the common case outside the budget stage. */
        public Classification(Set<String> allowed, Set<String> primary,
                Set<String> deferred, Set<String> activatedDeferred) {
            this(allowed, primary, deferred, activatedDeferred, Set.of());
        }
    }
}
