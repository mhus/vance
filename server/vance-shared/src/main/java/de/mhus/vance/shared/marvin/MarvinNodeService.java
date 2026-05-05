package de.mhus.vance.shared.marvin;

import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.api.marvin.TaskKind;
import java.time.Instant;
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
 * Lifecycle and tree-traversal for {@link MarvinNodeDocument}.
 * The single entry point to Marvin's task-tree storage — analogous
 * to {@code ThinkProcessService} for processes.
 *
 * <p>Pre-order DFS traversal (see {@link #findNextActionableNode})
 * is the hot path: invoked at the top of every Marvin runTurn to
 * pick the next node to advance. Tree manipulation (append children,
 * skip, replan, mark done/failed) goes through dedicated methods
 * so callers don't poke at the document directly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarvinNodeService {

    private final MarvinNodeRepository repository;

    // ────────────────── Create ──────────────────

    /**
     * Persists the root node of a fresh Marvin tree.
     */
    public MarvinNodeDocument createRoot(
            String tenantId, String processId, String goal,
            TaskKind taskKind, @Nullable Map<String, Object> taskSpec) {
        MarvinNodeDocument root = MarvinNodeDocument.builder()
                .tenantId(tenantId)
                .processId(processId)
                .parentId(null)
                .position(0)
                .goal(goal)
                .taskKind(taskKind)
                .taskSpec(taskSpec == null ? new LinkedHashMap<>() : new LinkedHashMap<>(taskSpec))
                .status(NodeStatus.PENDING)
                .build();
        MarvinNodeDocument saved = repository.save(root);
        log.info("Marvin root created — process='{}' nodeId='{}' kind={} goal='{}'",
                processId, saved.getId(), taskKind, abbrev(goal));
        return saved;
    }

    /**
     * Appends children under {@code parentId}, assigning consecutive
     * positions starting after the highest existing-sibling position.
     * Returns the saved children in insertion order.
     */
    public List<MarvinNodeDocument> appendChildren(
            String tenantId, String processId, String parentId,
            List<NodeSpec> children) {
        if (children.isEmpty()) return List.of();
        int nextPos = nextChildPosition(processId, parentId);
        List<MarvinNodeDocument> toSave = new ArrayList<>(children.size());
        int i = nextPos;
        for (NodeSpec spec : children) {
            toSave.add(MarvinNodeDocument.builder()
                    .tenantId(tenantId)
                    .processId(processId)
                    .parentId(parentId)
                    .position(i++)
                    .goal(spec.goal())
                    .taskKind(spec.taskKind())
                    .taskSpec(spec.taskSpec() == null
                            ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(spec.taskSpec()))
                    .status(NodeStatus.PENDING)
                    .build());
        }
        List<MarvinNodeDocument> saved = repository.saveAll(toSave);
        log.info("Marvin appended {} children under parent='{}' process='{}'",
                saved.size(), parentId, processId);
        return saved;
    }

    /**
     * Inserts a single sibling after the given anchor node.
     * Used by {@code marvin_add_subtask} when a worker decides
     * something needs to be done before continuing.
     */
    public MarvinNodeDocument insertSiblingAfter(
            String tenantId, MarvinNodeDocument anchor, NodeSpec spec) {
        // Bump positions of later siblings by 1 to make room.
        List<MarvinNodeDocument> siblings = anchor.getParentId() == null
                ? repository.findByProcessIdAndParentIdIsNullOrderByPositionAsc(anchor.getProcessId())
                : repository.findByProcessIdAndParentIdOrderByPositionAsc(
                        anchor.getProcessId(), anchor.getParentId());
        int anchorPos = anchor.getPosition();
        List<MarvinNodeDocument> shifted = new ArrayList<>();
        for (MarvinNodeDocument s : siblings) {
            if (s.getPosition() > anchorPos) {
                s.setPosition(s.getPosition() + 1);
                shifted.add(s);
            }
        }
        if (!shifted.isEmpty()) repository.saveAll(shifted);
        MarvinNodeDocument inserted = MarvinNodeDocument.builder()
                .tenantId(tenantId)
                .processId(anchor.getProcessId())
                .parentId(anchor.getParentId())
                .position(anchorPos + 1)
                .goal(spec.goal())
                .taskKind(spec.taskKind())
                .taskSpec(spec.taskSpec() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(spec.taskSpec()))
                .status(NodeStatus.PENDING)
                .build();
        return repository.save(inserted);
    }

    /**
     * Inserts a single sibling immediately before the given anchor
     * node. Anchor and any later siblings shift one position right
     * so the new node sits at the anchor's old position. Used by
     * the EXPAND_FROM_DOC pause-policy (a USER_INPUT approval gate
     * appears <em>before</em> the expansion node, so the DFS hits
     * it first).
     */
    public MarvinNodeDocument insertSiblingBefore(
            String tenantId, MarvinNodeDocument anchor, NodeSpec spec) {
        List<MarvinNodeDocument> siblings = anchor.getParentId() == null
                ? repository.findByProcessIdAndParentIdIsNullOrderByPositionAsc(anchor.getProcessId())
                : repository.findByProcessIdAndParentIdOrderByPositionAsc(
                        anchor.getProcessId(), anchor.getParentId());
        int anchorPos = anchor.getPosition();
        List<MarvinNodeDocument> shifted = new ArrayList<>();
        for (MarvinNodeDocument s : siblings) {
            if (s.getPosition() >= anchorPos) {
                s.setPosition(s.getPosition() + 1);
                shifted.add(s);
            }
        }
        if (!shifted.isEmpty()) repository.saveAll(shifted);
        MarvinNodeDocument inserted = MarvinNodeDocument.builder()
                .tenantId(tenantId)
                .processId(anchor.getProcessId())
                .parentId(anchor.getParentId())
                .position(anchorPos)
                .goal(spec.goal())
                .taskKind(spec.taskKind())
                .taskSpec(spec.taskSpec() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(spec.taskSpec()))
                .status(NodeStatus.PENDING)
                .build();
        return repository.save(inserted);
    }

    // ────────────────── Read / Lookup ──────────────────

    public Optional<MarvinNodeDocument> findById(String nodeId) {
        return repository.findById(nodeId);
    }

    public List<MarvinNodeDocument> listAll(String processId) {
        return repository.findByProcessIdOrderByPositionAsc(processId);
    }

    public List<MarvinNodeDocument> findChildren(String processId, @Nullable String parentId) {
        if (parentId == null) {
            return repository.findByProcessIdAndParentIdIsNullOrderByPositionAsc(processId);
        }
        return repository.findByProcessIdAndParentIdOrderByPositionAsc(processId, parentId);
    }

    public Optional<MarvinNodeDocument> findRoot(String processId) {
        return repository.findByProcessIdAndParentIdIsNullOrderByPositionAsc(processId)
                .stream().findFirst();
    }

    public Optional<MarvinNodeDocument> findBySpawnedProcessId(String spawnedProcessId) {
        return repository.findBySpawnedProcessId(spawnedProcessId);
    }

    public Optional<MarvinNodeDocument> findByInboxItemId(String inboxItemId) {
        return repository.findByInboxItemId(inboxItemId);
    }

    // ────────────────── Traversal ──────────────────

    /**
     * Pre-order DFS — see {@code specification/marvin-engine.md} §6.
     * Visits the root first, then children in {@code position} order;
     * recurses into a child before moving to the next sibling.
     *
     * <p>Returns the first {@link NodeStatus#PENDING} node hit. Nodes
     * in {@code RUNNING}/{@code WAITING} block descent into their
     * subtree (they're waiting on an external event — Marvin must not
     * race ahead). Terminal statuses ({@code DONE}, {@code FAILED},
     * {@code SKIPPED}) are transparent: the traversal looks past them
     * for the next sibling.
     *
     * <p>{@code FAILED} is treated as transparent on purpose — the
     * recovery strategy decides what to do, not the traversal.
     */
    public Optional<MarvinNodeDocument> findNextActionableNode(String processId) {
        List<MarvinNodeDocument> all = repository.findByProcessIdOrderByPositionAsc(processId);
        if (all.isEmpty()) return Optional.empty();

        // Bucket children by parent for O(1) sibling-lookup during DFS.
        Map<String, List<MarvinNodeDocument>> childrenByParent = new LinkedHashMap<>();
        MarvinNodeDocument root = null;
        for (MarvinNodeDocument n : all) {
            if (n.getParentId() == null) {
                root = n;
            } else {
                childrenByParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
            }
        }
        if (root == null) return Optional.empty();
        for (List<MarvinNodeDocument> kids : childrenByParent.values()) {
            kids.sort((a, b) -> Integer.compare(a.getPosition(), b.getPosition()));
        }
        return dfs(root, childrenByParent).hit();
    }

    /**
     * Result of a DFS subtree walk. {@code hit} carries the actionable
     * node if found; {@code blocked} signals the subtree contains a
     * RUNNING/WAITING node that hasn't terminated yet. The two are
     * orthogonal so the parent can distinguish "subtree exhausted —
     * advance to next sibling" (both empty) from "subtree blocked —
     * may NOT advance under SEQUENTIAL siblings" (hit empty, blocked
     * true). When {@code hit} is present {@code blocked} is irrelevant.
     */
    private record WalkResult(
            Optional<MarvinNodeDocument> hit, boolean blocked) {
        private static final WalkResult EXHAUSTED =
                new WalkResult(Optional.empty(), false);
        private static final WalkResult BLOCKED =
                new WalkResult(Optional.empty(), true);
        static WalkResult hit(MarvinNodeDocument node) {
            return new WalkResult(Optional.of(node), false);
        }
    }

    private WalkResult dfs(
            MarvinNodeDocument node,
            Map<String, List<MarvinNodeDocument>> childrenByParent) {
        switch (node.getStatus()) {
            case PENDING -> {
                return WalkResult.hit(node);
            }
            case RUNNING, WAITING -> {
                // Waiting on external event — block descent.
                return WalkResult.BLOCKED;
            }
            case DONE, FAILED, SKIPPED -> {
                // Transparent — descend into children & continue siblings.
            }
        }
        List<MarvinNodeDocument> kids = childrenByParent.getOrDefault(node.getId(), List.of());
        boolean parentSequential = isSequentialParent(node);
        boolean anyKidBlocked = false;
        for (MarvinNodeDocument k : kids) {
            WalkResult r = dfs(k, childrenByParent);
            if (r.hit().isPresent()) return r;
            if (r.blocked()) {
                anyKidBlocked = true;
                if (parentSequential) {
                    // SEQUENTIAL parent: a blocked kid pins the
                    // entire sibling group — must NOT advance to
                    // the next sibling until this one's subtree
                    // reaches a terminal status.
                    return WalkResult.BLOCKED;
                }
                // PARALLEL parent: blocked kid doesn't stop the
                // scan — keep looking for a PENDING sibling.
            }
        }
        return anyKidBlocked ? WalkResult.BLOCKED : WalkResult.EXHAUSTED;
    }

    /**
     * Decides whether a node's children must be executed strictly
     * in position order (SEQUENTIAL) or may run in parallel
     * (PARALLEL). Reads the {@code executionMode} entry from the
     * node's {@code taskSpec}; if absent, falls back to the
     * task-kind default — {@code EXPAND_FROM_DOC} fans out so its
     * children default to PARALLEL, every other kind defaults to
     * SEQUENTIAL.
     */
    private static boolean isSequentialParent(MarvinNodeDocument node) {
        Map<String, Object> spec = node.getTaskSpec();
        Object override = spec == null ? null : spec.get("executionMode");
        if (override instanceof String s && !s.isBlank()) {
            return !"PARALLEL".equalsIgnoreCase(s.trim());
        }
        return node.getTaskKind() != TaskKind.EXPAND_FROM_DOC;
    }

    /**
     * @return {@code true} iff at least one node of this process is
     *         currently {@link NodeStatus#WAITING} on user input.
     *         RUNNING (a worker that's still going) is intentionally
     *         excluded — it isn't a blocking condition for the
     *         process, just normal in-flight work.
     */
    public boolean hasWaitingNodes(String processId) {
        return repository.countByProcessIdAndStatus(processId, NodeStatus.WAITING) > 0;
    }

    /**
     * @return {@code true} iff at least one node is currently in
     *         {@link NodeStatus#RUNNING}.
     */
    public boolean hasRunningNodes(String processId) {
        return repository.countByProcessIdAndStatus(processId, NodeStatus.RUNNING) > 0;
    }

    /**
     * @return {@code true} iff every node of this process is in a
     *         terminal status (DONE/FAILED/SKIPPED). Empty trees
     *         return {@code false} — there's nothing to be done yet.
     */
    public boolean isTreeTerminal(String processId) {
        long total = repository.countByProcessId(processId);
        if (total == 0) return false;
        long done = repository.countByProcessIdAndStatus(processId, NodeStatus.DONE);
        long failed = repository.countByProcessIdAndStatus(processId, NodeStatus.FAILED);
        long skipped = repository.countByProcessIdAndStatus(processId, NodeStatus.SKIPPED);
        return done + failed + skipped == total;
    }

    // ────────────────── Mutations ──────────────────

    public MarvinNodeDocument markRunning(MarvinNodeDocument node) {
        node.setStatus(NodeStatus.RUNNING);
        if (node.getStartedAt() == null) node.setStartedAt(Instant.now());
        return repository.save(node);
    }

    public MarvinNodeDocument markWaiting(MarvinNodeDocument node) {
        node.setStatus(NodeStatus.WAITING);
        if (node.getStartedAt() == null) node.setStartedAt(Instant.now());
        return repository.save(node);
    }

    public MarvinNodeDocument markDone(
            MarvinNodeDocument node, @Nullable Map<String, Object> artifacts) {
        node.setStatus(NodeStatus.DONE);
        node.setCompletedAt(Instant.now());
        if (artifacts != null) {
            node.getArtifacts().putAll(artifacts);
        }
        return repository.save(node);
    }

    public MarvinNodeDocument markFailed(MarvinNodeDocument node, String reason) {
        node.setStatus(NodeStatus.FAILED);
        node.setFailureReason(reason);
        node.setCompletedAt(Instant.now());
        return repository.save(node);
    }

    public MarvinNodeDocument markSkipped(MarvinNodeDocument node, @Nullable String reason) {
        node.setStatus(NodeStatus.SKIPPED);
        node.setCompletedAt(Instant.now());
        if (reason != null) {
            node.getArtifacts().put("skipReason", reason);
        }
        return repository.save(node);
    }

    public MarvinNodeDocument setSpawnedProcessId(MarvinNodeDocument node, String spawnedId) {
        node.setSpawnedProcessId(spawnedId);
        return repository.save(node);
    }

    public MarvinNodeDocument setInboxItemId(MarvinNodeDocument node, String itemId) {
        node.setInboxItemId(itemId);
        return repository.save(node);
    }

    public MarvinNodeDocument save(MarvinNodeDocument node) {
        return repository.save(node);
    }

    /** Wipes the full tree of a process — used on
     *  {@code marvin_replan_node} and on process-stop cleanup. */
    public void deleteTree(String processId) {
        repository.deleteByProcessId(processId);
    }

    // ────────────────── Helpers ──────────────────

    private int nextChildPosition(String processId, @Nullable String parentId) {
        List<MarvinNodeDocument> existing = parentId == null
                ? repository.findByProcessIdAndParentIdIsNullOrderByPositionAsc(processId)
                : repository.findByProcessIdAndParentIdOrderByPositionAsc(processId, parentId);
        if (existing.isEmpty()) return 0;
        return existing.get(existing.size() - 1).getPosition() + 1;
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }

    /**
     * Lightweight value carrier for new-node specs handed to
     * {@link #appendChildren(String, String, String, List)} and
     * {@link #insertSiblingAfter(String, MarvinNodeDocument, NodeSpec)}.
     */
    public record NodeSpec(
            String goal,
            TaskKind taskKind,
            @Nullable Map<String, Object> taskSpec) {}
}
