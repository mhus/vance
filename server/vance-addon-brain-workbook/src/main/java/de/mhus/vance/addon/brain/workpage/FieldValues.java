package de.mhus.vance.addon.brain.workpage;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Shape-aware access to a {@link Block.Field}'s dynamic {@code solution} /
 * {@code value} payload (see {@link Block.Field} for the per-type contract).
 * The payload itself stays the raw YAML object so fences round-trip
 * loss-free; these accessors give the {@code FieldBlockValidator} and the
 * button actions a typed view.
 */
public final class FieldValues {

    private FieldValues() {}

    /**
     * Single option index — {@code null} when {@code v} is not a number
     * (or is {@code null}).
     */
    public static @Nullable Integer asIndex(@Nullable Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    /**
     * Multiple option indices — {@code null} when {@code v} is not a list
     * of numbers (or is {@code null}).
     */
    public static @Nullable List<Integer> asIndices(@Nullable Object v) {
        if (!(v instanceof List<?> list)) return null;
        List<Integer> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Number n)) return null;
            out.add(n.intValue());
        }
        return out;
    }

    /** Whether {@code idx} points at an existing option ({@code size} options). */
    public static boolean inBounds(int idx, int size) {
        return idx >= 0 && idx < size;
    }
}
