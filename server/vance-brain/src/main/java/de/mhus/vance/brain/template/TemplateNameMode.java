package de.mhus.vance.brain.template;

/** How a template determines the created document's filename. */
public enum TemplateNameMode {
    /** User picks the filename; {@code name.default} may prefill it. */
    FREE,
    /** Fixed filename from {@code name.value} (e.g. {@code _app.yaml}); user picks only the folder. */
    FIXED;

    /** Lowercase wire token used in DTOs ({@code "free"} / {@code "fixed"}). */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
