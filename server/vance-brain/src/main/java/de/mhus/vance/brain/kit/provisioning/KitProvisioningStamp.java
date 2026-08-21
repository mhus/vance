package de.mhus.vance.brain.kit.provisioning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Folds „what the source says it would hand over" and „what we asked it
 * for" into one token, so a later check is a single comparison.
 *
 * <p><b>Why the parameters have to be in here.</b> A source declares its
 * revision on the cacheable capabilities call, which never sees the
 * params — those go to the build. So the revision alone cannot notice a
 * params change: edit {@code params:}, revision unchanged, check says
 * „nothing to do", and the new params never take effect. Silently. Folding
 * them in on this side is what makes a params change a change.
 *
 * <p><b>Null in, null out.</b> A source that cannot state a revision gets
 * no stamp and is never change-checked — which is honest. Hashing the
 * params alone would produce a token that looks answerable and is not: it
 * would go stale the moment the source changed and nobody would notice.
 */
final class KitProvisioningStamp {

    private KitProvisioningStamp() {
    }

    /**
     * Token for a desired kit, or null when the source did not state a
     * revision.
     */
    static @Nullable String of(@Nullable String revision, Map<String, Object> params) {
        if (revision == null || revision.isBlank()) return null;
        StringBuilder canonical = new StringBuilder(revision.trim());
        canonical.append(' ');
        append(canonical, params);
        return HexFormat.of().formatHex(sha256(canonical.toString()));
    }

    /**
     * Whether what a source would hand over now differs from what the
     * record remembers.
     *
     * <p><b>Unanswerable counts as „no".</b> Either side may be null — a
     * source that states no revision, or a kit installed by hand or before
     * the stamp existed. Treating that as a difference would report every
     * such kit on every tick; treating it as equality is the quiet answer
     * and the honest one, because nothing is actually known.
     *
     * <p>One method so the periodic check and the unattended update path
     * cannot drift on this: they must agree exactly, or a kit gets reported
     * and refreshed for different reasons.
     */
    static boolean differs(
            @Nullable String installedStamp,
            @Nullable String revision,
            Map<String, Object> params) {
        if (installedStamp == null || installedStamp.isBlank()) return false;
        String now = of(revision, params);
        return now != null && !now.equals(installedStamp);
    }

    /**
     * Append a value in a form that depends on content and not on map
     * ordering — YAML hands us whatever order the file had, and two
     * identical configurations written differently must not read as a
     * change.
     */
    private static void append(StringBuilder out, @Nullable Object value) {
        if (value == null) {
            out.append('~');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            for (Map.Entry<String, Object> e : stringKeyed(map).entrySet()) {
                out.append(e.getKey()).append('=');
                append(out, e.getValue());
                out.append(';');
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            // Order is kept for lists: [crm, invoicing] and [invoicing, crm]
            // are the same set but not necessarily the same instruction, and
            // guessing which would be wrong for somebody.
            out.append('[');
            for (Object element : list) {
                append(out, element);
                out.append(';');
            }
            out.append(']');
        } else {
            out.append(value);
        }
    }

    /** Sorted and string-keyed, so ordering cannot leak into the token. */
    private static Map<String, Object> stringKeyed(Map<?, ?> map) {
        Map<String, Object> out = new TreeMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to be present", e);
        }
    }
}
