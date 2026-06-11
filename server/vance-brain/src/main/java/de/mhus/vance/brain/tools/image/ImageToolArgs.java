package de.mhus.vance.brain.tools.image;

import de.mhus.vance.brain.image.ImageManipulationException;
import de.mhus.vance.brain.image.ImageOpResult;
import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Shared argument-reading + response-shaping helpers for the
 * {@code image_*} tool family. Each tool wrapper is the same dünn-um-den-Service
 * pattern; centralising the param coercion keeps the wrappers terse and
 * the type rules consistent across tools.
 */
final class ImageToolArgs {

    private ImageToolArgs() {}

    static String readNonBlank(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }

    static @Nullable String readString(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }

    static int readInt(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) {
            throw new ToolException("'" + key + "' is required");
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new ToolException("'" + key + "' must be an integer, got '" + raw + "'");
        }
    }

    static @Nullable Integer readOptionalInt(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        String s = raw.toString().trim();
        if (s.isBlank()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new ToolException("'" + key + "' must be an integer, got '" + raw + "'");
        }
    }

    static double readDouble(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) {
            throw new ToolException("'" + key + "' is required");
        }
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new ToolException("'" + key + "' must be a number, got '" + raw + "'");
        }
    }

    static @Nullable Double readOptionalDouble(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.doubleValue();
        String s = raw.toString().trim();
        if (s.isBlank()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new ToolException("'" + key + "' must be a number, got '" + raw + "'");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readMap(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new ToolException("'" + key + "' must be an object");
        }
        return (Map<String, Object>) m;
    }

    static Map<String, Object> successResponse(ImageOpResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", r.path());
        out.put("mimeType", r.mimeType());
        out.put("width", r.width());
        out.put("height", r.height());
        out.put("sizeBytes", r.sizeBytes());
        out.put("durationMs", r.durationMs());
        return out;
    }

    static Map<String, Object> errorResponse(ImageManipulationException e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", e.getReason().wire());
        out.put("message", e.getMessage());
        out.put("retryable", e.getReason().retryable());
        return out;
    }
}
