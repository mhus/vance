package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of {@link ScriptValueMarshaller#toStorable} — the
 * plain-Java deep-copy path the guard scratch store relies on. The
 * {@code toPlainJava(Value, …)} path is exercised by the GraalJS
 * executor's own return-value tests; here we lock down copy isolation,
 * primitive pass-through and the resource caps.
 */
class ScriptValueMarshallerTest {

    @Test
    void toStorable_primitive_passesThrough() {
        assertThat(ScriptValueMarshaller.toStorable("hi", 100, 8)).isEqualTo("hi");
        assertThat(ScriptValueMarshaller.toStorable(Boolean.TRUE, 100, 8)).isEqualTo(true);
        assertThat(ScriptValueMarshaller.toStorable(42L, 100, 8)).isEqualTo(42L);
        assertThat(ScriptValueMarshaller.toStorable(null, 100, 8)).isNull();
    }

    @Test
    void toStorable_map_deepCopiesSoMutatingSourceDoesNotLeak() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("k", "v");
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("a", 1L);
        src.put("nested", nested);

        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) ScriptValueMarshaller.toStorable(src, 100, 8);

        // Mutating the source (and its nested map) after the copy must not
        // reach the copied graph — otherwise a context-bound value would
        // dangle in the scratch store.
        src.put("a", 999L);
        nested.put("k", "changed");

        assertThat(copy).containsEntry("a", 1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> copiedNested = (Map<String, Object>) copy.get("nested");
        assertThat(copiedNested).containsEntry("k", "v");
    }

    @Test
    void toStorable_list_deepCopies() {
        List<Object> src = new ArrayList<>(List.of("x", "y"));
        @SuppressWarnings("unchecked")
        List<Object> copy = (List<Object>) ScriptValueMarshaller.toStorable(src, 100, 8);
        src.add("z");
        assertThat(copy).containsExactly("x", "y");
    }

    @Test
    void toStorable_exceedingNodeCap_throwsResourceExhausted() {
        List<Object> big = new ArrayList<>(List.of("a", "b", "c", "d"));
        assertThatThrownBy(() -> ScriptValueMarshaller.toStorable(big, 2, 8))
                .isInstanceOf(ScriptExecutionException.class);
    }

    @Test
    void toStorable_exceedingDepth_throwsResourceExhausted() {
        Map<String, Object> l1 = new LinkedHashMap<>();
        Map<String, Object> l2 = new LinkedHashMap<>();
        Map<String, Object> l3 = new LinkedHashMap<>();
        l2.put("d", l3);
        l1.put("c", l2);
        assertThatThrownBy(() -> ScriptValueMarshaller.toStorable(l1, 100, 1))
                .isInstanceOf(ScriptExecutionException.class);
    }
}
