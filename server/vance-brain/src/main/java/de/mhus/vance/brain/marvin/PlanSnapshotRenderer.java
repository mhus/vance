package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Renders the current task-tree of a Marvin process into a compact
 * text block that the engine injects into every worker LLM call.
 * The Tree IS the plan — there's no separate plan document; this
 * renderer produces an always-current view from the persistent
 * nodes.
 *
 * <p>The output is hard-capped at ~4000 chars; when the full tree
 * would exceed that, distant branches collapse to a one-liner
 * summary while the current node's path, siblings and own children
 * stay fully visible.
 *
 * <p>See {@code specification/marvin-engine.md} §5.
 */
@Component
public class PlanSnapshotRenderer {

    /** Max chars per fellow-node summary inside the snapshot. */
    static final int SUMMARY_TRUNCATE_CHARS = 200;

    /** Hard cap for the full rendered snapshot. */
    static final int MAX_SNAPSHOT_CHARS = 4000;

    /** Markers wrapping the snapshot — used by the memory rotator
     *  to detect older snapshots in chat history and replace them. */
    public static final String OPEN_MARKER =
            "┌─ LIVE PLAN (current state — supersedes any earlier view) ─";
    public static final String CLOSE_MARKER =
            "└─ end of plan ────────────────────────────────────────────";

    /**
     * Renders the snapshot.
     *
     * @param allNodes      every node of the process, any order
     * @param currentNodeId the node about to receive an LLM call;
     *                      marked {@code [YOU ARE HERE]}. May be
     *                      {@code null} when no node is in focus.
     */
    public String render(
            List<MarvinNodeDocument> allNodes,
            @Nullable String currentNodeId) {
        if (allNodes == null || allNodes.isEmpty()) {
            return wrap("(empty tree)");
        }
        MarvinNodeDocument root = null;
        Map<String, List<MarvinNodeDocument>> byParent = new LinkedHashMap<>();
        Map<String, MarvinNodeDocument> byId = new LinkedHashMap<>();
        for (MarvinNodeDocument n : allNodes) {
            byId.put(n.getId() == null ? "" : n.getId(), n);
            if (n.getParentId() == null) {
                root = n;
            } else {
                byParent.computeIfAbsent(n.getParentId(),
                        k -> new ArrayList<>()).add(n);
            }
        }
        if (root == null) return wrap("(no root node)");
        for (List<MarvinNodeDocument> kids : byParent.values()) {
            kids.sort(Comparator.comparingInt(MarvinNodeDocument::getPosition));
        }

        // First attempt: full render.
        Set<String> ancestorChain = computeAncestorChain(
                currentNodeId, byId);
        StringBuilder full = new StringBuilder();
        renderNode(root, byParent, "", "ROOT",
                currentNodeId, ancestorChain, full,
                /* abbreviateDistant */ false);
        String result = full.toString();
        if (result.length() <= MAX_SNAPSHOT_CHARS) {
            return wrap(result);
        }
        // Fallback: abbreviate distant branches.
        StringBuilder pruned = new StringBuilder();
        renderNode(root, byParent, "", "ROOT",
                currentNodeId, ancestorChain, pruned,
                /* abbreviateDistant */ true);
        String prunedStr = pruned.toString();
        if (prunedStr.length() > MAX_SNAPSHOT_CHARS) {
            // Last resort: hard truncate with notice.
            prunedStr = prunedStr.substring(0, MAX_SNAPSHOT_CHARS - 40)
                    + "\n  [snapshot truncated]";
        }
        return wrap(prunedStr);
    }

    private static String wrap(String body) {
        return OPEN_MARKER + "\n" + body
                + (body.endsWith("\n") ? "" : "\n") + CLOSE_MARKER;
    }

    private static Set<String> computeAncestorChain(
            @Nullable String currentNodeId,
            Map<String, MarvinNodeDocument> byId) {
        Set<String> chain = new HashSet<>();
        if (currentNodeId == null) return chain;
        String cur = currentNodeId;
        while (cur != null) {
            chain.add(cur);
            MarvinNodeDocument n = byId.get(cur);
            if (n == null) break;
            cur = n.getParentId();
        }
        return chain;
    }

    private static void renderNode(
            MarvinNodeDocument node,
            Map<String, List<MarvinNodeDocument>> byParent,
            String indent,
            String label,
            @Nullable String currentNodeId,
            Set<String> ancestorChain,
            StringBuilder out,
            boolean abbreviateDistant) {
        boolean isCurrent = currentNodeId != null
                && currentNodeId.equals(node.getId());
        boolean isOnPath = node.getId() != null
                && ancestorChain.contains(node.getId());
        List<MarvinNodeDocument> kids = byParent.getOrDefault(
                node.getId() == null ? "" : node.getId(), List.of());

        out.append(indent).append(label).append(" (")
                .append(formatStatus(node.getStatus())).append(") — ")
                .append(truncate(safeTitle(node), 80));
        if (isCurrent) out.append("  [YOU ARE HERE]");
        out.append('\n');

        @Nullable String summary = extractSummary(node);
        if (summary != null && !isCurrent) {
            out.append(indent).append("     └ ")
                    .append(truncate(summary, SUMMARY_TRUNCATE_CHARS))
                    .append('\n');
        }

        // Decide: render kids full, abbreviated, or skip?
        boolean renderKidsFull;
        if (kids.isEmpty()) return;
        if (!abbreviateDistant) {
            renderKidsFull = true;
        } else if (isOnPath) {
            // Ancestor chain — always full.
            renderKidsFull = true;
        } else if (isCurrent) {
            // Own children — full.
            renderKidsFull = true;
        } else {
            // Distant subtree — collapse.
            renderKidsFull = false;
        }
        if (renderKidsFull) {
            int i = 1;
            String pathPrefix = label.equals("ROOT") ? "#" : label + ".";
            String stripped = pathPrefix.startsWith("#")
                    ? pathPrefix.substring(1)
                    : pathPrefix;
            for (MarvinNodeDocument kid : kids) {
                String childLabel = label.equals("ROOT")
                        ? "#" + i
                        : "#" + stripped + i;
                renderNode(kid, byParent, indent + "  ",
                        childLabel, currentNodeId, ancestorChain,
                        out, abbreviateDistant);
                i++;
            }
        } else {
            // Abbreviated: count by status.
            int total = kids.size();
            int done = 0, failed = 0, running = 0, pending = 0;
            for (MarvinNodeDocument k : kids) {
                switch (k.getStatus()) {
                    case DONE -> done++;
                    case FAILED -> failed++;
                    case RUNNING, WAITING -> running++;
                    case PENDING -> pending++;
                    case SKIPPED -> { /* counted neither way */ }
                }
            }
            out.append(indent).append("  (+ ").append(total)
                    .append(" subtasks: ");
            List<String> parts = new ArrayList<>();
            if (done > 0)    parts.add(done + " DONE");
            if (failed > 0)  parts.add(failed + " FAILED");
            if (running > 0) parts.add(running + " in-flight");
            if (pending > 0) parts.add(pending + " planned");
            out.append(String.join(", ", parts)).append(")\n");
        }
    }

    private static String formatStatus(NodeStatus s) {
        return switch (s) {
            case PENDING -> "planned";
            case RUNNING -> "running";
            case WAITING -> "waiting";
            case DONE    -> "DONE";
            case FAILED  -> "FAILED";
            case SKIPPED -> "skipped";
        };
    }

    private static String safeTitle(MarvinNodeDocument n) {
        String goal = n.getGoal();
        if (goal == null || goal.isBlank()) {
            return n.getId() == null ? "(no title)" : n.getId();
        }
        return goal;
    }

    /**
     * Picks the most useful summary text from a node's artifacts.
     * Prefers an explicit short {@code summary}, falls back to
     * the head of {@code result} or {@code partialResult}.
     */
    private static @Nullable String extractSummary(MarvinNodeDocument n) {
        Map<String, Object> art = n.getArtifacts();
        if (art == null || art.isEmpty()) return null;
        Object summary = art.get("summary");
        if (summary instanceof String s && !s.isBlank()) return s;
        Object result = art.get("result");
        if (result instanceof String r && !r.isBlank()) return r;
        Object partial = art.get("partialResult");
        if (partial instanceof String p && !p.isBlank()) return p;
        return null;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s.replace('\n', ' ').trim();
        return s.substring(0, max - 1).replace('\n', ' ').trim() + "…";
    }
}
