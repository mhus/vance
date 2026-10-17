package de.mhus.vance.addon.brain.contacts;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The one place that turns caller input into a contact id and an id into a
 * path. Every path under {@code _vance/contacts/} is built here from a parsed
 * UUID — never from caller text — because the service writes these paths as
 * SYSTEM: a path fragment smuggled in through an "id" would be a write
 * anywhere under the reserved prefix.
 */
public final class ContactIds {

    /** Folder of the address book in every scope project. */
    public static final String FOLDER = "_vance/contacts/";

    public static final String CARD_SUFFIX = ".yaml";

    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private ContactIds() {}

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Lower-case canonical UUID, or {@code null} when {@code raw} is none. */
    public static @Nullable String normalize(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("urn:uuid:")) s = s.substring("urn:uuid:".length());
        return UUID_SHAPE.matcher(s).matches() ? s : null;
    }

    /** Like {@link #normalize} but fails with a message fit for a tool caller. */
    public static String require(@Nullable String raw) {
        String id = normalize(raw);
        if (id == null) throw new IllegalArgumentException("Not a contact id (UUID): '" + raw + "'");
        return id;
    }

    public static String cardPath(String id) {
        return FOLDER + require(id) + CARD_SUFFIX;
    }

    /** Photo path for an id and a fixed extension ({@code jpg} or {@code png}). */
    public static String photoPath(String id, String extension) {
        if (!"jpg".equals(extension) && !"png".equals(extension)) {
            throw new IllegalArgumentException("Unsupported photo extension: " + extension);
        }
        return FOLDER + require(id) + "." + extension;
    }

    /** The id of a card path ({@code _vance/contacts/<uuid>.yaml}), else {@code null}. */
    public static @Nullable String idOfCardPath(@Nullable String path) {
        if (path == null || !path.startsWith(FOLDER) || !path.endsWith(CARD_SUFFIX)) return null;
        String stem = path.substring(FOLDER.length(), path.length() - CARD_SUFFIX.length());
        return stem.contains("/") ? null : normalize(stem);
    }

    /** Whether the path is anything inside the address-book folder. */
    public static boolean inFolder(@Nullable String path) {
        return path != null && path.startsWith(FOLDER);
    }

    /** Whether a group member entry is a URI rather than a card id. */
    public static boolean isUri(String member) {
        int colon = member.indexOf(':');
        return colon > 0 && !member.startsWith("urn:uuid:");
    }
}
