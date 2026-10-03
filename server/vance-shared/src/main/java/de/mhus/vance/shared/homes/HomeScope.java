package de.mhus.vance.shared.homes;

import org.jspecify.annotations.Nullable;

/**
 * Which subjects share one {@code HOME}. Picked per project via the
 * {@code home-scope} setting (cascade {@code project → _tenant}), mapped onto
 * the scope-key vocabulary the permission system already uses
 * ({@code _tenant}, {@code _user_<login>} — see {@code permission-system.md}
 * R7):
 *
 * <ul>
 *   <li>{@link #PROJECT} — the project's own home, fully isolated;</li>
 *   <li>{@link #TENANT} — one home for all projects of the tenant;</li>
 *   <li>{@link #USER} — one home per user across that user's projects.</li>
 * </ul>
 */
public enum HomeScope {
    PROJECT,
    TENANT,
    USER;

    /**
     * Parses the setting value, {@code null} when absent or unknown. The
     * caller decides the fallback ({@link #PROJECT} is the fail-safe: the
     * narrowest isolation, so an unparsable value can never widen sharing).
     */
    public static @Nullable HomeScope parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase()) {
            case "project" -> PROJECT;
            case "tenant" -> TENANT;
            case "user" -> USER;
            default -> null;
        };
    }
}
