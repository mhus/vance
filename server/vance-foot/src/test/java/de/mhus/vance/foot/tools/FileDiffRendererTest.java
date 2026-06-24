package de.mhus.vance.foot.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic coverage for the diff algorithm + hunk grouper. The
 * rendering pipeline itself (JLine ANSI output) is not exercised — that
 * is a one-liner per op type and gets verified by hand in the foot REPL.
 */
class FileDiffRendererTest {

    @Test
    void splitLines_keeps_count_independent_of_trailing_newline() {
        assertThat(FileDiffRenderer.splitLines("a\nb\nc")).containsExactly("a", "b", "c");
        assertThat(FileDiffRenderer.splitLines("a\nb\nc\n")).containsExactly("a", "b", "c");
        assertThat(FileDiffRenderer.splitLines("")).isEmpty();
    }

    @Test
    void splitLines_handles_crlf() {
        assertThat(FileDiffRenderer.splitLines("a\r\nb\r\nc")).containsExactly("a", "b", "c");
    }

    @Test
    void computeDiff_identical_files_yields_only_context() {
        var ops = FileDiffRenderer.computeDiff(List.of("a", "b", "c"), List.of("a", "b", "c"));
        assertThat(ops).hasSize(3);
        assertThat(ops).allMatch(o -> o.type.name().equals("CONTEXT"));
    }

    @Test
    void computeDiff_pure_insertion_emits_only_adds() {
        var ops = FileDiffRenderer.computeDiff(List.of(), List.of("x", "y"));
        assertThat(ops).hasSize(2);
        assertThat(ops).allMatch(o -> o.type.name().equals("ADD"));
    }

    @Test
    void computeDiff_pure_deletion_emits_only_removes() {
        var ops = FileDiffRenderer.computeDiff(List.of("x", "y"), List.of());
        assertThat(ops).hasSize(2);
        assertThat(ops).allMatch(o -> o.type.name().equals("REMOVE"));
    }

    @Test
    void computeDiff_replace_one_line_emits_remove_then_add() {
        // a / b / c → a / B / c — classic single-line replace.
        var ops = FileDiffRenderer.computeDiff(List.of("a", "b", "c"), List.of("a", "B", "c"));
        // Sequence: CONTEXT(a), REMOVE(b), ADD(B), CONTEXT(c) — order of
        // REMOVE vs ADD depends on the LCS-backtrack tie-breaker; both
        // are valid as long as the change is captured.
        assertThat(ops).hasSize(4);
        assertThat(ops.get(0).type.name()).isEqualTo("CONTEXT");
        assertThat(ops.get(3).type.name()).isEqualTo("CONTEXT");
        long adds = ops.stream().filter(o -> o.type.name().equals("ADD")).count();
        long removes = ops.stream().filter(o -> o.type.name().equals("REMOVE")).count();
        assertThat(adds).isEqualTo(1);
        assertThat(removes).isEqualTo(1);
    }

    @Test
    void groupHunks_single_change_in_long_file_emits_one_hunk() {
        // 20 context lines, one removal at index 10, ctx=3 → hunk spans
        // ops [7..14) (3 lines before, the removal, 3 lines after).
        var a = new java.util.ArrayList<String>();
        for (int i = 0; i < 20; i++) a.add("line" + i);
        var b = new java.util.ArrayList<>(a);
        b.remove(10);
        var ops = FileDiffRenderer.computeDiff(a, b);
        var hunks = FileDiffRenderer.groupHunks(ops, 3);
        assertThat(hunks).hasSize(1);
        // The hunk should contain exactly one REMOVE and 6 CONTEXT lines.
        var hunk = hunks.get(0);
        long ctxCount = ops.subList(hunk.startOp, hunk.endOp).stream()
                .filter(o -> o.type.name().equals("CONTEXT")).count();
        long removeCount = ops.subList(hunk.startOp, hunk.endOp).stream()
                .filter(o -> o.type.name().equals("REMOVE")).count();
        assertThat(removeCount).isEqualTo(1);
        assertThat(ctxCount).isEqualTo(6);
    }

    @Test
    void groupHunks_two_distant_changes_emit_two_hunks() {
        var a = new java.util.ArrayList<String>();
        for (int i = 0; i < 40; i++) a.add("line" + i);
        var b = new java.util.ArrayList<>(a);
        b.set(5, "CHANGED-5");
        b.set(30, "CHANGED-30");
        var ops = FileDiffRenderer.computeDiff(a, b);
        var hunks = FileDiffRenderer.groupHunks(ops, 3);
        assertThat(hunks).hasSize(2);
    }

    @Test
    void groupHunks_two_close_changes_merge_into_one_hunk() {
        // Changes 4 lines apart with ctx=3 → context windows overlap (3+3=6 > 4),
        // so they merge into one hunk.
        var a = new java.util.ArrayList<String>();
        for (int i = 0; i < 20; i++) a.add("line" + i);
        var b = new java.util.ArrayList<>(a);
        b.set(5, "CHANGED-5");
        b.set(9, "CHANGED-9");
        var ops = FileDiffRenderer.computeDiff(a, b);
        var hunks = FileDiffRenderer.groupHunks(ops, 3);
        assertThat(hunks).hasSize(1);
    }

    @Test
    void groupHunks_no_changes_yields_no_hunks() {
        var ops = FileDiffRenderer.computeDiff(List.of("a", "b"), List.of("a", "b"));
        assertThat(FileDiffRenderer.groupHunks(ops, 3)).isEmpty();
    }
}
