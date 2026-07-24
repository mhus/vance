package de.mhus.vance.toolpack.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression (code-review-2 S7): untrusted MCP/REST responses flow through this
 * hand-rolled parser. Without a nesting-depth bound a deeply nested frame
 * overflows the stack, and StackOverflowError (an Error, not a RuntimeException)
 * bypasses the dispatch guards and kills the invocation/reader thread.
 */
class PackJsonTest {

    @Test
    void deeplyNestedArrays_throwBoundedError_notStackOverflow() {
        int levels = 5000;
        String json = "[".repeat(levels) + "]".repeat(levels);

        assertThatThrownBy(() -> PackJson.read(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting exceeds max depth");
    }

    @Test
    void deeplyNestedObjects_throwBoundedError_notStackOverflow() {
        int levels = 5000;
        String json = "{\"a\":".repeat(levels) + "1" + "}".repeat(levels);

        assertThatThrownBy(() -> PackJson.read(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting exceeds max depth");
    }

    @Test
    void reasonablyNestedJson_stillParses() {
        Object parsed = PackJson.read("{\"a\":[{\"b\":[1,2,{\"c\":\"x\"}]}]}");
        assertThat(parsed).isInstanceOf(Map.class);
        Object a = ((Map<?, ?>) parsed).get("a");
        assertThat(a).isInstanceOf(List.class);
    }
}
