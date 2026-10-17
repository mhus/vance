package de.mhus.vance.addon.brain.contacts.model;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The structured name — vCard {@code N}. Every part is optional. */
@GenerateTypeScript("contacts")
public record ContactName(
        @Nullable String family,
        @Nullable String given,
        List<String> additional,
        List<String> prefixes,
        List<String> suffixes) {

    public ContactName {
        family = blankToNull(family);
        given = blankToNull(given);
        additional = additional == null ? List.of() : List.copyOf(additional);
        prefixes = prefixes == null ? List.of() : List.copyOf(prefixes);
        suffixes = suffixes == null ? List.of() : List.copyOf(suffixes);
    }

    public static ContactName of(@Nullable String given, @Nullable String family) {
        return new ContactName(family, given, List.of(), List.of(), List.of());
    }

    /** Whether no part is set — such a name is dropped on write. */
    public boolean isEmpty() {
        return family == null && given == null && additional.isEmpty() && prefixes.isEmpty() && suffixes.isEmpty();
    }

    /** "Dr. Anna Maria Berger" — the display form used when no full name was given. */
    public String display() {
        StringBuilder sb = new StringBuilder();
        for (String p : prefixes) append(sb, p);
        append(sb, given);
        for (String a : additional) append(sb, a);
        append(sb, family);
        for (String s : suffixes) append(sb, s);
        return sb.toString();
    }

    private static void append(StringBuilder sb, @Nullable String part) {
        if (part == null || part.isBlank()) return;
        if (!sb.isEmpty()) sb.append(' ');
        sb.append(part.trim());
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
