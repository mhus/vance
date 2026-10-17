package de.mhus.vance.addon.brain.contacts.model;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Where a contact lives. Reads merge all three, most specific first
 * ({@link #PROJECT} → {@link #USER} → {@link #TENANT}); a write goes to
 * exactly one.
 */
@GenerateTypeScript("contacts")
public enum ContactScope {
    /** The caller's own hub {@code _user_<login>} — private. */
    USER,
    /** The project the caller works in. */
    PROJECT,
    /** The tenant project {@code _tenant} — every member reads it. */
    TENANT;

    /** Cascade order for reads: the more specific scope shadows the broader one. */
    public static final java.util.List<ContactScope> READ_ORDER = java.util.List.of(PROJECT, USER, TENANT);

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** How far a card in this scope is visible: user 0 (narrowest) … tenant 2. */
    public int breadth() {
        return switch (this) {
            case USER -> 0;
            case PROJECT -> 1;
            case TENANT -> 2;
        };
    }

    /** Strict parse for params; {@code null}/blank → {@code null} (= caller's default). */
    public static @Nullable ContactScope parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "user", "my", "mine", "hub" -> USER;
            case "project" -> PROJECT;
            case "tenant" -> TENANT;
            default ->
                throw new IllegalArgumentException("Unknown contact scope '" + raw + "' — use user, project or tenant");
        };
    }
}
