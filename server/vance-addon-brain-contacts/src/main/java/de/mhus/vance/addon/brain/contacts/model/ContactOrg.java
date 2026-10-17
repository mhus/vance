package de.mhus.vance.addon.brain.contacts.model;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The organisation a person belongs to — vCard {@code ORG}.
 *
 * @param name  organisation name
 * @param units departments, outermost first
 * @param ref   optional UUID of a {@code kind: org} card for the same organisation
 */
@GenerateTypeScript("contacts")
public record ContactOrg(
        @Nullable String name, List<String> units, @Nullable String ref) {

    public ContactOrg {
        name = name == null || name.isBlank() ? null : name.trim();
        units = units == null ? List.of() : List.copyOf(units);
        ref = ref == null || ref.isBlank() ? null : ref.trim();
    }

    public boolean isEmpty() {
        return name == null && units.isEmpty() && ref == null;
    }
}
