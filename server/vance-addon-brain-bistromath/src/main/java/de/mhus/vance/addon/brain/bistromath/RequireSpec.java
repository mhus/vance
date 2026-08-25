package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One {@code name@version} a document asks for.
 *
 * <p><b>The version is required.</b> A bare {@code db} would have to mean
 * "whatever is newest", which is a second resolution mode and a different
 * promise: an author who writes {@code db@1} is saying which API they wrote
 * against, and that is the only thing worth recording. Resolution can then
 * disagree with them out loud (see {@link RequireResolver}) instead of
 * silently.
 *
 * <p>Versions compare part-wise and numerically, so {@code 2} sorts after
 * {@code 10} would be wrong and does not happen. A non-numeric part compares as
 * text, which is a defined answer rather than a crash.
 */
public record RequireSpec(String name, String version, String raw) implements Comparable<RequireSpec> {

    /** Lowercase, so a library cannot exist twice under different casing. */
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern VERSION = Pattern.compile("^[0-9]+(\\.[0-9]+)*$");

    /**
     * @param at where this was written, for the message — a manifest, a view or
     *           the header of a script.
     * @throws ToolException when the spelling is not {@code name@version}.
     */
    public static RequireSpec parse(String raw, String at) {
        String s = raw == null ? "" : raw.trim();
        int at2 = s.indexOf('@');
        if (at2 <= 0 || at2 == s.length() - 1) {
            throw new ToolException(at + ": cannot read require `" + raw + "`. Write"
                    + " `name@version`, for example `db@1` — the version is required so that"
                    + " a conflict can be reported instead of guessed.");
        }
        String name = s.substring(0, at2).toLowerCase(java.util.Locale.ROOT);
        String version = s.substring(at2 + 1);
        if (!NAME.matcher(name).matches()) {
            throw new ToolException(at + ": `" + name + "` is not a library name."
                    + " Lowercase letters, digits and hyphens, starting with a letter.");
        }
        if (!VERSION.matcher(version).matches()) {
            throw new ToolException(at + ": `" + version + "` is not a version."
                    + " Digits, optionally dotted: `1`, `2.4`.");
        }
        return new RequireSpec(name, version, s);
    }

    /** {@code name@version} — also the file name a library is looked up under. */
    public String id() {
        return name + '@' + version;
    }

    @Override
    public int compareTo(RequireSpec other) {
        int byName = name.compareTo(other.name);
        if (byName != 0) return byName;
        return compareVersions(version, other.version);
    }

    static int compareVersions(String a, String b) {
        List<String> pa = split(a);
        List<String> pb = split(b);
        for (int i = 0; i < Math.max(pa.size(), pb.size()); i++) {
            String x = i < pa.size() ? pa.get(i) : "0";
            String y = i < pb.size() ? pb.get(i) : "0";
            int c;
            try {
                c = Long.compare(Long.parseLong(x), Long.parseLong(y));
            } catch (NumberFormatException e) {
                c = x.compareTo(y);
            }
            if (c != 0) return c;
        }
        return 0;
    }

    private static List<String> split(String v) {
        List<String> out = new ArrayList<>();
        for (String part : v.split("\\.")) out.add(part);
        return out;
    }
}
