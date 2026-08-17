package de.mhus.vance.brain.kit;

import java.util.regex.Pattern;

/**
 * Glob matching for kit policy rules, with deliberately different
 * semantics per namespace.
 *
 * <p><b>Document paths</b> are hierarchical and everyone knows the
 * separator, so {@code *} stops at {@code /} and {@code **} crosses it —
 * the convention every developer already carries.
 *
 * <p><b>Setting keys</b> are flat identifiers that happen to contain
 * dots. Treating {@code .} as a separator would make the obvious rule
 * {@code ai.alias.*} match {@code ai.alias.default} but not
 * {@code ai.alias.default.fast}, which is the opposite of what someone
 * writing that line means. So for keys, {@code *} simply matches
 * anything.
 */
final class KitGlob {

    private KitGlob() {}

    /** Path glob: {@code *} within a segment, {@code **} across segments. */
    static boolean matchesPath(String glob, String path) {
        return compilePath(glob).matcher(path).matches();
    }

    /** Key glob: {@code *} matches any run of characters, dots included. */
    static boolean matchesKey(String glob, String key) {
        return compileKey(glob).matcher(key).matches();
    }

    private static Pattern compilePath(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static Pattern compileKey(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
