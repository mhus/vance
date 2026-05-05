package de.mhus.vance.shared.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * Tiny JSON-Schema-style validator. Tailored for the worker-output
 * contract: recipe authors declare a small schema, the engine
 * validates the worker's parsed JSON reply against it before running
 * post-actions. Not a full Draft-7 implementation — just the operators
 * we use in practice.
 *
 * <p>Supported operators (against a {@link Map}-shaped JSON value):
 *
 * <ul>
 *   <li>{@code type}: {@code object}, {@code array}, {@code string},
 *       {@code number}, {@code integer}, {@code boolean}, {@code null}</li>
 *   <li>{@code required}: list of property names that must be present</li>
 *   <li>{@code properties}: per-key sub-schema (recursive)</li>
 *   <li>{@code items}: per-array-element sub-schema</li>
 *   <li>{@code enum}: list of allowed values</li>
 *   <li>{@code minLength} / {@code maxLength}: string length bounds</li>
 *   <li>{@code minimum} / {@code maximum}: number bounds (inclusive)</li>
 *   <li>{@code pattern}: regex the string must fully match</li>
 * </ul>
 *
 * <p>Anything else in the schema map is silently ignored — explicit
 * goal is "small, predictable, easy to audit". Add more operators
 * when a real recipe needs one, not preemptively.
 */
public final class JsonSchemaLight {

    /** Parse-and-validate result. */
    public record Result(boolean valid, List<String> errors) {
        public static Result ok() { return new Result(true, List.of()); }
        public static Result fail(List<String> errs) {
            return new Result(false, List.copyOf(errs));
        }
        public String firstError() {
            return errors.isEmpty() ? "" : errors.get(0);
        }
        public String errorsJoined() {
            return String.join("; ", errors);
        }
    }

    private JsonSchemaLight() {}

    /** Validate {@code value} against {@code schema}. */
    public static Result validate(@Nullable Object value, @Nullable Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) return Result.ok();
        List<String> errors = new ArrayList<>();
        validateAt(value, schema, "$", errors);
        return errors.isEmpty() ? Result.ok() : Result.fail(errors);
    }

    @SuppressWarnings("unchecked")
    private static void validateAt(
            @Nullable Object value, Map<String, Object> schema, String path,
            List<String> errors) {
        Object typeRaw = schema.get("type");
        if (typeRaw instanceof String type) {
            if (!matchesType(value, type)) {
                errors.add(path + ": expected type '" + type
                        + "' but got " + describeType(value));
                return;
            }
        }

        // Constraints applied per-actual-type.
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> obj = (Map<String, Object>) mapValue;
            // required
            Object reqRaw = schema.get("required");
            if (reqRaw instanceof List<?> reqList) {
                for (Object reqEntry : reqList) {
                    if (reqEntry instanceof String reqKey && !obj.containsKey(reqKey)) {
                        errors.add(path + ": missing required property '" + reqKey + "'");
                    }
                }
            }
            // properties (recursive validation)
            Object propsRaw = schema.get("properties");
            if (propsRaw instanceof Map<?, ?> propsMap) {
                for (Map.Entry<?, ?> e : propsMap.entrySet()) {
                    String propName = String.valueOf(e.getKey());
                    if (!obj.containsKey(propName)) continue;
                    if (e.getValue() instanceof Map<?, ?> subSchemaRaw) {
                        Map<String, Object> subSchema = (Map<String, Object>) subSchemaRaw;
                        validateAt(obj.get(propName), subSchema,
                                path + "." + propName, errors);
                    }
                }
            }
        } else if (value instanceof List<?> arrayValue) {
            // array length bounds (could add min/maxItems later)
            Object itemsRaw = schema.get("items");
            if (itemsRaw instanceof Map<?, ?> itemsSchemaRaw) {
                Map<String, Object> itemsSchema = (Map<String, Object>) itemsSchemaRaw;
                for (int i = 0; i < arrayValue.size(); i++) {
                    validateAt(arrayValue.get(i), itemsSchema,
                            path + "[" + i + "]", errors);
                }
            }
        } else if (value instanceof String s) {
            Object minLen = schema.get("minLength");
            if (minLen instanceof Number n && s.length() < n.intValue()) {
                errors.add(path + ": string length " + s.length()
                        + " is below minLength " + n.intValue());
            }
            Object maxLen = schema.get("maxLength");
            if (maxLen instanceof Number n && s.length() > n.intValue()) {
                errors.add(path + ": string length " + s.length()
                        + " exceeds maxLength " + n.intValue());
            }
            Object patternRaw = schema.get("pattern");
            if (patternRaw instanceof String pat) {
                try {
                    if (!Pattern.compile(pat).matcher(s).matches()) {
                        errors.add(path + ": string '" + abbrev(s)
                                + "' does not match pattern /" + pat + "/");
                    }
                } catch (PatternSyntaxException pse) {
                    errors.add(path + ": schema pattern is invalid regex: "
                            + pse.getMessage());
                }
            }
        } else if (value instanceof Number num) {
            double d = num.doubleValue();
            Object min = schema.get("minimum");
            if (min instanceof Number n && d < n.doubleValue()) {
                errors.add(path + ": number " + d + " is below minimum " + n);
            }
            Object max = schema.get("maximum");
            if (max instanceof Number n && d > n.doubleValue()) {
                errors.add(path + ": number " + d + " exceeds maximum " + n);
            }
        }

        // enum applies to scalars.
        Object enumRaw = schema.get("enum");
        if (enumRaw instanceof List<?> enumList) {
            boolean match = false;
            for (Object allowed : enumList) {
                if (allowed == null && value == null) { match = true; break; }
                if (allowed != null && allowed.equals(value)) { match = true; break; }
            }
            if (!match) {
                errors.add(path + ": value '" + abbrev(String.valueOf(value))
                        + "' not in enum " + enumList);
            }
        }
    }

    private static boolean matchesType(@Nullable Object value, String type) {
        return switch (type.toLowerCase()) {
            case "object" -> value instanceof Map<?, ?>;
            case "array"  -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte
                    || (value instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue()));
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true; // Unknown type → permissive (don't reject silently-typo'd schemas)
        };
    }

    private static String describeType(@Nullable Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof List<?>) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        return value.getClass().getSimpleName();
    }

    private static String abbrev(String s) {
        if (s.length() <= 60) return s;
        return s.substring(0, 60) + "…";
    }
}
