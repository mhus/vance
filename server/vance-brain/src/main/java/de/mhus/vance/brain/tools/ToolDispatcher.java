package de.mhus.vance.brain.tools;

import de.mhus.vance.api.tools.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Aggregator across all {@link ToolSource}s. Callers ask for the tool
 * set visible in a scope and dispatch invocations by name without
 * knowing which source the tool came from.
 *
 * <p>Name collisions across sources are resolved first-wins in source
 * order — {@link ServerToolSource} is registered first, so built-ins
 * cannot be shadowed by a client pushing a same-named tool.
 */
@Service
@Slf4j
public class ToolDispatcher {

    private final List<ToolSource> sources;

    public ToolDispatcher(List<ToolSource> sources) {
        this.sources = List.copyOf(sources);
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
        Resolved r = resolve(name, ctx).orElseThrow(
                () -> new ToolException("Unknown tool: " + name));
        try {
            return r.tool().invoke(params, ctx);
        } catch (ToolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ToolException(
                    "Tool '" + name + "' failed: " + e.getMessage(), e);
        }
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
