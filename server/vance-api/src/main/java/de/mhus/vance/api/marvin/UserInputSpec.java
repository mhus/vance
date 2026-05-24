package de.mhus.vance.api.marvin;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Spec for a user-input request the worker wants Marvin to file in
 * the inbox. Mirrors the inbox-item create shape. See
 * {@code specification/marvin-engine.md} §4.1 / §4.2.
 *
 * @param type         inbox item type — DECISION, FEEDBACK, APPROVAL
 * @param title        short label for the inbox row
 * @param body         markdown body shown to the user
 * @param criticality  optional; default per inbox-item-service rules
 * @param payload      optional structured payload (e.g. options list)
 */
public record UserInputSpec(
        String type,
        String title,
        @Nullable String body,
        @Nullable String criticality,
        @Nullable Map<String, Object> payload) {

    public UserInputSpec {
        if (payload == null) {
            payload = new LinkedHashMap<>();
        }
    }
}
