package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * One choice of a {@code select} widget.
 *
 * <p>Authored either as a bare scalar — {@code options: [open, paid]}, where the
 * value is also the caption — or as a mapping when the two differ:
 * {@code {value: paid, label: Bezahlt}}. Both spellings exist because most
 * option lists are short technical words that read fine as they are, and
 * demanding a mapping for those would be noise in every document.
 *
 * <p><b>Not</b> a mini-language inside the scalar. {@code "paid|Bezahlt"} was
 * the shorter alternative and is exactly the kind of thing this schema refuses:
 * a separator inside a value means every author has to know an escaping rule,
 * and YAML already has a way to write two things.
 */
@GenerateTypeScript("bistromath")
public record ViewOption(String value, String label) {

    public ViewOption {
        if (value == null) value = "";
        if (label == null || label.isBlank()) label = value;
    }
}
