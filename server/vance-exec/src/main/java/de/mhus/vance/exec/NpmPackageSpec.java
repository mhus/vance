package de.mhus.vance.exec;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A registry package spec ({@code name} or {@code name@range}) checked before it
 * reaches {@code npm}. npm accepts far more than registry names — {@code file:}
 * paths and bare paths copy host files into {@code node_modules}, {@code git+…}
 * and tarball URLs fetch from anywhere, {@code npm:} aliases rename packages, and a
 * leading {@code -} is read as an option ({@code planning/multi-tenant-isolation.md}
 * G14/O8). Only the registry form passes here.
 *
 * <p>Name grammar follows npm's rules for new packages: lowercase, optional
 * {@code @scope/}, URL-safe characters, at most 214 characters. The range is a
 * semver range or dist-tag; it may not contain {@code :} or {@code /}, which rules
 * out every protocol and path form.
 */
public record NpmPackageSpec(String name, @Nullable String range) {

    private static final int MAX_NAME_LENGTH = 214;
    private static final Pattern NAME = Pattern.compile("(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*");
    private static final Pattern RANGE = Pattern.compile("[0-9A-Za-z.^~<>=|*+\\- ]+");

    /**
     * Parses and validates {@code raw}.
     *
     * @throws RootDirException when {@code raw} is not a registry spec
     */
    public static NpmPackageSpec parse(String raw) {
        String spec = raw.trim();
        // The version separator is the first '@' after a leading scope marker.
        int at = spec.indexOf('@', spec.startsWith("@") ? 1 : 0);
        String name = at < 0 ? spec : spec.substring(0, at);
        String range = at < 0 ? null : spec.substring(at + 1).trim();
        if (name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
            throw new RootDirException("invalid npm package spec '" + raw
                    + "' — only registry packages are allowed: name or name@range"
                    + " (no file:, git, URL, path or npm: alias)");
        }
        if (range != null && (range.isEmpty() || !RANGE.matcher(range).matches())) {
            throw new RootDirException(
                    "invalid version range in npm package spec '" + raw + "' — expected a semver range or dist-tag");
        }
        return new NpmPackageSpec(name, range);
    }

    /** The spec as one npm argv element. */
    public String toArgument() {
        return range == null ? name : name + "@" + range;
    }
}
