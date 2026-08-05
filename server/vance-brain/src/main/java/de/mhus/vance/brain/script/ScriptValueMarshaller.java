package de.mhus.vance.brain.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;

/**
 * Converts a GraalJS {@link Value} (or an already host-mapped guest
 * object) into a plain, context-independent Java object graph:
 * primitives stay primitive, objects become {@link LinkedHashMap},
 * arrays become {@link ArrayList}.
 *
 * <p>Extracted from {@code GraaljsScriptExecutor} so both the script
 * return-value path and the guard scratch-store ({@code vance.guard.*}
 * writes) share one marshaller. The plain copy is essential for the
 * scratch store: a raw {@code PolyglotMap}/{@code Value} view is backed
 * by the guest context and dangles the moment {@code ctx.close()} runs,
 * so anything kept across script runs must be copied eagerly while the
 * context is still open.
 *
 * <p>Bounded against a hostile value: {@code budget} counts down one per
 * array element / object member materialised and throws
 * {@code RESOURCE_EXHAUSTED} when it goes negative; {@code depth} is
 * capped at {@code maxDepth}, which also bounds a self-referential
 * (cyclic) value that would otherwise StackOverflow.
 */
public final class ScriptValueMarshaller {

    private ScriptValueMarshaller() {}

    /**
     * Marshals a top-level {@link Value} into a plain Java object graph.
     *
     * @param maxNodes total array elements + object members allowed
     * @param maxDepth maximum nesting depth
     */
    public static @Nullable Object toPlainJava(Value value, long maxNodes, int maxDepth) {
        return mapValue(value, 0, new long[] {maxNodes}, maxDepth);
    }

    /**
     * Marshals an arbitrary host-side argument (as a Java method
     * parameter receives it from GraalJS) into a storable plain copy.
     * Handles the three shapes a guest value arrives as: a raw
     * {@link Value}, an already-mapped {@link Map}/{@link List} view
     * (both backed by the guest context — hence copied eagerly), or a
     * boxed primitive / {@code String} / {@code null} (passed through).
     */
    public static @Nullable Object toStorable(@Nullable Object raw, long maxNodes, int maxDepth) {
        return copy(raw, 0, new long[] {maxNodes}, maxDepth);
    }

    private static @Nullable Object copy(@Nullable Object raw, int depth, long[] budget, int maxDepth) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Value v) {
            return mapValue(v, depth, budget, maxDepth);
        }
        if (raw instanceof String || raw instanceof Boolean || raw instanceof Number) {
            return raw;
        }
        if (depth > maxDepth) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.RESOURCE_EXHAUSTED,
                    "value nesting exceeds maxDepth (" + maxDepth
                            + ") — cyclic or too deeply nested");
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, @Nullable Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                consumeNode(budget);
                out.put(String.valueOf(e.getKey()), copy(e.getValue(), depth + 1, budget, maxDepth));
            }
            return out;
        }
        if (raw instanceof List<?> list) {
            List<@Nullable Object> out = new ArrayList<>(list.size());
            for (Object el : list) {
                consumeNode(budget);
                out.add(copy(el, depth + 1, budget, maxDepth));
            }
            return out;
        }
        // Any other host object — store its string form rather than a
        // context-bound reference.
        return raw.toString();
    }

    @Nullable
    private static Object mapValue(Value value, int depth, long[] budget, int maxDepth) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            if (value.fitsInLong()) {
                return value.asLong();
            }
            return value.asDouble();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (depth > maxDepth) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.RESOURCE_EXHAUSTED,
                    "Script result nesting exceeds vance.script.result.maxDepth ("
                            + maxDepth + ") — cyclic or too deeply nested return value");
        }
        if (value.hasArrayElements()) {
            long size = value.getArraySize();
            List<@Nullable Object> out = new ArrayList<>((int) Math.min(size, 1024));
            for (long i = 0; i < size; i++) {
                consumeNode(budget);
                out.add(mapValue(value.getArrayElement(i), depth + 1, budget, maxDepth));
            }
            return out;
        }
        if (value.hasMembers()) {
            Map<String, @Nullable Object> out = new LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                consumeNode(budget);
                out.put(key, mapValue(value.getMember(key), depth + 1, budget, maxDepth));
            }
            return out;
        }
        return value.toString();
    }

    /** Decrement the node budget; throw once it is exhausted. */
    private static void consumeNode(long[] budget) {
        if (--budget[0] < 0) {
            throw new ScriptExecutionException(
                    ScriptExecutionException.ErrorClass.RESOURCE_EXHAUSTED,
                    "value exceeds the node cap "
                            + "(vance.script.result.max / @maxResultNodes) — too large");
        }
    }
}
