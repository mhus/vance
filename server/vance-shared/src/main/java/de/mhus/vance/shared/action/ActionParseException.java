package de.mhus.vance.shared.action;

import java.util.List;

/**
 * Thrown by {@link TriggerActionParser#parse(java.util.Map)} when the
 * input YAML map does not describe a valid trigger action. Carries the
 * full list of validation errors so callers (loader / editor) can
 * surface them all at once instead of one-by-one.
 */
public class ActionParseException extends RuntimeException {

    private final List<ActionValidationError> errors;

    public ActionParseException(List<ActionValidationError> errors) {
        super(buildMessage(errors));
        this.errors = List.copyOf(errors);
    }

    public List<ActionValidationError> errors() {
        return errors;
    }

    private static String buildMessage(List<ActionValidationError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "Invalid trigger action (no detail)";
        }
        StringBuilder sb = new StringBuilder("Invalid trigger action:");
        for (ActionValidationError e : errors) {
            sb.append(" [").append(e.kind());
            if (!e.field().isEmpty()) {
                sb.append(' ').append(e.field());
            }
            sb.append(": ").append(e.detail()).append(']');
        }
        return sb.toString();
    }
}
