package de.mhus.vance.shared.action;

/**
 * Structured reason why a YAML map didn't parse to a valid
 * {@link de.mhus.vance.api.action.TriggerAction}. See
 * {@code planning/trigger-actions.md} §3.2.
 *
 * @param kind   coarse error category
 * @param field  the offending field (e.g. {@code "recipe"},
 *               {@code "script.source"}); empty for top-level
 * @param detail human-readable detail, suitable for logs and the
 *               WebUI editor's inline validation
 */
public record ActionValidationError(Kind kind, String field, String detail) {

    public enum Kind {
        /** Disjunction is empty — none of {@code recipe}/{@code script}/{@code workflow} is set. */
        NONE_SET,
        /** Disjunction is overspecified — more than one of the three is set. */
        MULTIPLE_SET,
        /** A required sub-field is missing (e.g. {@code script.path}). */
        MISSING_FIELD,
        /** A field's value is out of range (unknown enum, non-positive timeout, …). */
        BAD_VALUE,
        /** A field has the wrong YAML type (e.g. {@code params: "string"} instead of map). */
        BAD_TYPE
    }
}
