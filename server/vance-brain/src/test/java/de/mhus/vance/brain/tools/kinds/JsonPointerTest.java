package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * RFC-6901 pointer navigation for the {@code data_*} mutation tools. An
 * off-by-one or a botched escape here silently writes to the wrong node of a
 * user document — so parse/resolve/set/remove are pinned exhaustively,
 * including the escape rules ({@code ~0}/{@code ~1}), array append ({@code -}),
 * and the out-of-range / non-container failure modes.
 */
class JsonPointerTest {

    // ──────────────── parse ────────────────

    @Test
    void parse_rootForms_yieldEmptyOrSingleEmptyToken() {
        assertThat(JsonPointer.parse(null)).isEmpty();
        assertThat(JsonPointer.parse("")).isEmpty();
        assertThat(JsonPointer.parse("/")).containsExactly("");
    }

    @Test
    void parse_splitsSegmentsAndUnescapes() {
        assertThat(JsonPointer.parse("/a/b")).containsExactly("a", "b");
        assertThat(JsonPointer.parse("/a~1b")).containsExactly("a/b");   // ~1 -> /
        assertThat(JsonPointer.parse("/a~0b")).containsExactly("a~b");   // ~0 -> ~
        assertThat(JsonPointer.parse("/x//y")).containsExactly("x", "", "y");
    }

    @Test
    void parse_withoutLeadingSlash_throws() {
        assertThatThrownBy(() -> JsonPointer.parse("no-slash"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("must start with '/'");
    }

    // ──────────────── resolve ────────────────

    @Test
    void resolve_navigatesMapsAndLists() {
        Object root = map("a", map("b", 42), "list", list(10, 20, 30));
        assertThat(JsonPointer.resolve(root, new String[] {"a", "b"})).isEqualTo(42);
        assertThat(JsonPointer.resolve(root, new String[] {"list", "1"})).isEqualTo(20);
        assertThat(JsonPointer.resolve(root, new String[0])).isSameAs(root);
    }

    @Test
    void resolve_missingOrOutOfRangeOrNonContainer_returnsNull() {
        Object root = map("a", 5, "list", list(1, 2));
        assertThat(JsonPointer.resolve(root, new String[] {"missing"})).isNull();
        assertThat(JsonPointer.resolve(root, new String[] {"list", "9"})).isNull();
        assertThat(JsonPointer.resolve(root, new String[] {"list", "-1"})).isNull();
        assertThat(JsonPointer.resolve(root, new String[] {"a", "b"})).isNull(); // 5 is not a container
        assertThat(JsonPointer.resolve(null, new String[] {"a"})).isNull();
    }

    @Test
    void resolve_nonNumericListIndex_throws() {
        Object root = map("list", list(1, 2));
        assertThatThrownBy(() -> JsonPointer.resolve(root, new String[] {"list", "x"}))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Expected array index");
    }

    // ──────────────── set ────────────────

    @Test
    void set_intoMap_returnsPreviousValue() {
        Map<String, Object> root = map("a", 1);
        Object prev = JsonPointer.set(root, new String[] {"a"}, 2, r -> {});
        assertThat(prev).isEqualTo(1);
        assertThat(root).containsEntry("a", 2);
    }

    @Test
    void set_autoCreatesNestedMaps() {
        Map<String, Object> root = new LinkedHashMap<>();
        JsonPointer.set(root, new String[] {"a", "b", "c"}, "deep", r -> {});
        assertThat(JsonPointer.resolve(root, new String[] {"a", "b", "c"})).isEqualTo("deep");
    }

    @Test
    void set_intoList_replaceAppendAndDash() {
        Map<String, Object> root = map("l", list(1, 2, 3));

        assertThat(JsonPointer.set(root, new String[] {"l", "1"}, 9, r -> {})).isEqualTo(2);
        assertThat(JsonPointer.resolve(root, new String[] {"l", "1"})).isEqualTo(9);

        // index == size appends
        assertThat(JsonPointer.set(root, new String[] {"l", "3"}, 4, r -> {})).isNull();
        // "-" appends
        assertThat(JsonPointer.set(root, new String[] {"l", "-"}, 5, r -> {})).isNull();

        assertThat(asList(JsonPointer.resolve(root, new String[] {"l"}))).containsExactly(1, 9, 3, 4, 5);
    }

    @Test
    void set_listIndexBeyondSize_throws() {
        Map<String, Object> root = map("l", list(1, 2));
        assertThatThrownBy(() -> JsonPointer.set(root, new String[] {"l", "5"}, 9, r -> {}))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void set_emptyPath_replacesRootViaSetter() {
        Map<String, Object> root = map("a", 1);
        Object[] captured = new Object[1];
        Object prev = JsonPointer.set(root, new String[0], "newRoot", v -> captured[0] = v);
        assertThat(captured[0]).isEqualTo("newRoot");
        assertThat(prev).isSameAs(root);
    }

    @Test
    void set_parentNeitherMapNorList_throws() {
        Map<String, Object> root = map("a", 5);
        assertThatThrownBy(() -> JsonPointer.set(root, new String[] {"a", "b"}, 1, r -> {}))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("neither object nor array");
    }

    // ──────────────── remove ────────────────

    @Test
    void remove_mapKey_returnsRemovedValue() {
        Map<String, Object> root = map("a", 1, "b", 2);
        assertThat(JsonPointer.remove(root, new String[] {"a"})).isEqualTo(1);
        assertThat(root).doesNotContainKey("a").containsEntry("b", 2);
    }

    @Test
    void remove_absentKeyOrOutOfRange_returnsNull() {
        Map<String, Object> root = map("l", list(1, 2));
        assertThat(JsonPointer.remove(root, new String[] {"missing"})).isNull();
        assertThat(JsonPointer.remove(root, new String[] {"l", "9"})).isNull();
    }

    @Test
    void remove_listIndex_shrinksList() {
        Map<String, Object> root = map("l", list(10, 20, 30));
        assertThat(JsonPointer.remove(root, new String[] {"l", "1"})).isEqualTo(20);
        assertThat(asList(JsonPointer.resolve(root, new String[] {"l"}))).containsExactly(10, 30);
    }

    @Test
    void remove_emptyPath_isNoOp() {
        Map<String, Object> root = map("a", 1);
        assertThat(JsonPointer.remove(root, new String[0])).isNull();
        assertThat(root).containsEntry("a", 1);
    }

    // ──────────────── format + round-trip ────────────────

    @Test
    void format_escapesAndRoundTrips() {
        assertThat(JsonPointer.format(new String[0])).isEmpty();
        assertThat(JsonPointer.format(new String[] {"a", "b"})).isEqualTo("/a/b");
        assertThat(JsonPointer.format(new String[] {"a/b", "c~d"})).isEqualTo("/a~1b/c~0d");

        String[] tokens = {"a/b", "c~d", "plain"};
        assertThat(JsonPointer.parse(JsonPointer.format(tokens))).containsExactly(tokens);
    }

    // ──────────────── mutableCopy ────────────────

    @Test
    void mutableCopy_makesImmutableParsedBodyWritable() {
        Object immutable = Map.of("l", List.of(1, 2));
        Object copy = JsonPointer.mutableCopy(immutable);

        // A copy of an immutable body must accept set/remove without throwing.
        JsonPointer.set(copy, new String[] {"l", "-"}, 3, r -> {});
        assertThat(asList(JsonPointer.resolve(copy, new String[] {"l"}))).containsExactly(1, 2, 3);
    }

    @Test
    void mutableCopy_dropsNonStringKeys() {
        Map<Object, Object> mixed = new LinkedHashMap<>();
        mixed.put("ok", 1);
        mixed.put(42, "dropped");
        Object copy = JsonPointer.mutableCopy(mixed);
        assertThat(asMap(copy)).containsOnlyKeys("ok");
    }

    // ──────────────── helpers ────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<>();
        for (Object o : items) l.add(o);
        return l;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
