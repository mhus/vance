package de.mhus.vance.toolpack.mcp;

import de.mhus.vance.toolpack.core.PackJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Coerces LLM-produced tool arguments to the primitive types an MCP
 * server's {@code inputSchema} declares.
 *
 * <p>Vance's own tools each tolerate a stringly-typed argument locally
 * ({@code v instanceof String s ? Boolean.parseBoolean(s) : …}). MCP
 * servers do not: they validate the {@code tools/call} payload strictly
 * — chrome-devtools-mcp answers {@code includeSnapshot: "true"} with
 * {@code MCP error -32602: expected boolean, received string}. Models
 * that stringify scalars then burn turns retrying the identical call
 * before dropping the parameter. This class closes that gap once, on
 * the only path from a {@link de.mhus.vance.toolpack.Tool} invocation
 * to the wire.
 *
 * <p>Coercions applied (driven by the declared {@code type}):
 * <ul>
 *   <li>{@code boolean} ← {@code "true"} / {@code "false"} (trimmed,
 *       case-insensitive)</li>
 *   <li>{@code integer} ← integral numeric string, or a floating-point
 *       number with no fractional part ({@code 3.0} → {@code 3})</li>
 *   <li>{@code number} ← numeric string</li>
 *   <li>{@code string} ← number / boolean</li>
 *   <li>{@code object} / {@code array} ← JSON text holding that shape</li>
 * </ul>
 * Nested {@code properties} and array {@code items} recurse.
 *
 * <p>Deliberately conservative: anything that isn't an unambiguous
 * representation of the declared type passes through untouched, so the
 * server's own validation error stays the diagnostic the model sees.
 * Notably {@code "yes"} / {@code "1"} are <b>not</b> booleans here —
 * guessing intent is how a coercion layer starts corrupting payloads.
 * Keys are never added, dropped or renamed; unknown keys (MCP schemas
 * commonly set {@code additionalProperties: true}) are left alone.
 */
public final class McpArgumentCoercer {

    private McpArgumentCoercer() {}

    /**
     * Returns {@code arguments} with every value the schema types as a
     * scalar coerced to that type. Returns the very same instance when
     * nothing needed changing, so callers can skip the copy.
     */
    public static Map<String, Object> coerce(
            Map<String, Object> arguments, @Nullable Map<String, Object> inputSchema) {
        if (arguments == null || arguments.isEmpty() || inputSchema == null) {
            return arguments;
        }
        Map<String, Object> properties = propertiesOf(inputSchema);
        if (properties.isEmpty()) {
            return arguments;
        }
        Map<String, Object> out = new LinkedHashMap<>(arguments);
        boolean changed = false;
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            Object schema = properties.get(e.getKey());
            if (!(schema instanceof Map<?, ?> propSchema)) {
                continue;
            }
            Object coerced = coerceValue(e.getValue(), asStringKeyed(propSchema));
            if (coerced != e.getValue()) {
                out.put(e.getKey(), coerced);
                changed = true;
            }
        }
        return changed ? out : arguments;
    }

    /**
     * Coerces a single value against one property schema. Returns the
     * identical reference when no coercion applies — the caller's
     * change detection relies on that.
     */
    private static Object coerceValue(
            @Nullable Object value, Map<String, Object> propSchema) {
        if (value == null) {
            return value;
        }
        String type = declaredType(propSchema);
        if (type == null) {
            return value;
        }
        return switch (type) {
            case "boolean" -> toBoolean(value);
            case "integer" -> toInteger(value);
            case "number" -> toNumber(value);
            case "string" -> toStringValue(value);
            case "object" -> coerceObject(value, propSchema);
            case "array" -> coerceArray(value, propSchema);
            default -> value;
        };
    }

    private static Object toBoolean(Object value) {
        if (!(value instanceof String s)) {
            return value;
        }
        String t = s.trim();
        if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
        return value;
    }

    private static Object toInteger(Object value) {
        if (value instanceof String s) {
            Object parsed = parseNumber(s.trim());
            return parsed == null ? value : toInteger(parsed);
        }
        if (value instanceof Double d) {
            return d % 1 == 0 && !d.isInfinite() ? (Object) d.longValue() : value;
        }
        if (value instanceof Float f) {
            return f % 1 == 0 && !f.isInfinite() ? (Object) f.longValue() : value;
        }
        return value;
    }

    private static Object toNumber(Object value) {
        if (!(value instanceof String s)) {
            return value;
        }
        Object parsed = parseNumber(s.trim());
        return parsed == null ? value : parsed;
    }

    private static Object toStringValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return value;
    }

    private static Object coerceObject(Object value, Map<String, Object> propSchema) {
        Object resolved = value;
        if (value instanceof String s) {
            Object parsed = parseJson(s.trim());
            if (!(parsed instanceof Map<?, ?>)) {
                return value;
            }
            resolved = parsed;
        }
        if (!(resolved instanceof Map<?, ?> map)) {
            return value;
        }
        Map<String, Object> nested = coerce(asStringKeyed(map), propSchema);
        // Either the JSON text was unwrapped or a nested value changed;
        // both mean the caller must see a new value.
        return resolved == value && nested == resolved ? value : nested;
    }

    private static Object coerceArray(Object value, Map<String, Object> propSchema) {
        Object resolved = value;
        if (value instanceof String s) {
            Object parsed = parseJson(s.trim());
            if (!(parsed instanceof List<?>)) {
                return value;
            }
            resolved = parsed;
        }
        if (!(resolved instanceof List<?> list)) {
            return value;
        }
        Object itemsRaw = propSchema.get("items");
        if (!(itemsRaw instanceof Map<?, ?> items)) {
            return resolved;
        }
        Map<String, Object> itemSchema = asStringKeyed(items);
        List<Object> out = new ArrayList<>(list.size());
        boolean changed = false;
        for (Object element : list) {
            Object coerced = coerceValue(element, itemSchema);
            changed |= coerced != element;
            out.add(coerced);
        }
        if (!changed) {
            return resolved;
        }
        return out;
    }

    /**
     * The single declared type, lowercased. {@code null} when the schema
     * declares none, or a union that isn't "one type plus null" — a
     * genuine union has no unambiguous target to coerce towards.
     */
    private static @Nullable String declaredType(Map<String, Object> propSchema) {
        Object type = propSchema.get("type");
        if (type instanceof String s) {
            return s.trim().toLowerCase(Locale.ROOT);
        }
        if (type instanceof List<?> list) {
            String single = null;
            for (Object entry : list) {
                if (!(entry instanceof String s)) continue;
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (t.equals("null")) continue;
                if (single != null) return null;
                single = t;
            }
            return single;
        }
        return null;
    }

    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        return schema.get("properties") instanceof Map<?, ?> p
                ? asStringKeyed(p)
                : Map.of();
    }

    /** Parses {@code s} as a JSON number; {@code null} when it isn't one. */
    private static @Nullable Object parseNumber(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            Object parsed = PackJson.read(s);
            return parsed instanceof Number n ? n : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static @Nullable Object parseJson(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            return PackJson.read(s);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyed(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
