package de.mhus.vance.brain.tools.hotblack;

import de.mhus.vance.brain.hotblack.HotblackException;
import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small shared helpers for the {@code audio_*} tool family. */
final class HotblackTools {

    private HotblackTools() {}

    /** The public JSON error shape: {@code error}, {@code message}, {@code retryable}. */
    static Map<String, Object> errorResponse(HotblackException e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", e.getReason().wire());
        out.put("message", e.getMessage());
        out.put("retryable", e.getReason().retryable());
        return out;
    }

    static String readNonBlank(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }

    static String readString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isBlank() ? null : s;
    }
}
