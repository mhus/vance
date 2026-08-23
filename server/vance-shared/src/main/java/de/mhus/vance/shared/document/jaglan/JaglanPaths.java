package de.mhus.vance.shared.document.jaglan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The {@code _ext/} namespace: what a mounted document's path looks like,
 * what a mount may be called, and how a mounted document's id is derived.
 *
 * <p>Pure static utility, no Spring, no IO — every rule here has to hold in
 * a unit test, because all three of them are load-bearing in a way that is
 * expensive to change later: the path is indexed in Mongo, the mount name is
 * part of the id, and the id travels in URLs and stored deep links.
 */
public final class JaglanPaths {

    private JaglanPaths() {}

    /** The mount namespace root — a folder that holds only mount folders. */
    public static final String ROOT = "_ext";

    /** Prefix every mounted document path carries. */
    public static final String PREFIX = ROOT + "/";

    /**
     * Prefix of every derived document id.
     *
     * <p><b>Stays {@code ext_}, and {@link #PREFIX} stays {@code _ext/},
     * regardless of what the subsystem is called.</b> Both are data format:
     * the prefix sits in every stored document path and every derived id, so
     * renaming them to match "Jaglan" would invalidate every stored deep
     * link, binder reference and held client id in one commit. {@code _ext}
     * also says the right thing to the person browsing the tree (external
     * content), which is a different audience from the code.
     *
     * <p>It exists so "is this a mounted document" is answerable from the id
     * alone, without loading the document — a client holding nothing but a
     * deep link can hide the actions that do not apply instead of loading
     * first and rebuilding after. A 24-character hex ObjectId can never
     * start with {@code ext_}, so there is no collision with real ids.
     */
    public static final String ID_PREFIX = "ext_";

    /** Hex characters kept from the digest. 128 bits — collision-free in
     *  practice, and the same length as a UUID without the dashes. */
    private static final int ID_HEX_LENGTH = 32;

    /**
     * Mount-name grammar. Lowercase kebab plus underscore, must start
     * alphanumeric, at most 64 characters.
     *
     * <p>Deliberately narrow, because the name is not a label: it is a path
     * segment that goes through RFC-3986 reference resolution in
     * {@code DocumentRefResolver}, is exposed as a folder over WebDAV,
     * is indexed as a Mongo path prefix, and is hashed into the document id.
     * The character class excludes {@code /} and {@code .} outright, which
     * is what keeps {@code ..} traversal out of a mount name, and the
     * leading-alphanumeric rule keeps mounts out of the {@code _}-prefixed
     * system namespace.
     */
    private static final Pattern MOUNT_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /** {@code true} when the path lives in the mount namespace. */
    public static boolean isMounted(@Nullable String path) {
        return path != null && path.startsWith(PREFIX);
    }

    /** {@code true} when the id was derived by {@link #documentId}. */
    public static boolean isMountedId(@Nullable String id) {
        return id != null && id.startsWith(ID_PREFIX);
    }

    /** {@code true} when {@code name} satisfies the mount-name grammar. */
    public static boolean isValidMountName(@Nullable String name) {
        return name != null && MOUNT_NAME.matcher(name).matches();
    }

    /**
     * Validate a configured mount name.
     *
     * <p>Called when a mount is configured, not when a path is built — a
     * bad name has to be refused once, at the place a human can fix it,
     * rather than turning into a broken path at every access.
     *
     * @throws IllegalArgumentException if the name violates the grammar
     */
    public static String requireValidMountName(@Nullable String name) {
        if (!isValidMountName(name)) {
            throw new IllegalArgumentException(
                    "invalid mount name '" + name + "' — expected "
                            + MOUNT_NAME.pattern());
        }
        return name;
    }

    /** The folder path of a mount: {@code _ext/<mount>}. */
    public static String mountRootPath(String mount) {
        return PREFIX + requireValidMountName(mount);
    }

    /**
     * Build the document path for an entry inside a mount.
     *
     * @param mount       the mount name
     * @param pathInMount mount-relative path, may be empty for the root
     */
    public static String documentPath(String mount, @Nullable String pathInMount) {
        String root = mountRootPath(mount);
        String rest = normalizeInMountPath(pathInMount);
        return rest.isEmpty() ? root : root + "/" + rest;
    }

    /**
     * The mount name out of a document path.
     *
     * @throws IllegalArgumentException if the path is not in the namespace
     *         or names no mount ({@code "_ext/"} alone)
     */
    public static String mountNameOf(String path) {
        if (!isMounted(path)) {
            throw new IllegalArgumentException("not a mounted path: '" + path + "'");
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        String mount = slash < 0 ? rest : rest.substring(0, slash);
        if (mount.isEmpty()) {
            throw new IllegalArgumentException("mounted path names no mount: '" + path + "'");
        }
        return mount;
    }

    /**
     * The mount-relative path out of a document path. Empty string for the
     * mount root itself.
     *
     * @throws IllegalArgumentException if the path is not in the namespace
     */
    public static String pathInMount(String path) {
        String mount = mountNameOf(path);
        String rest = path.substring(PREFIX.length() + mount.length());
        return rest.startsWith("/") ? rest.substring(1) : rest;
    }

    /**
     * Derive the stable document id for a mounted entry.
     *
     * <p>Deterministic on purpose. A mounted document's Mongo row is a
     * metadata shell with a TTL: it is purged and re-stat'ed as it ages, and
     * a generated {@code ObjectId} would be different every time, which
     * would invalidate every stored deep link, binder reference and held id
     * in a client on each cycle. Deriving it from the address means the row
     * can come and go while the id stays.
     *
     * <p>Tenant and project are part of the digest because {@code _id} is
     * <b>globally</b> unique in the collection while a document is addressed
     * by {@code (tenantId, projectId, path)}. Two projects mounting the same
     * source would otherwise derive one id for two documents and overwrite
     * each other's row.
     *
     * <p>The {@code \0} separator prevents the fields from running together:
     * without it {@code ("a", "bc")} and {@code ("ab", "c")} would hash the
     * same, and mount names and paths are adjacent user input.
     */
    public static String documentId(
            String tenantId, String projectId, String mount, @Nullable String pathInMount) {
        requireValidMountName(mount);
        String material = nullToEmpty(tenantId) + '\0'
                + nullToEmpty(projectId) + '\0'
                + mount + '\0'
                + normalizeInMountPath(pathInMount);
        return ID_PREFIX + sha256Hex(material).substring(0, ID_HEX_LENGTH);
    }

    /** {@link #documentId} for a full {@code _ext/...} document path. */
    public static String documentIdForPath(String tenantId, String projectId, String path) {
        return documentId(tenantId, projectId, mountNameOf(path), pathInMount(path));
    }

    /**
     * Derive the id of a {@link JaglanFolderState} marker.
     *
     * <p>Carries a different prefix than {@link #documentId} on purpose: the
     * two live in different collections, but a shared derivation would make
     * the folder {@code books} and the file {@code books} collide into one
     * hash, and a mount may well hold both.
     */
    public static String folderStateId(
            String tenantId, String projectId, String mount, @Nullable String folderInMount) {
        requireValidMountName(mount);
        String material = nullToEmpty(tenantId) + '\0'
                + nullToEmpty(projectId) + '\0'
                + mount + '\0'
                + normalizeInMountPath(folderInMount);
        return "extdir_" + sha256Hex(material).substring(0, ID_HEX_LENGTH);
    }

    /**
     * Normalise a mount-relative path: strip surrounding slashes, collapse
     * repeated ones, and refuse traversal.
     *
     * <p>Refusing {@code .} and {@code ..} rather than resolving them is the
     * fail-closed choice: this path arrives from a foreign listing, and a
     * resolved {@code ..} would silently address a document outside the
     * mount folder — where it would get a valid path and a valid derived id
     * for a mount that does not own it.
     *
     * @throws IllegalArgumentException on a {@code .} or {@code ..} segment
     */
    public static String normalizeInMountPath(@Nullable String pathInMount) {
        if (pathInMount == null || pathInMount.isBlank()) return "";
        String[] segments = pathInMount.strip().split("/");
        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "path traversal in mount path: '" + pathInMount + "'");
            }
            if (out.length() > 0) out.append('/');
            out.append(segment);
        }
        return out.toString();
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec — unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
