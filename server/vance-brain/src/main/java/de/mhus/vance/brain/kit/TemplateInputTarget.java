package de.mhus.vance.brain.kit;

import org.jspecify.annotations.Nullable;

/**
 * Where the value of an input is persisted after substitution.
 *
 * <p>{@code DOCUMENT_INLINE} (the default for non-secret inputs) means
 * "replace {@code {{var:<name>}}} in every kit document with this
 * value". {@code SETTING} stores the value via
 * {@link de.mhus.vance.shared.settings.SettingService} and the kit's
 * documents typically reference it via {@code {{secret:...}}} at runtime.
 *
 * <p>PASSWORD-typed inputs <b>must</b> use {@link Kind#SETTING} so the
 * secret never lands inline in a YAML document. The apply code
 * enforces this — a PASSWORD input without a target is rejected at
 * parse time.
 *
 * <p>Such a setting is persisted as
 * {@link de.mhus.vance.api.settings.SettingType#HIDDEN}, not {@code PASSWORD}:
 * being referenced by the kit's documents is the whole purpose of
 * {@link Kind#SETTING}, and a {@code PASSWORD}-typed setting is not
 * reference-readable. See {@code planning/setting-type-hidden.md} §7.
 */
public record TemplateInputTarget(
        Kind kind,
        @Nullable Scope scope,
        @Nullable String project,
        @Nullable String key) {

    public enum Kind {
        /** Value substituted into the kit's documents. */
        DOCUMENT_INLINE,
        /** Value stored via SettingService; NOT substituted into documents. */
        SETTING
    }

    /** Setting-storage scope. Mirrors {@code SettingService} reference types. */
    public enum Scope {
        TENANT,    // _tenant project
        PROJECT,   // the project the kit is applied to
        USER;      // current user (only when ctx.userId is known)

        public static Scope parse(String raw, String fieldLabel) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "template input '" + fieldLabel + "': target.scope is required when kind=setting");
            }
            try {
                return Scope.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "template input '" + fieldLabel + "': unknown target.scope '" + raw
                                + "' — expected tenant | project | user");
            }
        }
    }

    /** Default for non-PASSWORD inputs that don't declare a target. */
    public static TemplateInputTarget documentInline() {
        return new TemplateInputTarget(Kind.DOCUMENT_INLINE, null, null, null);
    }
}
