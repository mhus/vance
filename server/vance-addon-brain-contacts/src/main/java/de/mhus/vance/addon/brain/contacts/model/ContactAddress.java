package de.mhus.vance.addon.brain.contacts.model;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A postal address — vCard {@code ADR}. {@code label} is the free multi-line
 * form some exporters carry instead of (or next to) the parts.
 */
@GenerateTypeScript("contacts")
public record ContactAddress(
        List<String> types,
        @Nullable Integer pref,
        @Nullable String poBox,
        @Nullable String extended,
        @Nullable String street,
        @Nullable String locality,
        @Nullable String region,
        @Nullable String postalCode,
        @Nullable String country,
        @Nullable String label) {

    public ContactAddress {
        types = types == null ? List.of() : List.copyOf(types);
        poBox = blankToNull(poBox);
        extended = blankToNull(extended);
        street = blankToNull(street);
        locality = blankToNull(locality);
        region = blankToNull(region);
        postalCode = blankToNull(postalCode);
        country = blankToNull(country);
        label = blankToNull(label);
    }

    /** Whether nothing but types is set — such an address is dropped on write. */
    public boolean isEmpty() {
        return poBox == null
                && extended == null
                && street == null
                && locality == null
                && region == null
                && postalCode == null
                && country == null
                && label == null;
    }

    /** One line for lists and search: "Hauptstraße 5, 10115 Berlin, DE". */
    public String oneLine() {
        if (label != null && street == null && locality == null) return label.replace('\n', ' ');
        StringBuilder sb = new StringBuilder();
        join(sb, street);
        String cityLine = ((postalCode == null ? "" : postalCode + " ") + (locality == null ? "" : locality)).trim();
        join(sb, cityLine);
        join(sb, region);
        join(sb, country);
        return sb.toString();
    }

    private static void join(StringBuilder sb, @Nullable String part) {
        if (part == null || part.isBlank()) return;
        if (!sb.isEmpty()) sb.append(", ");
        sb.append(part);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
