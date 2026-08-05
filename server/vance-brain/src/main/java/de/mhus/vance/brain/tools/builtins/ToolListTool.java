package de.mhus.vance.brain.tools.builtins;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Lists <em>every</em> tool name the caller may invoke — names only, no
 * descriptions. Tool names are self-describing by convention
 * ({@code doc_read}, {@code canvas_node_add}), so a full name list is
 * both cheap (~13 chars per name) and enough for the model to pick a
 * candidate. Schemas come from {@link ToolDescriptionTool} in a second
 * hop; {@link InvokeToolTool} (or a direct call) executes.
 *
 * <p>Two buckets in the result:
 * <ul>
 *   <li>{@code inContext} — schemas already in this turn's manifest
 *       (primary + activated deferred). Calling
 *       {@code tool_description} on these is wasted effort.</li>
 *   <li>{@code available} — everything else the engine may invoke.
 *       Directly callable (the engine auto-activates on first use);
 *       {@code tool_description} first if the parameters aren't
 *       obvious.</li>
 * </ul>
 *
 * <p>{@code packHints} carries one line per multi-tool pack (names
 * containing {@code __}, e.g. {@code jira_rest__*}). Machine-generated
 * pack names are the one case where the name alone doesn't carry intent,
 * and a per-pack hint costs one line instead of one description per
 * sub-tool — see {@link Tool#promptHint()}.
 *
 * <p>Replaced {@code find_tools} (substring search over
 * name + description) on 2026-08-05: with the whole name list visible
 * the model filters better than a server-side matcher could, and the
 * old matcher ignored {@link Tool#searchHint()} entirely.
 */
@Component
public class ToolListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "prefix", Map.of(
                            "type", "string",
                            "description",
                                    "Optional case-insensitive name prefix "
                                            + "(e.g. 'doc_', 'jira_rest__'). "
                                            + "Omit to list everything.")),
            "required", List.of());

    private final ObjectProvider<ToolDispatcher> dispatcher;

    /**
     * {@link ObjectProvider} so the dispatcher bean resolves lazily —
     * otherwise Tool → ToolDispatcher → BuiltInToolSource → Tool would
     * cycle at construction.
     */
    public ToolListTool(ObjectProvider<ToolDispatcher> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() {
        return "tool_list";
    }

    @Override
    public String description() {
        return "Lists the names of all tools you may call — 'inContext' "
                + "(schema already in your manifest) and 'available' "
                + "(callable, schema via tool_description). Optional name "
                + "prefix filter.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public boolean contributesPrak() {
        // Discovery only — no content the assistant synthesises into insight.
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
        // No bus bound → no engine allow-set to scope by and no per-turn
        // classification (foot-side / raw dispatch). List the whole
        // context-dispatchable pool and fall back on the tools' static
        // primary flag for the bucket split.
        return invoke(params, ctx, ToolBus.NOOP);
    }

    @Override
    public Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx, ToolBus bus) {
        String prefix = params == null ? null : asString(params.get("prefix"));
        // Scope to what the engine can actually invoke, so we never
        // advertise a tool whose call would hard-fail with "not available
        // to this engine" (misleads the model into wasted calls and
        // hallucinated success). Empty set → engine is unrestricted.
        Set<String> invocable = bus.invocableToolNames();
        ContextToolsApi surface = bus instanceof ContextToolsApi c ? c : null;

        List<Entry> entries = new ArrayList<>();
        for (ToolDispatcher.Resolved r : dispatcher.getObject().resolveAll(ctx)) {
            String name = r.tool().name();
            if (!invocable.isEmpty() && !invocable.contains(name)) continue;
            entries.add(new Entry(name, inContext(name, r.tool(), surface), r.tool().promptHint()));
        }
        return buildListing(entries, prefix);
    }

    /**
     * Per-turn classification wins over the tool's static default: a
     * recipe-driven ToolFilter can demote a primary tool into the
     * deferred bucket (and vice versa), and only the bound surface knows
     * which bucket the LLM is actually seeing this turn.
     */
    private static boolean inContext(
            String name, Tool tool, @Nullable ContextToolsApi surface) {
        if (surface == null) return tool.primary() && !tool.deferred();
        return surface.primary().contains(name)
                || surface.activatedDeferred().contains(name);
    }

    /** One dispatchable tool as the pure listing step sees it. */
    record Entry(String name, boolean inContext, String packHint) {}

    /**
     * Pure listing step — extracted so prefix filtering, bucket split
     * and pack-hint dedup are unit-testable without a live dispatcher.
     */
    static Map<String, Object> buildListing(List<Entry> entries, @Nullable String prefix) {
        String needle = prefix == null || prefix.isBlank()
                ? null
                : prefix.strip().toLowerCase();
        List<Entry> matching = entries.stream()
                .filter(e -> needle == null || e.name().toLowerCase().startsWith(needle))
                .sorted(Comparator.comparing(Entry::name))
                .toList();

        Set<String> inContext = new LinkedHashSet<>();
        Set<String> available = new LinkedHashSet<>();
        Map<String, String> packHints = new LinkedHashMap<>();
        for (Entry e : matching) {
            (e.inContext() ? inContext : available).add(e.name());
            int sep = e.name().indexOf("__");
            if (sep > 0 && e.packHint() != null && !e.packHint().isBlank()) {
                packHints.putIfAbsent(e.name().substring(0, sep), e.packHint().strip());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", inContext.size() + available.size());
        out.put("inContext", List.copyOf(inContext));
        out.put("available", List.copyOf(available));
        if (!packHints.isEmpty()) out.put("packHints", packHints);
        return out;
    }

    private static @Nullable String asString(@Nullable Object raw) {
        return raw instanceof String s ? s : null;
    }
}
