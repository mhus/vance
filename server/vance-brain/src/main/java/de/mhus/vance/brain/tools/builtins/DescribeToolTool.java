package de.mhus.vance.brain.tools.builtins;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Returns the full {@link de.mhus.vance.api.tools.ToolSpec} for a named
 * tool, including its parameter schema. Intended as the second step of
 * the find/describe/invoke discovery pattern.
 *
 * <p>Side-effect: when the resolved tool is
 * {@link de.mhus.vance.toolpack.Tool#deferred()}, the call records
 * the activation timestamp on the calling process via
 * {@link ThinkProcessService#activateDeferredTool}. From the next
 * tools()-call onward the tool ships in the LLM's primary list (until
 * decay TTL passes without a follow-up invocation).
 *
 * <p>See {@code planning/tool-schema-deferral.md} §4.4.
 */
@Component
public class DescribeToolTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Name of the tool to describe.")),
            "required", List.of("name"));

    private final ObjectProvider<ToolDispatcher> dispatcher;
    private final ThinkProcessService thinkProcessService;

    public DescribeToolTool(
            ObjectProvider<ToolDispatcher> dispatcher,
            ThinkProcessService thinkProcessService) {
        this.dispatcher = dispatcher;
        this.thinkProcessService = thinkProcessService;
    }

    @Override
    public String name() {
        return "describe_tool";
    }

    @Override
    public String description() {
        return "Returns the full specification (description + parameter schema) "
                + "for a named tool. Calling this on a deferred tool also "
                + "activates it for the rest of this session.";
    }

    @Override
    public boolean primary() {
        return true;
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
        String name = params == null ? null : (String) params.get("name");
        if (name == null || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        ToolDispatcher.Resolved r = dispatcher.getObject().resolve(name, ctx)
                .orElseThrow(() -> new ToolException("Unknown tool: " + name));
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
}
