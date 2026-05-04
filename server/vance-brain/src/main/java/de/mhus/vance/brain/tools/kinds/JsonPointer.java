package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * JSON Pointer (RFC-6901) walk for the {@code data_*} tools.
 * Slash-separated; empty pointer "" or "/" is the root. Array index
 * tokens are decimal digits; the dash {@code "-"} as a token in a
 * {@code set} call appends.
 *
 * <p>Escape rules: {@code ~0} → {@code "~"}, {@code ~1} → {@code "/"}.
 */
public final class JsonPointer {

    private JsonPointer() {}

    public static String[] parse(@Nullable String pointer) {
        if (pointer == null || pointer.isEmpty()) return new String[0];
        if (!pointer.startsWith("/")) {
            throw new ToolException("JSON Pointer must start with '/' (got: '" + pointer + "')");
        }
        String[] parts = pointer.substring(1).split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].replace("~1", "/").replace("~0", "~");
        }
        return parts;
    }

    public static @Nullable Object resolve(@Nullable Object root, String[] path) {
        Object cur = root;
        for (String token : path) {
            if (cur == null) return null;
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(token);
            } else if (cur instanceof List<?> l) {
                int idx = parseIndex(token);
                if (idx < 0 || idx >= l.size()) return null;
                cur = l.get(idx);
            } else {
                return null;
            }
        }
        return cur;
    }

    /** Set the value at {@code path}. Empty path replaces the root
     *  via the {@code rootSetter}. Returns the previous value at the
     *  position, or {@code null}. */
    @SuppressWarnings("unchecked")
    public static @Nullable Object set(Object root, String[] path, @Nullable Object value,
                                       java.util.function.Consumer<Object> rootSetter) {
        if (path.length == 0) {
            rootSetter.accept(value);
            return root;
        }
        Object parent = walk(root, path, path.length - 1);
        String lastToken = path[path.length - 1];
        if (parent instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            return map.put(lastToken, value);
        }
        if (parent instanceof List<?> rawList) {
            List<Object> list = (List<Object>) rawList;
            if ("-".equals(lastToken)) {
                list.add(value);
                return null;
            }
            int idx = parseIndex(lastToken);
            if (idx < 0 || idx > list.size()) {
                throw new ToolException("Index " + idx + " out of range for list of size " + list.size());
            }
            if (idx == list.size()) {
                list.add(value);
                return null;
            }
            return list.set(idx, value);
        }
        throw new ToolException("Path '" + format(path) + "' parent is neither object nor array");
    }

    /** Remove the value at {@code path}. Returns the removed value, or
     *  {@code null} when the path didn't resolve. Empty path is a no-op
     *  (we don't drop the root document). */
    @SuppressWarnings("unchecked")
    public static @Nullable Object remove(Object root, String[] path) {
        if (path.length == 0) return null;
        Object parent = walk(root, path, path.length - 1);
        String lastToken = path[path.length - 1];
        if (parent instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            return map.remove(lastToken);
        }
        if (parent instanceof List<?> rawList) {
            List<Object> list = (List<Object>) rawList;
            int idx = parseIndex(lastToken);
            if (idx < 0 || idx >= list.size()) return null;
            return list.remove(idx);
        }
        return null;
    }

    /** Walk through the first {@code n} tokens of {@code path}; if
     *  any intermediate Object is missing, create empty maps along
     *  the way (so {@code set} can build nested paths). */
    @SuppressWarnings("unchecked")
    private static Object walk(Object root, String[] path, int n) {
        Object cur = root;
        for (int i = 0; i < n; i++) {
            String token = path[i];
            if (cur instanceof Map<?, ?> rawMap) {
                Map<String, Object> map = (Map<String, Object>) rawMap;
                Object child = map.get(token);
                if (child == null) {
                    child = new LinkedHashMap<String, Object>();
                    map.put(token, child);
                }
                cur = child;
            } else if (cur instanceof List<?> rawList) {
                List<Object> list = (List<Object>) rawList;
                int idx = parseIndex(token);
                if (idx < 0 || idx >= list.size()) {
                    throw new ToolException("Cannot auto-create list index " + token
                            + " at " + format(java.util.Arrays.copyOfRange(path, 0, i + 1)));
                }
                cur = list.get(idx);
                if (cur == null) {
                    cur = new LinkedHashMap<String, Object>();
                    list.set(idx, cur);
                }
            } else {
                throw new ToolException("Path token '" + token + "' at depth " + i
                        + " runs into a non-container value");
            }
        }
        return cur;
    }

    private static int parseIndex(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new ToolException("Expected array index, got '" + token + "'");
        }
    }

    public static String format(String[] path) {
        if (path.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : path) {
            sb.append('/').append(p.replace("~", "~0").replace("/", "~1"));
        }
        return sb.toString();
    }

    /** Mutable deep-copy: turn an immutable parsed body (Jackson /
     *  SnakeYAML may return immutable collections in some cases)
     *  into one we can {@code set} and {@code remove} on. */
    @SuppressWarnings("unchecked")
    public static @Nullable Object mutableCopy(@Nullable Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String key) copy.put(key, mutableCopy(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> l) {
            List<Object> copy = new ArrayList<>(l.size());
            for (Object o : l) copy.add(mutableCopy(o));
            return copy;
        }
        return value;
    }
}
