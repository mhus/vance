package de.mhus.vance.api.marvin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-side action the worker wants performed after its node
 * reaches DONE. Currently used for deterministic document writes
 * (e.g. {@code doc_write_text}) without an LLM tool call.
 *
 * @param tool  operation name; e.g. {@code doc_write_text}
 * @param args  tool-specific arguments (path, content, title …);
 *              string args are rendered through Pebble at execution
 *              time with {@code node.*} / {@code process.*} roots
 */
public record PostActionSpec(String tool, Map<String, Object> args) {

    public PostActionSpec {
        if (args == null) {
            args = new LinkedHashMap<>();
        }
    }
}
