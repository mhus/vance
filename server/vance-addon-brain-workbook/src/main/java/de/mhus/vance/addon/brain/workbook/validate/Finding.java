package de.mhus.vance.addon.brain.workbook.validate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One validation finding for a workbook / workpage. Structured so the
 * LLM (and the future UI panel) can act on it: {@code level} says how bad,
 * {@code location} says where (page + fence), {@code code} is a stable
 * machine token, {@code message} is the human-readable explanation.
 */
public record Finding(Level level, String location, String code, String message) {

    public enum Level { ERROR, WARNING }

    public static Finding error(String location, String code, String message) {
        return new Finding(Level.ERROR, location, code, message);
    }

    public static Finding warning(String location, String code, String message) {
        return new Finding(Level.WARNING, location, code, message);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level.name().toLowerCase());
        m.put("location", location);
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
