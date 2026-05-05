package de.mhus.vance.shared.marvin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.shared.marvin.MarvinNodeService.NodeSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for the pre-order DFS in {@link MarvinNodeService#findNextActionableNode}
 * and the related "tree terminal" / status-aggregation queries. The
 * traversal contract (spec: {@code marvin-engine.md} §6) is the hot
 * path the engine drives every turn, so worth pinning behaviour here.
 */
class MarvinNodeServiceTest {

    private MarvinNodeRepository repository;
    private MarvinNodeService service;

    @BeforeEach
    void setUp() {
        repository = mock(MarvinNodeRepository.class);
        service = new MarvinNodeService(repository);
    }

    // ─── Pre-order DFS — actionable node lookup ─────────────────────────

    @Test
    void findNextActionable_emptyTree_returnsEmpty() {
        when(repository.findByProcessIdOrderByPositionAsc("p")).thenReturn(List.of());

        assertThat(service.findNextActionableNode("p")).isEmpty();
    }

    @Test
    void findNextActionable_pendingRoot_returnsRoot() {
        MarvinNodeDocument root = node("root", null, 0, NodeStatus.PENDING);
        stubTree("p", root);

        Optional<MarvinNodeDocument> next = service.findNextActionableNode("p");

        assertThat(next).hasValue(root);
    }

    @Test
    void findNextActionable_doneRoot_descendsIntoFirstPendingChild() {
        // root(DONE) → A(DONE) → B(PENDING)
        MarvinNodeDocument root = node("root", null, 0, NodeStatus.DONE);
        MarvinNodeDocument a = node("a", "root", 0, NodeStatus.DONE);
        MarvinNodeDocument b = node("b", "root", 1, NodeStatus.PENDING);
        stubTree("p", root, a, b);

        Optional<MarvinNodeDocument> next = service.findNextActionableNode("p");

        assertThat(next).hasValue(b);
    }

    @Test
    void findNextActionable_runningNode_blocksDescent() {
        // root(DONE) → A(RUNNING) → A1(PENDING)
        // RUNNING blocks descent: A1 must NOT be visited.
        MarvinNodeDocument root = node("root", null, 0, NodeStatus.DONE);
        MarvinNodeDocument a = node("a", "root", 0, NodeStatus.RUNNING);
        MarvinNodeDocument a1 = node("a1", "a", 0, NodeStatus.PENDING);
        stubTree("p", root, a, a1);

        Optional<MarvinNodeDocument> next = service.findNextActionableNode("p");

        assertThat(next).isEmpty();
    }

    @Test
    void findNextActionable_waitingNode_blocksDescent() {
        // Same shape as RUNNING test — WAITING also blocks descent
        // (waiting on user input).
        MarvinNodeDocument root = node("root", null, 0, NodeStatus.DONE);
        MarvinNodeDocument a = node("a", "root", 0, NodeStatus.WAITING);
        MarvinNodeDocument a1 = node("a1", "a", 0, NodeStatus.PENDING);
        stubTree("p", root, a, a1);

        assertThat(service.findNextActionableNode("p")).isEmpty();
    }

    @Test
    void findNextActionable_failedNode_isTransparent_descendsAndContinues() {
        // root(DONE) → A(FAILED) → A1(PENDING)
        // FAILED is transparent — the recovery strategy decides;
        // traversal looks past it.
        MarvinNodeDocument root = node("root", null, 0, NodeStatus.DONE);
        MarvinNodeDocument a = node("a", "root", 0, NodeStatus.FAILED);
        MarvinNodeDocument a1 = node("a1", "a", 0, NodeStatus.PENDING);
        stubTree("p", root, a, a1);

        assertThat(service.findNextActionableNode("p")).hasValue(a1);
    }

    @Test
    void findNextActionable_skippedNode_isTransparent_continuesToNextSibling() {
        MarvinNodeDocument root = node("root", null, 0, NodeStatus.DONE);
        MarvinNodeDocument a = node("a", "root", 0, NodeStatus.SKIPPED);
        MarvinNodeDocument b = node("b", "root", 1, NodeStatus.PENDING);
        stubTree("p", root, a, b);

        assertThat(service.findNextActionableNode("p")).hasValue(b);
    }

    @Test
    void findNextActionable_visitsChildBeforeNextSibling_dfsOrder() {
        // root(DONE) → A(DONE) → A1(PENDING)
        //          └→ B(PENDING)
        // A1 comes before B in pre-order: child precedes the next sibling.
        MarvinNodeDocument root = node("root", null, 0, NodeStatus.DONE);
        MarvinNodeDocument a = node("a", "root", 0, NodeStatus.DONE);
        MarvinNodeDocument a1 = node("a1", "a", 0, NodeStatus.PENDING);
        MarvinNodeDocument b = node("b", "root", 1, NodeStatus.PENDING);
        stubTree("p", root, a, a1, b);

        assertThat(service.findNextActionableNode("p")).hasValue(a1);
    }

    @Test
    void findNextActionable_respectsPositionOrder_acrossSiblings() {
        // Position drives sibling order, NOT insertion order.
        MarvinNodeDocument root = node("root", null, 0, NodeStatus.DONE);
        MarvinNodeDocument later = node("b", "root", 1, NodeStatus.PENDING);
        MarvinNodeDocument earlier = node("a", "root", 0, NodeStatus.PENDING);
        // Repository returns siblings in insertion order; service must sort.
        stubTree("p", root, later, earlier);

        assertThat(service.findNextActionableNode("p")).hasValue(earlier);
    }

    @Test
    void findNextActionable_allTerminal_returnsEmpty() {
        MarvinNodeDocument root = node("root", null, 0, NodeStatus.DONE);
        MarvinNodeDocument a = node("a", "root", 0, NodeStatus.DONE);
        MarvinNodeDocument b = node("b", "root", 1, NodeStatus.SKIPPED);
        MarvinNodeDocument c = node("c", "root", 2, NodeStatus.FAILED);
        stubTree("p", root, a, b, c);

        assertThat(service.findNextActionableNode("p")).isEmpty();
    }

    @Test
    void findNextActionable_treeWithoutRoot_returnsEmpty() {
        // Defensive: no root, only orphans (shouldn't happen in practice
        // but the traversal must not throw).
        MarvinNodeDocument orphan = node("orphan", "ghost-parent", 0, NodeStatus.PENDING);
        stubTree("p", orphan);

        assertThat(service.findNextActionableNode("p")).isEmpty();
    }

    // ─── Tree-status aggregation ────────────────────────────────────────

    @Test
    void isTreeTerminal_emptyTree_returnsFalse() {
        when(repository.countByProcessId("p")).thenReturn(0L);

        assertThat(service.isTreeTerminal("p")).isFalse();
    }

    @Test
    void isTreeTerminal_allDone_returnsTrue() {
        when(repository.countByProcessId("p")).thenReturn(5L);
        when(repository.countByProcessIdAndStatus("p", NodeStatus.DONE)).thenReturn(3L);
        when(repository.countByProcessIdAndStatus("p", NodeStatus.FAILED)).thenReturn(1L);
        when(repository.countByProcessIdAndStatus("p", NodeStatus.SKIPPED)).thenReturn(1L);

        assertThat(service.isTreeTerminal("p")).isTrue();
    }

    @Test
    void isTreeTerminal_anyPendingOrRunning_returnsFalse() {
        when(repository.countByProcessId("p")).thenReturn(5L);
        when(repository.countByProcessIdAndStatus("p", NodeStatus.DONE)).thenReturn(3L);
        when(repository.countByProcessIdAndStatus("p", NodeStatus.FAILED)).thenReturn(0L);
        when(repository.countByProcessIdAndStatus("p", NodeStatus.SKIPPED)).thenReturn(1L);
        // 4 of 5 in terminal — one PENDING/RUNNING/WAITING somewhere.

        assertThat(service.isTreeTerminal("p")).isFalse();
    }

    @Test
    void hasWaitingNodes_excludesRunning() {
        when(repository.countByProcessIdAndStatus("p", NodeStatus.WAITING)).thenReturn(1L);
        assertThat(service.hasWaitingNodes("p")).isTrue();

        when(repository.countByProcessIdAndStatus("q", NodeStatus.WAITING)).thenReturn(0L);
        assertThat(service.hasWaitingNodes("q")).isFalse();
    }

    @Test
    void hasRunningNodes_isIndependentOfWaiting() {
        when(repository.countByProcessIdAndStatus("p", NodeStatus.RUNNING)).thenReturn(2L);
        assertThat(service.hasRunningNodes("p")).isTrue();
    }

    // ─── insertSiblingBefore ────────────────────────────────────────────

    @Test
    void insertSiblingBefore_anchorWithNoEarlierSiblings_shiftsAnchorRight() {
        // root is the implicit parent; anchor 'a' is the only child at pos 0.
        MarvinNodeDocument anchor = node("a", "root", 0, NodeStatus.PENDING);
        when(repository.findByProcessIdAndParentIdOrderByPositionAsc("p", "root"))
                .thenReturn(List.of(anchor));
        // Echo whatever is saved so the returned id-less node round-trips.
        when(repository.save(any(MarvinNodeDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MarvinNodeDocument inserted = service.insertSiblingBefore(
                "tenant", anchor,
                new NodeSpec("approval gate", TaskKind.USER_INPUT, Map.of()));

        // Anchor was bumped to position 1 and persisted.
        assertThat(anchor.getPosition()).isEqualTo(1);
        verify(repository).saveAll(anyList());
        // New node sits at the anchor's old position 0.
        assertThat(inserted.getPosition()).isEqualTo(0);
        assertThat(inserted.getParentId()).isEqualTo("root");
        assertThat(inserted.getTaskKind()).isEqualTo(TaskKind.USER_INPUT);
        assertThat(inserted.getGoal()).isEqualTo("approval gate");
        assertThat(inserted.getStatus()).isEqualTo(NodeStatus.PENDING);
    }

    @Test
    void insertSiblingBefore_withEarlierAndLaterSiblings_onlyShiftsFromAnchorOnward() {
        MarvinNodeDocument earlier = node("e", "root", 0, NodeStatus.DONE);
        MarvinNodeDocument anchor = node("a", "root", 1, NodeStatus.PENDING);
        MarvinNodeDocument later = node("l", "root", 2, NodeStatus.PENDING);
        when(repository.findByProcessIdAndParentIdOrderByPositionAsc("p", "root"))
                .thenReturn(List.of(earlier, anchor, later));
        when(repository.save(any(MarvinNodeDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MarvinNodeDocument inserted = service.insertSiblingBefore(
                "tenant", anchor,
                new NodeSpec("g", TaskKind.WORKER, Map.of()));

        assertThat(earlier.getPosition()).isEqualTo(0);  // unchanged
        assertThat(anchor.getPosition()).isEqualTo(2);   // bumped
        assertThat(later.getPosition()).isEqualTo(3);    // bumped
        assertThat(inserted.getPosition()).isEqualTo(1); // anchor's old slot

        // saveAll was called for the two shifted siblings only.
        ArgumentCaptor<List<MarvinNodeDocument>> shiftedCap =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(shiftedCap.capture());
        assertThat(shiftedCap.getValue()).containsExactly(anchor, later);
    }

    @Test
    void insertSiblingBefore_topLevelAnchor_usesParentIdNullLookup() {
        MarvinNodeDocument anchor = node("root", null, 0, NodeStatus.PENDING);
        when(repository.findByProcessIdAndParentIdIsNullOrderByPositionAsc("p"))
                .thenReturn(List.of(anchor));
        when(repository.save(any(MarvinNodeDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MarvinNodeDocument inserted = service.insertSiblingBefore(
                "tenant", anchor,
                new NodeSpec("g", TaskKind.WORKER, Map.of("recipe", "ford")));

        assertThat(inserted.getParentId()).isNull();
        assertThat(inserted.getPosition()).isEqualTo(0);
        assertThat(anchor.getPosition()).isEqualTo(1);
        assertThat(inserted.getTaskSpec()).containsEntry("recipe", "ford");
        // The non-null sibling lookup must NOT have been used.
        verify(repository, never())
                .findByProcessIdAndParentIdOrderByPositionAsc(any(), any());
    }

    @Test
    void insertSiblingBefore_taskSpecIsCopied_callerMutationsDontLeakIntoSavedNode() {
        MarvinNodeDocument anchor = node("a", "root", 0, NodeStatus.PENDING);
        when(repository.findByProcessIdAndParentIdOrderByPositionAsc("p", "root"))
                .thenReturn(List.of(anchor));
        when(repository.save(any(MarvinNodeDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put("recipe", "ford");

        MarvinNodeDocument inserted = service.insertSiblingBefore(
                "tenant", anchor,
                new NodeSpec("g", TaskKind.WORKER, caller));
        // Caller mutates after the call — saved spec must remain untouched.
        caller.put("recipe", "different");

        assertThat(inserted.getTaskSpec()).containsEntry("recipe", "ford");
    }

    @Test
    void insertSiblingBefore_noOtherSiblingsBesidesAnchor_savesOnlyTheNewNode() {
        MarvinNodeDocument anchor = node("solo", "root", 5, NodeStatus.PENDING);
        when(repository.findByProcessIdAndParentIdOrderByPositionAsc("p", "root"))
                .thenReturn(List.of(anchor));
        when(repository.save(any(MarvinNodeDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MarvinNodeDocument inserted = service.insertSiblingBefore(
                "tenant", anchor,
                new NodeSpec("g", TaskKind.WORKER, Map.of()));

        // Anchor was the only sibling; it gets shifted to position 6 and
        // saveAll is called once with that single shifted sibling.
        assertThat(anchor.getPosition()).isEqualTo(6);
        assertThat(inserted.getPosition()).isEqualTo(5);
        verify(repository, times(1)).saveAll(anyList());
        // Plus exactly one save() for the newly inserted node.
        verify(repository, times(1)).save(any(MarvinNodeDocument.class));
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private void stubTree(String processId, MarvinNodeDocument... nodes) {
        when(repository.findByProcessIdOrderByPositionAsc(processId))
                .thenReturn(List.of(nodes));
    }

    private static MarvinNodeDocument node(
            String id, String parentId, int position, NodeStatus status) {
        return MarvinNodeDocument.builder()
                .id(id)
                .processId("p")
                .parentId(parentId)
                .position(position)
                .goal("test goal " + id)
                .taskKind(TaskKind.WORKER)
                .status(status)
                .build();
    }
}
