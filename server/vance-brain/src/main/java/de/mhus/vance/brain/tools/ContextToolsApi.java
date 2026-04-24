package de.mhus.vance.brain.tools;

import de.mhus.vance.api.tools.ToolSpec;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.List;
import java.util.Map;

/**
 * Per-call tools surface exposed to a think-engine through {@code
 * ThinkEngineContext.tools()}. Wraps the {@link ToolDispatcher} with a
 * pre-bound {@link ToolInvocationContext} so engines don't have to
 * re-build the scope on every call.
 */
public final class ContextToolsApi {

    private final ToolDispatcher dispatcher;
    private final ToolInvocationContext ctx;

    public ContextToolsApi(ToolDispatcher dispatcher, ToolInvocationContext ctx) {
        this.dispatcher = dispatcher;
        this.ctx = ctx;
    }

    /** All tools visible in this scope. */
    public List<ToolSpec> listAll() {
        return ToolDispatcher.specs(dispatcher.resolveAll(ctx));
    }

    /** Primary tools — what the LLM sees every turn. */
    public List<ToolSpec> listPrimary() {
        return ToolDispatcher.specs(dispatcher.resolvePrimary(ctx));
    }

    /** Invoke by name. Unknown tool or failure → {@link ToolException}. */
    public Map<String, Object> invoke(String name, Map<String, Object> params) {
        return dispatcher.invoke(name, params, ctx);
    }

    /**
     * Primary tools projected to langchain4j {@link ToolSpecification}s —
     * ready to drop into a {@code ChatRequest.builder().toolSpecifications(...)}.
     */
    public List<ToolSpecification> primaryAsLc4j() {
        return dispatcher.resolvePrimary(ctx).stream()
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
}
