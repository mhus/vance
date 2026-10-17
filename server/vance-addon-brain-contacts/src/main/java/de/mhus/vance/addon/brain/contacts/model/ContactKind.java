package de.mhus.vance.addon.brain.contacts.model;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What a card describes — vCard 4.0 {@code KIND}. {@code location} is left
 * out on purpose: nothing in Vancetope addresses a place through a contact.
 */
@GenerateTypeScript("contacts")
public enum ContactKind {
    INDIVIDUAL,
    ORG,
    GROUP;

    /** The lower-case spelling used on disk, in vCard and in tool params. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Lenient read: {@code null}, blank and unknown values fall back to
     * {@link #INDIVIDUAL} — the vCard default when {@code KIND} is absent.
     */
    public static ContactKind fromWire(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return INDIVIDUAL;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "org", "organization", "organisation" -> ORG;
            case "group" -> GROUP;
            default -> INDIVIDUAL;
        };
    }

    /** Strict read for tool params: unknown values are an error, not a default. */
    public static @Nullable ContactKind parseStrict(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "individual" -> INDIVIDUAL;
            case "org" -> ORG;
            case "group" -> GROUP;
            default ->
                throw new IllegalArgumentException("Unknown contact kind '" + raw + "' — use individual, org or group");
        };
    }
}
