package de.mhus.vance.brain.tools.starred;

import de.mhus.vance.shared.starred.StarredService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Check every starred entry against its target document.
 *
 * <p>This is the one place the N lookups happen. The list itself is a
 * denormalised snapshot on purpose — resolving on every read would put a fan-out
 * over N documents in N projects on the landing page and on every "send to" menu.
 * So the check is a named action instead of a silent surcharge.
 *
 * <p>Refreshes a drifted kind or app type in place. Reports — and does not touch
 * — entries whose target is gone or no longer readable: that may be transient, and
 * dropping a curation the person made is their decision, not the agent's.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StarredReconcileTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>(),
            "required", List.of());

    private final StarredService starredService;
    private final StarredToolSupport support;

    @Override public String name() { return "starred_reconcile"; }

    @Override
    public String description() {
        return "Verify the user's starred list against the real documents: refresh entries "
                + "whose kind or app type changed, and report the ones whose target is gone "
                + "or unreadable. Nothing is deleted — removal stays the user's decision. "
                + "Use when a tile misbehaves or after documents were moved around.";
    }

    @Override public boolean primary() { return false; }

    @Override public boolean deferred() { return true; }

    @Override public Set<String> labels() {
        Set<String> labels = new java.util.HashSet<>(StarredToolSupport.BASE_LABELS);
        labels.add("write");
        return Set.copyOf(labels);
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String user = support.requireUser(ctx);
        StarredService.ReconcileResult result =
                starredService.reconcile(ctx.tenantId(), user, support.subject(ctx));

        List<Map<String, Object>> rows = result.entries().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("project", e.project());
                    m.put("path", e.path());
                    m.put("outcome", e.outcome().name().toLowerCase(Locale.ROOT));
                    m.put("message", e.message());
                    return m;
                })
                .toList();

        long broken = result.entries().stream()
                .filter(e -> e.outcome() == StarredService.ReconcileOutcome.MISSING
                        || e.outcome() == StarredService.ReconcileOutcome.FORBIDDEN)
                .count();

        log.trace("StarredReconcileTool user='{}' checked={} changed={} broken={}",
                user, rows.size(), result.changed(), broken);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", rows.size());
        out.put("changed", result.changed());
        out.put("broken", broken);
        out.put("entries", rows);
        if (broken > 0) {
            out.put("hint", "Broken entries were left in place. Offer the user "
                    + "starred_remove for the ones they no longer want.");
        }
        return out;
    }
}
