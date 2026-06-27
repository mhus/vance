package de.mhus.vance.brain.tools;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolException;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.agrajag.AgrajagChecker;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ToolDispatcher {

    private final List<ToolSource> sources;
    private final PermissionService permissionService;
    private final AgrajagChecker agrajagChecker;
    private final ToolHealthService toolHealthService;

    @jakarta.annotation.PostConstruct
    public void postConstruct() {
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
            Map<String, Object> result = tools == null
                    ? r.tool().invoke(params, ctx)
                    : r.tool().invoke(params, ctx, tools);
            // Auto-clear any health entry / cooldowns this caller may have
            // triggered earlier — the tool just proved it works.
            noteSuccess(name, ctx);
            return result;
        } catch (ToolException e) {
            log.warn("Tool '{}' raised ToolException tenant='{}' project='{}' session='{}' process='{}': {}",
                    name, ctx == null ? null : ctx.tenantId(),
                    ctx == null ? null : ctx.projectId(),
                    ctx == null ? null : ctx.sessionId(),
                    ctx == null ? null : ctx.processId(),
                    e.getMessage(), e);
            triage(name, e, ctx);
            throw withHint(r.tool(), e);
        } catch (de.mhus.vance.shared.document.DocumentService.DocumentLockedException e) {
            // Soft document-lock — surface as a clean, recognizable
            // ToolException so the LLM sees a specific "document_locked"
            // signal rather than a generic "Tool failed" wrapper. The
            // structured fields (blockedRole, lockedFor) go into the
            // message so the model can decide whether to ask the user
            // or call `document_lock_remove` itself.
            String lockedFor = e.getLockedFor().stream()
                    .sorted()
                    .map(Enum::name)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            String msg = "document_locked: write blocked because the document's "
                    + "lockedFor set contains " + e.getBlockedRole()
                    + " (full set: [" + lockedFor + "]). Ask the user to unlock "
                    + "via the document properties panel, or call document_lock_remove "
                    + "if you have a clear reason.";
            log.info("Tool '{}' rejected by document lock blocked={} lockedFor={}",
                    name, e.getBlockedRole(), e.getLockedFor());
            ToolException te = new ToolException(msg, e);
            triage(name, te, ctx);
            throw withHint(r.tool(), te);
        } catch (RuntimeException e) {
            log.warn("Tool '{}' raised RuntimeException tenant='{}' project='{}' session='{}' process='{}': {}",
                    name, ctx == null ? null : ctx.tenantId(),
                    ctx == null ? null : ctx.projectId(),
                    ctx == null ? null : ctx.sessionId(),
                    ctx == null ? null : ctx.processId(),
                    e.toString(), e);
            triage(name, e, ctx);
            throw withHint(r.tool(),
                    new ToolException(
                            "Tool '" + name + "' failed: " + e.getMessage(), e));
        }
    }

    /**
     * Prepends the tool's {@link de.mhus.vance.toolpack.Tool#troubleshootingHint()}
     * to the exception message, if any. Wrapped in a fresh
     * {@link ToolException} so the original cause-chain is preserved and
     * the LLM (or any other caller reading {@code getMessage()}) sees the
     * recovery hint right at the top: "hint: <hint> -- <original>". A
     * {@code null}/blank hint is a no-op — the original exception passes
     * through verbatim.
     */
    private static ToolException withHint(
            de.mhus.vance.toolpack.Tool tool, ToolException original) {
        String hint = tool.troubleshootingHint();
        if (hint == null || hint.isBlank()) return original;
        String orig = original.getMessage() == null ? "" : original.getMessage();
        return new ToolException("hint: " + hint + " -- " + orig, original);
    }

    /**
     * Best-effort handoff to {@link AgrajagChecker} when a tool invocation
     * throws. Side-effects only (cooldown set, health-doc updated when
     * the matched rule asks for it). The original error still propagates
     * to the LLM unchanged — Agrajag's classification just influences
     * future invocations.
     */
    private void triage(String name, Throwable error, ToolInvocationContext ctx) {
        try {
            agrajagChecker.handle(name, error, ctx);
        } catch (RuntimeException secondary) {
            log.warn("AgrajagChecker raised during triage of tool='{}': {}",
                    name, secondary.toString());
        }
    }

    /** Auto-clear matching cooldowns + flip DOWN→OK on successful calls. */
    private void noteSuccess(String name, ToolInvocationContext ctx) {
        try {
            toolHealthService.noteSuccessfulCall(
                    ctx.tenantId(), ctx.sessionId(), ctx.userId(),
                    ctx.projectId(), name);
        } catch (RuntimeException secondary) {
            log.warn("ToolHealth auto-clear failed for tool='{}': {}",
                    name, secondary.toString());
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
