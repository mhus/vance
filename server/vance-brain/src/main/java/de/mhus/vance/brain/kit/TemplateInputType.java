package de.mhus.vance.brain.kit;

/**
 * Input field kinds accepted by {@link TemplateDescriptor#inputs()}.
 * Drives both the Web-UI form rendering and the parse-side
 * validation in {@link KitYamlMapper#parseTemplate}.
 *
 * <p>Kept deliberately small; add more (URL, EMAIL, …) only when a
 * concrete template needs them.
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
    SELECT,
    /**
     * Web-UI: multi-checkbox / chips. Requires {@code choices:} as a list of
     * {@link TemplateChoice} objects (with per-choice {@code default}).
     * The value is serialised as a JSON array (e.g. {@code ["jira", "confluence"]}),
     * which is also a valid YAML flow sequence — both forms parse correctly
     * when substituted via {@code {{var:<name>}}}.
     */
    MULTI_SELECT;

    public static TemplateInputType parse(String raw, String fieldLabel) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "template input '" + fieldLabel + "': 'type' is required");
        }
        // Accept both `multi_select` (canonical Java enum form) and `multiselect`
        // (YAML-friendly shorthand) as aliases.
        String token = raw.trim().toUpperCase().replace('-', '_');
        if ("MULTISELECT".equals(token)) token = "MULTI_SELECT";
        try {
            return TemplateInputType.valueOf(token);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "template input '" + fieldLabel + "': unknown type '" + raw
                            + "' — expected one of "
                            + java.util.Arrays.toString(values()).toLowerCase());
        }
    }
}
