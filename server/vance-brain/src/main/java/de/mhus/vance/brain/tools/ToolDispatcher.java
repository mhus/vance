package de.mhus.vance.brain.tools;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Aggregator across all {@link ToolSource}s. Callers ask for the tool
 * set visible in a scope and dispatch invocations by name without
 * knowing which source the tool came from.
 *
 * <p>Name collisions across sources are resolved first-wins in source
 * order — {@code ConfiguredToolSource} (server-side cascade) is
 * registered first, so client-pushed tools cannot shadow server-managed
 * tools that share a name.
 */
@Service
@Slf4j
public class ToolDispatcher {

    private final List<ToolSource> sources;
    private final PermissionService permissionService;

    public ToolDispatcher(List<ToolSource> sources, PermissionService permissionService) {
        this.sources = List.copyOf(sources);
        this.permissionService = permissionService;
        log.info("ToolDispatcher sources: {}",
                sources.stream().map(ToolSource::sourceId).toList());
    }

    /** All tools visible in the given scope (first-wins on name collisions). */
    public List<Resolved> resolveAll(ToolInvocationContext ctx) {
        Map<String, Resolved> byName = new LinkedHashMap<>();
        for (ToolSource src : sources) {
            for (Tool t : src.tools(ctx)) {
                byName.putIfAbsent(t.name(), new Resolved(t, src));
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** Tools marked {@code primary} — the LLM sees these up-front. */
    public List<Resolved> resolvePrimary(ToolInvocationContext ctx) {
        return resolveAll(ctx).stream().filter(r -> r.tool().primary()).toList();
    }

    /** Look up a single tool by name across all sources. */
    public Optional<Resolved> resolve(String name, ToolInvocationContext ctx) {
        for (ToolSource src : sources) {
            Optional<Tool> t = src.find(name, ctx);
            if (t.isPresent()) {
                return Optional.of(new Resolved(t.get(), src));
            }
        }
        return Optional.empty();
    }

    /**
     * Invokes a tool by name. Unknown tool → {@link ToolException} with
     * a caller-visible message. Anything else thrown by the tool is
     * wrapped so the caller always sees a {@code ToolException}.
     */
    public Map<String, Object> invoke(
            String name, Map<String, Object> params, ToolInvocationContext ctx) {
        return invoke(name, params, ctx, null);
    }

    /**
     * Variant that propagates the engine's bound {@link ContextToolsApi}
     * to tools that want to call sibling tools through the same
     * allow-filter (e.g. the script executor). Pass {@code null} when
     * no surface should be exposed.
     */
    public Map<String, Object> invoke(
            String name,
            Map<String, Object> params,
            ToolInvocationContext ctx,
            @Nullable ContextToolsApi tools) {
        Resolved r = resolve(name, ctx).orElseThrow(
                () -> new ToolException("Unknown tool: " + name));
        permissionService.enforce(securityContextOf(ctx), resourceOf(ctx), Action.EXECUTE);
        try {
            return tools == null
                    ? r.tool().invoke(params, ctx)
                    : r.tool().invoke(params, ctx, tools);
        } catch (ToolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ToolException(
                    "Tool '" + name + "' failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the {@link SecurityContext} for a tool invocation. Tool-driven
     * work happens on behalf of whoever spawned the think-process — that
     * userId is on {@link ToolInvocationContext}. Internal flows without a
     * user (e.g. lifecycle listeners) get {@link SecurityContext#SYSTEM}.
     *
     * <p>Teams stay empty here on purpose: the AllowAll resolver doesn't
     * read them, and a per-call Mongo lookup for every tool dispatch
     * would be a meaningful regression. When a real role-based resolver
     * lands, this is the place to wire team caching.
     */
    private static SecurityContext securityContextOf(ToolInvocationContext ctx) {
        if (ctx.userId() == null || ctx.userId().isBlank()) {
            return SecurityContext.SYSTEM;
        }
        return SecurityContext.user(ctx.userId(), ctx.tenantId(), List.of());
    }

    /**
     * Picks the most specific {@link Resource} known for a tool invocation.
     * The future resolver gets the deepest scope; the AllowAll default
     * just logs it.
     */
    private static Resource resourceOf(ToolInvocationContext ctx) {
        if (ctx.processId() != null && ctx.sessionId() != null && ctx.projectId() != null) {
            return new Resource.ThinkProcess(
                    ctx.tenantId(), ctx.projectId(), ctx.sessionId(), ctx.processId());
        }
        if (ctx.sessionId() != null && ctx.projectId() != null) {
            return new Resource.Session(ctx.tenantId(), ctx.projectId(), ctx.sessionId());
        }
        if (ctx.projectId() != null) {
            return new Resource.Project(ctx.tenantId(), ctx.projectId());
        }
        return new Resource.Tenant(ctx.tenantId());
    }

    /** Convenience: project resolved tools to their wire spec. */
    public static List<ToolSpec> specs(List<Resolved> resolved) {
        List<ToolSpec> out = new ArrayList<>(resolved.size());
        for (Resolved r : resolved) {
            out.add(r.tool().toSpec(r.source().sourceId()));
        }
        return out;
    }

    /** Tool plus the source it came from. */
    public record Resolved(Tool tool, ToolSource source) {}
}
