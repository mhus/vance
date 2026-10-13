package de.mhus.vance.brain.discovery;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Filter a {@link CatalogSnapshot} against an engine's allow-set
 * before handing it to the discovery LLM.
 *
 * <p>Two effects:
 *
 * <ul>
 *   <li><b>Tool entries</b> ({@code requires-tools = {name}}) are
 *       dropped when {@code name} isn't in the allow-set.</li>
 *   <li><b>Manual entries</b> with a {@code requires-tools} header
 *       are dropped when <em>any</em> of the required tools isn't in
 *       the allow-set.</li>
 *   <li>Skills + manuals without {@code requires-tools} pass through
 *       unchanged.</li>
 * </ul>
 *
 * <p>A {@code null} or empty {@code allowedTools} set means "no
 * restriction" — the full catalog passes through. That matches the
 * existing {@code ContextToolsApi.allowed()} convention.
 *
 * <p>Section headers ({@code ## Manuals}, {@code ## Skills},
 * {@code ## Tools}) stay in the rendered output even when their
 * entry-list becomes empty — costs almost nothing and keeps the
 * output structurally predictable for the LLM.
 */
public final class CatalogFilter {

    private static final Pattern ENTRY_HEADER = Pattern.compile("(?m)^###\\s+(?<name>\\S+)\\s*$");

    private CatalogFilter() {}

    /**
     * Returns the markdown with entries removed whose
     * {@code requires-tools} aren't met by {@code allowedTools}.
     * Pass-through when {@code allowedTools} is {@code null} or
     * empty.
     */
    public static String filter(CatalogSnapshot snapshot, @Nullable Set<String> allowedTools) {
        if (snapshot == null) return "";
        String markdown = snapshot.markdown();
        if (allowedTools == null || allowedTools.isEmpty()) {
            return markdown;
        }
        Set<String> drop = entriesToDrop(snapshot.entries(), allowedTools);
        if (drop.isEmpty()) {
            return markdown;
        }
        return removeEntries(markdown, drop);
    }

    /**
     * Returns the names of entries that fail the allow-set check —
     * used by tests and by callers that want to inspect what got
     * filtered.
     */
    public static Set<String> entriesToDrop(Map<String, CatalogSnapshot.EntrySpec> entries, Set<String> allowedTools) {
        Set<String> drop = new HashSet<>();
        if (entries == null || entries.isEmpty()) return drop;
        for (Map.Entry<String, CatalogSnapshot.EntrySpec> e : entries.entrySet()) {
            Set<String> req = e.getValue().requiredTools();
            if (req.isEmpty()) continue;
            for (String t : req) {
                if (!allowedTools.contains(t)) {
                    drop.add(e.getKey());
                    break;
                }
            }
        }
        return drop;
    }

    /**
     * Moves the {@code ###} entries named in {@code demote} out of their
     * sections into one trailing section under {@code heading} — ranking
     * without filtering: the entries stay discoverable, they just come
     * after everything else. Pass-through when nothing matches.
     */
    public static String demoteEntries(String markdown, Set<String> demote, String heading) {
        if (markdown == null || demote == null || demote.isEmpty()) return markdown;
        String[] lines = markdown.split("\\R", -1);
        StringBuilder kept = new StringBuilder(markdown.length());
        StringBuilder moved = new StringBuilder();
        boolean moving = false;
        for (String line : lines) {
            if (line.startsWith("## ") && !line.startsWith("### ")) {
                moving = false;
            } else if (line.startsWith("### ")) {
                Matcher m = ENTRY_HEADER.matcher(line);
                moving = m.find() && demote.contains(m.group("name"));
            }
            (moving ? moved : kept).append(line).append('\n');
        }
        if (moved.isEmpty()) return markdown;
        if (kept.length() > 0 && kept.charAt(kept.length() - 1) == '\n') kept.setLength(kept.length() - 1);
        return kept + "\n\n" + heading + "\n\n" + moved.toString().strip() + "\n";
    }

    /**
     * Walks the markdown line by line, dropping any {@code ###}
     * section whose name is in {@code drop}. Sections start at
     * {@code ### <name>} and end at the next {@code ##}/{@code ###}
     * boundary (or end-of-file).
     */
    private static String removeEntries(String markdown, Set<String> drop) {
        String[] lines = markdown.split("\\R", -1);
        StringBuilder out = new StringBuilder(markdown.length());
        boolean skipping = false;
        for (String line : lines) {
            if (line.startsWith("## ") && !line.startsWith("### ")) {
                // Section boundary — never skip top-level headings.
                skipping = false;
                out.append(line).append('\n');
                continue;
            }
            if (line.startsWith("### ")) {
                Matcher m = ENTRY_HEADER.matcher(line);
                if (m.find()) {
                    skipping = drop.contains(m.group("name"));
                    if (skipping) continue;
                }
                out.append(line).append('\n');
                continue;
            }
            if (skipping) continue;
            out.append(line).append('\n');
        }
        // The split with -1 preserves trailing empties so the output
        // gains exactly one extra newline at the end — strip it back
        // for a clean round-trip when the input had a stable shape.
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }
}
