package de.mhus.vance.brain.kit;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One input field on a {@link TemplateDescriptor}.
 *
 * @param name      Variable name — referenced as {@code {{var:<name>}}}
 *                  in the kit's documents.
 * @param type      Field kind (string / password / boolean / integer /
 *                  select / multi_select).
 * @param label     Human-readable label for the Web-UI form. Defaults to
 *                  {@code name} when omitted.
 * @param help      Optional help text shown beside the field.
 * @param required  Whether the input must be supplied at apply time.
 *                  Defaults to {@code true} — most inputs are mandatory.
 *                  For {@code multi_select}: {@code required=true} means
 *                  at least one choice must be selected.
 * @param defaultValue Pre-fill / fallback when the caller doesn't pass a value.
 *                  Ignored for {@code multi_select} — use per-choice
 *                  {@code default} flags on {@link TemplateChoice} instead.
 * @param choices   Allowed values for {@code SELECT} and {@code MULTI_SELECT};
 *                  ignored otherwise.
 * @param target    Where the value lands (document-inline vs setting).
 *                  PASSWORDs must use {@link TemplateInputTarget.Kind#SETTING}.
 */
public record TemplateInput(
        String name,
        TemplateInputType type,
        String label,
        @Nullable String help,
        boolean required,
        @Nullable String defaultValue,
        List<TemplateChoice> choices,
        TemplateInputTarget target) {

    public TemplateInput {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("template input: 'name' is required");
        }
        if (type == null) {
            throw new IllegalArgumentException(
                    "template input '" + name + "': 'type' is required");
        }
        if (label == null || label.isBlank()) label = name;
        choices = choices == null ? List.of() : List.copyOf(choices);
        if (target == null) target = TemplateInputTarget.documentInline();
        // Security invariant: PASSWORD must never flow into document-
        // inline substitution — a secret in a YAML document on disk
        // is the bug we want to make structurally impossible.
        if (type == TemplateInputType.PASSWORD
                && target.kind() != TemplateInputTarget.Kind.SETTING) {
            throw new IllegalArgumentException(
                    "template input '" + name + "': type=password requires target.kind=setting "
                            + "(secrets must not be substituted inline into documents)");
        }
        if ((type == TemplateInputType.SELECT || type == TemplateInputType.MULTI_SELECT)
                && choices.isEmpty()) {
            throw new IllegalArgumentException(
                    "template input '" + name + "': type=" + type.name().toLowerCase()
                            + " requires non-empty 'choices'");
        }
        // Multi-select with target=setting would land a JSON-array string
        // in a setting cell — fine technically, but probably not what the
        // author meant. Reject loudly until a concrete use-case forces it.
        if (type == TemplateInputType.MULTI_SELECT
                && target.kind() == TemplateInputTarget.Kind.SETTING) {
            throw new IllegalArgumentException(
                    "template input '" + name + "': type=multi_select cannot use "
                            + "target.kind=setting — multi-select values are intended for "
                            + "inline substitution (or for driving the documents-overlay "
                            + "and derived blocks)");
        }
        // Reject duplicate choice values up front — would silently win/lose
        // in lookup tables.
        if (!choices.isEmpty()) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (TemplateChoice c : choices) {
                if (!seen.add(c.value())) {
                    throw new IllegalArgumentException(
                            "template input '" + name + "': duplicate choice value '"
                                    + c.value() + "'");
                }
            }
        }
    }

    /**
     * Convenience: returns just the choice values (kept for callers that
     * need the v1 string-list shape for validation messages).
     */
    public List<String> choiceValues() {
        List<String> out = new ArrayList<>(choices.size());
        for (TemplateChoice c : choices) out.add(c.value());
        return out;
    }
}
