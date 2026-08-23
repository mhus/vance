package de.mhus.vance.shared.form;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.shared.form.FormValidationException.FormValidationError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Validates a submitted {@code Map<String, Object>} against a list of
 * {@link FormFieldDto}s before it's handed to the Pebble renderer.
 * Validation is purely structural: required-presence, type coercion,
 * integer bounds, repeat-bounds, select-whitelist. The renderer must
 * never see malformed input.
 *
 * <p>Errors are collected (not fail-fast) — the Web-UI can highlight
 * every broken field in one round-trip.
 *
 * <h2>Conditional fields ({@code showIf}) are exempt from {@code required}</h2>
 * {@link FormFieldDto#getShowIf()} is a <b>Pebble</b> expression, and this
 * validator is deliberately Pebble-free: it lives in {@code vance-shared}, runs
 * before any renderer, and is shared by wizards, kit tool-templates and document
 * templates — none of which carry {@code showIf} at all
 * ({@link FormFieldYamlParser} does not even parse it). Only Setting Forms do,
 * and there the expression is evaluated at apply time by the brain's plan
 * builder, which then treats a hidden field's binding as absent.
 *
 * <p>Enforcing {@code required} here on such a field asserts the opposite of the
 * documented contract ("treated as absent for required/bindsTo enforcement") and
 * has a concrete failure: the vault form's provider select defaults to
 * {@code settings}, which needs no connection, yet every Infisical-only field
 * behind a {@code showIf} was reported {@code required} and the form returned
 * 422 on <em>every</em> save. Skipping them cannot produce that class of error at
 * all, and gives up only the case "the condition is in fact true and the user
 * left the field empty" — which the planner sees anyway, with the values in hand.
 *
 * <p>The narrower alternative — have the caller evaluate the expressions and pass
 * the visible set in — stays open, and is what an exact enforcement would need.
 * It is not built here because it would put a parameter on this class that only
 * one of its four callers could ever fill.
 */
@Service
public class FormValidator {

    public static final String TYPE_STRING = "string";
    public static final String TYPE_TEXTAREA = "textarea";
    public static final String TYPE_PASSWORD = "password";
    public static final String TYPE_INTEGER = "integer";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_SELECT = "select";
    public static final String TYPE_MULTI_SELECT = "multi_select";
    public static final String TYPE_REPEAT = "repeat";

    /**
     * Walks {@code fields} and checks each entry against {@code values}.
     * Throws {@link FormValidationException} when at least one field
     * is malformed; returns normally otherwise.
     */
    public void validate(List<FormFieldDto> fields, Map<String, Object> values) {
        List<FormValidationError> errors = new ArrayList<>();
        validateInto("", fields, values, errors);
        if (!errors.isEmpty()) {
            throw new FormValidationException(errors);
        }
    }

    private void validateInto(
            String pathPrefix,
            List<FormFieldDto> fields,
            Map<String, Object> values,
            List<FormValidationError> errors) {
        for (FormFieldDto field : fields) {
            String path = pathPrefix.isEmpty() ? field.getName() : pathPrefix + "." + field.getName();
            Object raw = values.get(field.getName());
            validateField(path, field, raw, errors);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateField(
            String path,
            FormFieldDto field,
            @Nullable Object raw,
            List<FormValidationError> errors) {
        String type = field.getType();
        boolean missing = raw == null || (raw instanceof String s && s.isBlank());
        if (missing) {
            // A blank conditional field may simply be one whose condition is
            // false — see the class javadoc. Only the type checks below are
            // skipped along with it, and those have nothing to check anyway.
            if (field.isRequired() && !isConditional(field)) {
                errors.add(new FormValidationError(path, "required"));
            }
            return;
        }
        switch (type) {
            case TYPE_STRING, TYPE_TEXTAREA, TYPE_PASSWORD -> {
                if (!(raw instanceof String)) {
                    errors.add(new FormValidationError(path, "expected_string"));
                }
            }
            case TYPE_INTEGER -> {
                Integer parsed = tryParseInt(raw);
                if (parsed == null) {
                    errors.add(new FormValidationError(path, "expected_integer"));
                    return;
                }
                if (field.getIntegerMin() != null && parsed < field.getIntegerMin()) {
                    errors.add(new FormValidationError(path, "below_min"));
                }
                if (field.getIntegerMax() != null && parsed > field.getIntegerMax()) {
                    errors.add(new FormValidationError(path, "above_max"));
                }
            }
            case TYPE_BOOLEAN -> {
                if (!(raw instanceof Boolean) && !isBooleanString(raw)) {
                    errors.add(new FormValidationError(path, "expected_boolean"));
                }
            }
            case TYPE_SELECT -> {
                if (!(raw instanceof String s)) {
                    errors.add(new FormValidationError(path, "expected_string"));
                    return;
                }
                if (!isAllowedChoice(s, field.getChoices())) {
                    errors.add(new FormValidationError(path, "invalid_choice"));
                }
            }
            case TYPE_MULTI_SELECT -> {
                List<String> picked = tryAsStringList(raw);
                if (picked == null) {
                    errors.add(new FormValidationError(path, "expected_string_list"));
                    return;
                }
                for (String s : picked) {
                    if (!isAllowedChoice(s, field.getChoices())) {
                        errors.add(new FormValidationError(path, "invalid_choice"));
                        break;
                    }
                }
            }
            case TYPE_REPEAT -> {
                if (!(raw instanceof List<?> rawList)) {
                    errors.add(new FormValidationError(path, "expected_array"));
                    return;
                }
                if (field.getMin() != null && rawList.size() < field.getMin()) {
                    errors.add(new FormValidationError(path, "too_few_entries"));
                }
                if (field.getMax() != null && rawList.size() > field.getMax()) {
                    errors.add(new FormValidationError(path, "too_many_entries"));
                }
                List<FormFieldDto> itemSchema = field.getItem();
                if (itemSchema == null || itemSchema.isEmpty()) {
                    return;
                }
                for (int i = 0; i < rawList.size(); i++) {
                    Object entry = rawList.get(i);
                    if (!(entry instanceof Map<?, ?> entryMap)) {
                        errors.add(new FormValidationError(
                                path + "[" + i + "]", "expected_object"));
                        continue;
                    }
                    Map<String, Object> typedEntry = new java.util.HashMap<>();
                    for (Map.Entry<?, ?> me : entryMap.entrySet()) {
                        typedEntry.put(String.valueOf(me.getKey()), me.getValue());
                    }
                    validateInto(path + "[" + i + "]", itemSchema, typedEntry, errors);
                }
            }
            default -> errors.add(new FormValidationError(path, "unknown_field_type"));
        }
    }

    /**
     * Whether the field's presence depends on a condition this validator cannot
     * evaluate. Note it asks only about {@code showIf}: {@code writeIf} decides
     * whether the <em>target setting</em> is written or deleted, not whether the
     * user was shown a box to fill in, so a {@code required} field with a
     * {@code writeIf} stays required.
     */
    private static boolean isConditional(FormFieldDto field) {
        String showIf = field.getShowIf();
        return showIf != null && !showIf.isBlank();
    }

    private static @Nullable Integer tryParseInt(Object raw) {
        if (raw instanceof Integer i) return i;
        if (raw instanceof Long l) return l.intValue();
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean isBooleanString(Object raw) {
        if (!(raw instanceof String s)) return false;
        String t = s.trim().toLowerCase();
        return "true".equals(t) || "false".equals(t) || "1".equals(t) || "0".equals(t)
                || "yes".equals(t) || "no".equals(t);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<String> tryAsStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (!(o instanceof String s)) return null;
                out.add(s);
            }
            return out;
        }
        return null;
    }

    private static boolean isAllowedChoice(String value, @Nullable List<FormChoiceDto> choices) {
        if (choices == null || choices.isEmpty()) return false;
        for (FormChoiceDto c : choices) {
            if (value.equals(c.getValue())) return true;
        }
        return false;
    }
}
