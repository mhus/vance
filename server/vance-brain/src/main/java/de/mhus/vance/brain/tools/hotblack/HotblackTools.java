package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.audio.AudioManipulationException;
import de.mhus.vance.brain.audio.AudioOpResult;
import de.mhus.vance.brain.hotblack.HotblackException;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Small shared helpers for the {@code audio_*} tool family —
 *  generation (Hotblack) and local editing (AudioManipulationService)
 *  alike. */
final class HotblackTools {

    private HotblackTools() {}

    /** The public JSON error shape: {@code error}, {@code message}, {@code retryable}. */
    static Map<String, Object> errorResponse(HotblackException e) {
        return errorResponse(e.getReason().wire(), e.getMessage(), e.getReason().retryable());
    }

    /** Same shape for the audio-edit service's failures. */
    static Map<String, Object> errorResponse(AudioManipulationException e) {
        return errorResponse(e.getReason().wire(), e.getMessage(), e.getReason().retryable());
    }

    private static Map<String, Object> errorResponse(String wire, String message, boolean retryable) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", wire);
        out.put("message", message);
        out.put("retryable", retryable);
        return out;
    }

    /** Shared success shape of every audio-edit op. */
    static Map<String, Object> opResponse(AudioOpResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", r.path());
        out.put("mimeType", r.mimeType());
        out.put("sizeBytes", r.sizeBytes());
        if (r.durationSeconds() != null) {
            out.put("durationSeconds", r.durationSeconds());
        }
        out.put("durationMs", r.durationMs());
        return out;
    }

    static String readNonBlank(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }

    static @Nullable String readString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }

    /** Number parameter as {@link Double}; {@code null} when absent. */
    static @Nullable Double readDouble(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new ToolException("'" + key + "' must be a number", e);
        }
    }

    /** Number parameter as {@link Integer}; {@code null} when absent. */
    static @Nullable Integer readInt(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new ToolException("'" + key + "' must be an integer", e);
        }
    }

    /** List-of-strings parameter; {@code null} when absent, entries trimmed. */
    static @Nullable List<String> readStringList(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object entry : list) {
                if (entry != null && !entry.toString().isBlank()) {
                    out.add(entry.toString().trim());
                }
            }
            return out.isEmpty() ? null : out;
        }
        String s = raw.toString().trim();
        return s.isEmpty() ? null : List.of(s);
    }

    /** Number parameter with a default; {@code null} input ⇒ default. */
    static double readDouble(Map<String, Object> params, String key, double defaultValue) {
        Double v = readDouble(params, key);
        return v == null ? defaultValue : v;
    }

    /** Boolean parameter; {@code false} when absent. */
    static boolean readBoolean(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return false;
        if (raw instanceof Boolean b) return b;
        return Boolean.parseBoolean(raw.toString().trim());
    }

    /** List parameter, never null. */
    static List<String> readList(Map<String, Object> params, String key) {
        List<String> v = readStringList(params, key);
        return v == null ? List.of() : v;
    }
}
