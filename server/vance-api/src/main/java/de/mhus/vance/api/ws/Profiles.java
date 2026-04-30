package de.mhus.vance.api.ws;

/**
 * Canonical Connection-Profile string constants used in the WebSocket
 * handshake (wire-level) and as keys in recipe profile-blocks.
 *
 * <p>Profile is intentionally an <em>open string</em>, not an enum: tenants
 * may introduce custom profiles (e.g. {@code "ci-bot"}, {@code "kiosk"}) and
 * configure recipe-profile-blocks for them without a Java code change. The
 * brain validates only the <em>shape</em> of the value (lowercase, alpha-num
 * + {@code _-}, ≤ 32 chars), not its identity.
 *
 * <p>This class collects the well-known names so server- and client-side
 * code that hardcodes a specific profile (e.g. {@code vance-foot} always
 * connects with {@link #FOOT}) gets compile-time safety instead of stringly-
 * typed magic.
 *
 * <p>Wire-level default when the client omits {@code ?profile=} is
 * {@link #WEB} — the most restrictive canonical value.
 *
 * <p>Recipe-block fallback uses {@link #DEFAULT} as the catch-all key when
 * no exact-match block exists in a recipe (see
 * {@code specification/recipes.md} §6a).
 *
 * <p>{@link #DAEMON} is reserved for future {@code vance-foot -d} headless
 * mode — currently has no special server-side behavior; documented in
 * {@code specification/client-protokoll-erweiterbarkeit.md} §2.1a.
 */
public final class Profiles {

    /** Terminal client ({@code vance-foot}). Has shell + FS tools + client-side {@code agent.md} manual. */
    public static final String FOOT = "foot";

    /** Browser-based UI ({@code @vance/vance-face}). No local tools, no client manual. Wire default. */
    public static final String WEB = "web";

    /** Mobile app (future). Restricted tool set, shorter sessions. */
    public static final String MOBILE = "mobile";

    /** Headless tool-provider mode (future, {@code vance-foot -d}). Reserved name. */
    public static final String DAEMON = "daemon";

    /** Catch-all key for recipe-profile-blocks when no exact-match block exists. */
    public static final String DEFAULT = "default";

    /**
     * Shape-validation pattern for profile strings. Profiles that match
     * are accepted by the brain — whether they have a recipe-block or
     * fall through to {@link #DEFAULT} is a separate question handled
     * by the recipe resolver.
     */
    public static final String PATTERN = "^[a-z][a-z0-9_-]{0,31}$";

    private Profiles() {
    }
}
