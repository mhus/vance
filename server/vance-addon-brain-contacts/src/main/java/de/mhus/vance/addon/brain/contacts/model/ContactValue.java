package de.mhus.vance.addon.brain.contacts.model;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One typed value of a multi-valued vCard property — an e-mail, a phone
 * number, a URL, a messenger handle or a relation.
 *
 * @param value the value itself (address, number, URI, UUID of a related card)
 * @param types vCard {@code TYPE} parameters ({@code work}, {@code home},
 *              {@code cell}, …); values outside the vCard vocabulary are kept
 * @param pref  vCard {@code PREF} (1 = most preferred … 100), {@code null} when unset
 */
@GenerateTypeScript("contacts")
public record ContactValue(
        String value, List<String> types, @Nullable Integer pref) {

    public ContactValue {
        value = value == null ? "" : value.trim();
        types = types == null ? List.of() : List.copyOf(types);
    }

    public static ContactValue of(String value, String... types) {
        return new ContactValue(value, List.of(types), null);
    }
}
