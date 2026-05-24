package de.mhus.vance.shared.form;

import java.util.List;

/**
 * Thrown by {@link FormValidator} when a submitted value map violates
 * the {@link de.mhus.vance.api.form.FormFieldDto} schema. The
 * {@link #getErrors()} list holds one entry per offending field with
 * a structured {@code field}-path (e.g. {@code members[2].name}) and
 * a short error code, suitable for the Web-UI to render inline.
 */
public class FormValidationException extends RuntimeException {

    private final List<FormValidationError> errors;

    public FormValidationException(List<FormValidationError> errors) {
        super(formatMessage(errors));
        this.errors = List.copyOf(errors);
    }

    public List<FormValidationError> getErrors() {
        return errors;
    }

    private static String formatMessage(List<FormValidationError> errors) {
        if (errors.isEmpty()) return "form validation failed";
        StringBuilder sb = new StringBuilder("form validation failed: ");
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) sb.append("; ");
            FormValidationError e = errors.get(i);
            sb.append(e.field()).append(": ").append(e.error());
        }
        return sb.toString();
    }

    /** One validation failure pinpointing field-path + error code. */
    public record FormValidationError(String field, String error) {}
}
