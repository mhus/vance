package de.mhus.vance.brain.history;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Translates a string scope ({@code "process"} / {@code "children"} /
 * {@code "session"}) into the concrete {@code Set<String>} of allowed
 * process ids that the {@link de.mhus.vance.shared.chat.ChatMessageService}
 * uses as its {@code thinkProcessId} {@code $in} filter. Shared between
 * {@link HistorySearchTool} and {@link HistoryRecallTool} so the two
 * tools stay consistent — a {@code search} hit must be {@code recall}-able
 * under the same scope.
 *
 * <p>Tenant boundary stays unconditional: every resolved process id
 * belongs to {@code ctx.tenantId()}; {@code findDescendantIds} and
 * {@code findBySession} both pin tenant via the underlying
 * {@code ThinkProcessRepository}.
 */
public final class HistoryScopeResolver {

    private HistoryScopeResolver() {}

    public static Set<String> resolve(
            String scope,
            ToolInvocationContext ctx,
            ThinkProcessService thinkProcessService) {
        if (ctx.processId() == null || ctx.processId().isBlank()) {
            return Set.of();
        }
        switch (scope) {
            case HistorySearchTool.SCOPE_PROCESS:
                return Set.of(ctx.processId());
            case HistorySearchTool.SCOPE_CHILDREN:
                Set<String> descendants =
                        thinkProcessService.findDescendantIds(ctx.processId());
                if (descendants.isEmpty()) {
                    // Process row vanished mid-call — fall back to self
                    // so the caller still sees its own history.
                    return Set.of(ctx.processId());
                }
                return descendants;
            case HistorySearchTool.SCOPE_SESSION:
                if (ctx.sessionId() == null || ctx.sessionId().isBlank()) {
                    // No session = no widening; caller still sees own process.
                    return Set.of(ctx.processId());
                }
                Set<String> sessionIds = new LinkedHashSet<>();
                for (ThinkProcessDocument p :
                        thinkProcessService.findBySession(ctx.tenantId(), ctx.sessionId())) {
                    if (p.getId() != null) sessionIds.add(p.getId());
                }
                // Always include caller, even if the lookup returned an
                // empty list (race: session entry removed mid-call).
                sessionIds.add(ctx.processId());
                return Set.copyOf(sessionIds);
            default:
                throw new ToolException("Unknown scope: " + scope);
        }
    }
}
