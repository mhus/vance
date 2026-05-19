package de.mhus.vance.brain.kit;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One input field on a {@link TemplateDescriptor}.
 *
 * @param name      Variable name — referenced as {@code {{var:<name>}}}
 *                  in the kit's documents.
 * @param type      Field kind (string / password / boolean / integer / select).
 * @param label     Human-readable label for the Web-UI form. Defaults to
 *                  {@code name} when omitted.
 * @param help      Optional help text shown beside the field.
 * @param required  Whether the input must be supplied at apply time.
 *                  Defaults to {@code true} — most inputs are mandatory.
 * @param defaultValue Pre-fill / fallback when the caller doesn't pass a value.
 * @param choices   Allowed values when {@link #type} = SELECT; ignored otherwise.
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
        List<String> choices,
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
        if (type == TemplateInputType.SELECT && choices.isEmpty()) {
            throw new IllegalArgumentException(
                    "template input '" + name + "': type=select requires non-empty 'choices'");
        }
    }
}
