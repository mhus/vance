package de.mhus.vance.brain.kit;

/**
 * Input field kinds accepted by {@link TemplateDescriptor#inputs()}.
 * Drives both the Web-UI form rendering and the parse-side
 * validation in {@link KitYamlMapper#parseTemplate}.
 *
 * <p>Kept deliberately small for v1; add more (URL, EMAIL, MULTI_SELECT)
 * only when a concrete template needs them.
 */
public enum TemplateInputType {
    /** Free-text. Web-UI: text input. */
    STRING,
    /** Secret. Web-UI: password input. Apply-time encrypted at rest. */
    PASSWORD,
    /** Web-UI: checkbox. Apply: {@code "true"} or {@code "false"} substitution. */
    BOOLEAN,
    /** Web-UI: number input. Apply: integer literal substitution. */
    INTEGER,
    /** Web-UI: dropdown. Requires {@code choices:} on the input. */
    SELECT;

    public static TemplateInputType parse(String raw, String fieldLabel) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "template input '" + fieldLabel + "': 'type' is required");
        }
        try {
            return TemplateInputType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "template input '" + fieldLabel + "': unknown type '" + raw
                            + "' — expected one of "
                            + java.util.Arrays.toString(values()).toLowerCase());
        }
    }
}
