package de.mhus.vance.brain.tools.builtins;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.budget.ToolFamily;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.toolusage.ToolUsageService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Returns description + parameter schema for one or more tools — the
 * second step of the {@code tool_list} → {@code tool_description} →
 * invoke pattern.
 *
 * <p><b>Batch by design.</b> A REST/MCP pack has 15–20 sub-tools; one
 * call per tool means 20 round-trips before the model can act, which in
 * practice ends in "I can't do that" instead of a tool call. {@code
 * names} therefore takes a list (a bare string is accepted too, so a
 * single lookup stays cheap to write) and is capped at
 * {@value #MAX_BATCH} per call — beyond that the payload is worse than
 * the round-trip it saves.
 *
 * <p>Side-effect: for every resolved tool that sits in the turn's
 * deferred bucket, the call records the activation timestamp on the
 * calling process via {@link ThinkProcessService#activateDeferredTool}.
 * From the next tools()-call onward those tools ship in the LLM's
 * primary list (until the decay TTL passes without a follow-up
 * invocation).
 *
 * <p>Replaced the single-name {@code describe_tool} on 2026-08-05.
 */
@Component
public class ToolDescriptionTool implements Tool {

    /** Upper bound on tools described per call. */
    static final int MAX_BATCH = 25;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "names", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description",
                                    "Tool names to describe. Pass all the "
                                            + "candidates you are weighing in "
                                            + "one call (e.g. every sub-tool of "
                                            + "a pack) instead of calling "
                                            + "repeatedly — max "
                                            + MAX_BATCH + " per call.")),
            "required", List.of("names"));

    private final ObjectProvider<ToolDispatcher> dispatcher;
    private final ThinkProcessService thinkProcessService;
    private final ToolUsageService toolUsageService;

    public ToolDescriptionTool(
            ObjectProvider<ToolDispatcher> dispatcher,
            ThinkProcessService thinkProcessService,
            ToolUsageService toolUsageService) {
        this.dispatcher = dispatcher;
        this.thinkProcessService = thinkProcessService;
        this.toolUsageService = toolUsageService;
    }

    @Override
    public String name() {
        return "tool_description";
    }

    @Override
    public String description() {
        return "Returns the full specification (description + parameter "
                + "schema) for the named tools — pass several names at once. "
                + "Describing a deferred tool also activates it for the rest "
                + "of this session.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public boolean contributesPrak() {
        // Tool-schema lookup — no durable insight in the result.
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        return invoke(params, ctx, ToolBus.NOOP);
    }

    @Override
    public Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx, ToolBus bus) {
        List<String> requested = parseNames(params == null ? null : params.get("names"));
        if (requested.isEmpty()) {
            throw new ToolException(
                    "'names' is required — pass one or more tool names "
                            + "(use tool_list to see what exists)");
        }
        List<String> names = requested.size() > MAX_BATCH
                ? requested.subList(0, MAX_BATCH)
                : requested;
        List<String> skipped = requested.size() > MAX_BATCH
                ? List.copyOf(requested.subList(MAX_BATCH, requested.size()))
                : List.of();

        // Same sight-line as tool_list: a name the engine cannot invoke
        // is reported as unknown rather than described. Otherwise a
        // narrowly caged worker could enumerate the whole tenant's
        // schemas, and the two discovery tools would disagree about what
        // exists — which reliably confuses the model. Empty allow-set →
        // unrestricted engine → no filter.
        Set<String> invocable = bus.invocableToolNames();

        List<Map<String, Object>> tools = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            // ContextToolsApi.MANDATORY_TOOLS stays describable even on a
            // surface built without classify (raw allow-set, sub-tool
            // paths) — "what parameters do you take?" must never fail on
            // the discovery pair itself.
            boolean visible = invocable.isEmpty()
                    || invocable.contains(name)
                    || ContextToolsApi.MANDATORY_TOOLS.contains(name);
            Optional<ToolDispatcher.Resolved> resolved = visible
                    ? dispatcher.getObject().resolve(name, ctx)
                    : Optional.empty();
            if (resolved.isEmpty()) {
                unknown.add(name);
                continue;
            }
            tools.add(describe(resolved.get(), name, ctx, bus));
        }
        if (tools.isEmpty()) {
            // Nothing resolved — surface it on the error channel rather
            // than as an empty success the model might read as "exists
            // but has no parameters".
            throw new ToolException("Unknown tool(s): " + String.join(", ", unknown));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", tools);
        if (!unknown.isEmpty()) out.put("unknown", unknown);
        if (!skipped.isEmpty()) {
            out.put("skipped", skipped);
            out.put("skippedReason",
                    "more than " + MAX_BATCH + " names per call — ask again for the rest");
        }
        return out;
    }

    /**
     * Role the discovery hit is attributed to — recipe first, engine as
     * fallback. Mirrors {@code ThinkEngineService.usageRole}; a process
     * that cannot be read lands in {@code ROLE_UNKNOWN} rather than
     * polluting a real role.
     */
    private @Nullable String usageRole(@Nullable String processId) {
        if (processId == null || processId.isBlank()) return null;
        try {
            return thinkProcessService.findById(processId)
                    .map(p -> {
                        String recipe = p.getRecipeName();
                        if (recipe != null && !recipe.isBlank()) return recipe.trim();
                        String engine = p.getThinkEngine();
                        return (engine != null && !engine.isBlank()) ? engine.trim() : null;
                    })
                    .orElse(null);
        } catch (RuntimeException e) {
            // A ranking hint is never worth failing the lookup for.
            return null;
        }
    }

    private Map<String, Object> describe(
            ToolDispatcher.Resolved r, String name, ToolInvocationContext ctx, ToolBus bus) {
        Tool tool = r.tool();
        // Engine-context deferral wins over the tool's static default:
        // a recipe-driven ToolFilter can demote a tool whose default is
        // primary into the per-turn deferred bucket (and vice versa).
        // Only the bound ContextToolsApi knows which bucket the tool
        // currently sits in for this turn — that's the bucket the LLM
        // sees, so that's the one activation must follow.
        boolean wasDeferred = bus instanceof ContextToolsApi tools
                ? tools.deferred().contains(name)
                : tool.deferred();
        boolean activated = false;
        if (wasDeferred && ctx.processId() != null && !ctx.processId().isBlank()) {
            activated = thinkProcessService.activateDeferredTool(ctx.processId(), name);
        }
        // Demand measured *before* the deferral hurdle. Counting only
        // invocations would make the tool-surface budget self-reinforcing:
        // a demoted tool is harder to reach, gets called less, and stays
        // demoted. Asking for the schema is the honest signal.
        //
        // The role comes from the process, not from the ToolInvocationContext
        // (which carries no recipe): one point-read next to a full LLM turn,
        // and without it every lookup would land in the unattributed bucket.
        toolUsageService.recordDiscovery(
                ctx.tenantId(), ctx.projectId(), usageRole(ctx.processId()),
                name, ToolFamily.of(name));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", tool.name());
        out.put("description", tool.description());
        out.put("primary", tool.primary());
        out.put("source", r.source().sourceId());
        out.put("paramsSchema", tool.paramsSchema());
        out.put("deferred", wasDeferred);
        out.put("searchHint", tool.searchHint());
        out.put("activated", activated);
        return out;
    }

    /**
     * Lenient name parsing: a JSON array is the contract, but models
     * routinely send a bare string or a comma-separated one. Accepting
     * both costs three lines and saves a wasted turn. Blanks dropped,
     * duplicates collapsed, order preserved.
     */
    static List<String> parseNames(@Nullable Object raw) {
        List<String> flat = new ArrayList<>();
        if (raw instanceof String s) {
            for (String part : s.split(",")) flat.add(part);
        } else if (raw instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o != null) flat.add(String.valueOf(o));
            }
        } else if (raw != null) {
            flat.add(String.valueOf(raw));
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : flat) {
            String trimmed = s.strip();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return List.copyOf(out);
    }
}
