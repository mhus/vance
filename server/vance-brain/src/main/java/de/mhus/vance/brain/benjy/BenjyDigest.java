package de.mhus.vance.brain.benjy;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Renders the bounded state digest for the controller LLM calls
 * (route / evaluate / reflect). Small models get a small, structured
 * window — never the raw full history (§9a: the digest renders bounded).
 *
 * <p>Completed items collapse to a single line each so the plan shrinks
 * in the prompt while the client projection keeps the full picture.
 */
public final class BenjyDigest {

    private static final int MAX_FACTS_PER_ITEM = 6;
    private static final int MAX_FACT_CHARS = 300;

    private BenjyDigest() {}

    public static String render(BenjyState state, @Nullable String triggerNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Goal\n");
        sb.append(nonBlank(state.getInterpretedGoal(), state.getGoal()));
        sb.append("\n\n## Acceptance criteria\n");
        if (state.getCriteria().isEmpty()) {
            sb.append("(none recorded)\n");
        } else {
            for (BenjyState.Criterion c : state.getCriteria()) {
                sb.append("- [")
                        .append(c.getId())
                        .append("] ")
                        .append(c.getText())
                        .append(" (")
                        .append(c.getStatus())
                        .append(")\n");
            }
        }
        sb.append("\n## Items\n");
        if (state.getItems().isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (BenjyState.Item item : state.getItems()) {
                sb.append("- [#")
                        .append(item.getId())
                        .append("] ")
                        .append(item.getContent())
                        .append(" — ")
                        .append(item.getStatus());
                if (item.getAttempts() > 0) {
                    sb.append(" (attempt ").append(item.getAttempts() + 1).append(")");
                }
                sb.append('\n');
                int factsShown = 0;
                for (String fact : item.getFacts()) {
                    if (factsShown++ >= MAX_FACTS_PER_ITEM) {
                        sb.append("  (older facts omitted)\n");
                        break;
                    }
                    sb.append("  · ").append(truncate(fact, MAX_FACT_CHARS)).append('\n');
                }
            }
        }
        sb.append("\n## Open work (queue)\n");
        if (state.getQueue().isEmpty()) {
            sb.append("(empty)");
        } else {
            for (BenjyState.QueuedTask t : state.getQueue()) {
                sb.append("- ")
                        .append(t.getType())
                        .append(t.getItemRef() != null ? " #" + t.getItemRef() : "")
                        .append('\n');
            }
        }
        if (triggerNote != null && !triggerNote.isBlank()) {
            sb.append("\n## Why this decision is asked now\n")
                    .append(triggerNote)
                    .append('\n');
        }
        return sb.toString();
    }

    /** The per-item view used by the evaluate call — item, criteria, worker result, facts. */
    public static String renderItem(BenjyState state, BenjyState.Item item, @Nullable String workerResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Item\n[#")
                .append(item.getId())
                .append("] ")
                .append(item.getContent())
                .append("\n\n## Goal\n")
                .append(nonBlank(state.getInterpretedGoal(), state.getGoal()))
                .append("\n\n## Acceptance criteria\n");
        List<BenjyState.Criterion> criteria = state.getCriteria();
        if (criteria.isEmpty()) {
            sb.append("(none recorded)\n");
        } else {
            for (BenjyState.Criterion c : criteria) {
                sb.append("- [")
                        .append(c.getId())
                        .append("] ")
                        .append(c.getText())
                        .append('\n');
            }
        }
        sb.append("\n## Worker result\n");
        sb.append(workerResult == null || workerResult.isBlank() ? "(no reply)" : truncate(workerResult, 4000));
        sb.append("\n\n## Facts from checks and prior attempts\n");
        if (item.getFacts().isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (String fact : item.getFacts()) {
                sb.append("- ").append(truncate(fact, MAX_FACT_CHARS)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String nonBlank(String primary, @Nullable String fallback) {
        if (primary != null && !primary.isBlank()) return primary;
        return fallback == null || fallback.isBlank() ? "(none)" : fallback;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
