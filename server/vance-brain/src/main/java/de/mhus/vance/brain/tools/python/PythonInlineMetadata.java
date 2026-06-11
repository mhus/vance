package de.mhus.vance.brain.tools.python;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal PEP 723 inline-script-metadata parser. Recognises the
 * {@code # /// script ... # ///} block at the top of a Python file
 * and extracts the {@code dependencies} array — the only field
 * Cortex's auto-install path needs.
 *
 * <p>Spec: <a href="https://peps.python.org/pep-0723/">PEP 723</a>.
 * Other declared fields ({@code requires-python}, custom labels) are
 * accepted and ignored — V1 honours only the project venv's Python
 * version. A future iteration can plug in a TOML library for the
 * full surface; for now we sidestep the dependency and pattern-match
 * what we care about.
 *
 * <p>Example block:
 * <pre>
 * # /// script
 * # dependencies = [
 * #   "requests",
 * #   "rich >= 13",
 * # ]
 * # ///
 * </pre>
 */
public final class PythonInlineMetadata {

    private PythonInlineMetadata() {}

    private static final Pattern BLOCK_START = Pattern.compile("^#\\s*///\\s*script\\s*$");
    private static final Pattern BLOCK_END = Pattern.compile("^#\\s*///\\s*$");
    /** Single-quoted or double-quoted string literal, very forgiving. */
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"'\\n]+)[\"']");

    /**
     * Return the dependencies declared in the file's PEP 723 block,
     * or an empty list when no block is present / the block declares
     * no {@code dependencies}.
     */
    public static List<String> parseDependencies(String code) {
        if (code == null || code.isEmpty()) return List.of();
        String[] lines = code.split("\\R", -1);
        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (startIdx < 0) {
                if (BLOCK_START.matcher(trimmed).matches()) startIdx = i;
            } else {
                if (BLOCK_END.matcher(trimmed).matches()) {
                    endIdx = i;
                    break;
                }
            }
        }
        if (startIdx < 0 || endIdx < 0) return List.of();

        // Strip the leading "# " / "#" comment marker per PEP 723 so
        // the remaining text is plain TOML to scan.
        StringBuilder body = new StringBuilder();
        for (int i = startIdx + 1; i < endIdx; i++) {
            String line = lines[i];
            String stripped;
            if (line.startsWith("# ")) stripped = line.substring(2);
            else if (line.startsWith("#")) stripped = line.substring(1);
            else stripped = line;
            body.append(stripped).append('\n');
        }
        return extractDependencies(body.toString());
    }

    private static List<String> extractDependencies(String toml) {
        int depsIdx = toml.indexOf("dependencies");
        if (depsIdx < 0) return List.of();
        int eqIdx = toml.indexOf('=', depsIdx);
        if (eqIdx < 0) return List.of();
        int openBracket = toml.indexOf('[', eqIdx);
        if (openBracket < 0) return List.of();
        int closeBracket = findMatchingBracket(toml, openBracket);
        if (closeBracket < 0) return List.of();

        String arrayBody = toml.substring(openBracket + 1, closeBracket);
        List<String> deps = new ArrayList<>();
        Matcher m = QUOTED.matcher(arrayBody);
        while (m.find()) {
            String dep = m.group(1).trim();
            if (!dep.isEmpty()) deps.add(dep);
        }
        return deps;
    }

    /** Returns the index of the matching {@code ]} or -1 on imbalance. */
    private static int findMatchingBracket(String s, int openIdx) {
        int depth = 1;
        for (int i = openIdx + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
