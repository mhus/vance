package de.mhus.vance.brain.tools.budget;

/**
 * Derives the <em>family</em> a tool belongs to. The family — not the
 * single tool — is the unit the budget demotes: a half-visible pack
 * ({@code conversations_list} in the manifest, {@code conversations_history}
 * not) is worse than a fully deferred one, because the model starts the
 * job and then walks into a wall.
 *
 * <p>Two rules, both derived from the name, so no tool class and no pack
 * document has to declare anything:
 *
 * <ol>
 *   <li>Pack sub-tool ({@code <pack>__<operation>}) → the pack prefix.
 *       {@code slack_rest__users_list} → {@code slack_rest}. This is the
 *       same grouping {@code tool_list} already uses for its
 *       {@code packHints}.</li>
 *   <li>Everything else → the first {@code _}-separated segment.
 *       {@code doc_read} → {@code doc}, {@code work_file_write} →
 *       {@code work}, {@code whoami} → {@code whoami}.</li>
 * </ol>
 *
 * <p>Rule 2 is deliberately coarse. It groups {@code work_file_*} with
 * {@code work_exec_*} (both address the brain workspace) and leaves
 * single-word tools in a family of one. Operators who need a different
 * split order families explicitly via
 * {@code vance.tools.budget.family-priority} instead of getting a
 * cleverer heuristic here.
 */
public final class ToolFamily {

    /** Separator between a pack prefix and its operation name. */
    public static final String PACK_SEPARATOR = "__";

    private ToolFamily() {}

    /**
     * Family name for {@code toolName}. Never null, never blank —
     * a blank input maps to {@code "?"} so the caller can group it
     * without a null check.
     */
    public static String of(String toolName) {
        if (toolName == null || toolName.isBlank()) return "?";
        String name = toolName.trim();
        int pack = name.indexOf(PACK_SEPARATOR);
        if (pack > 0) {
            return name.substring(0, pack);
        }
        int underscore = name.indexOf('_');
        if (underscore > 0) {
            return name.substring(0, underscore);
        }
        return name;
    }

    /**
     * Is this a sub-tool of a multi-tool pack (REST/MCP/IMAP)? Pack
     * tools rank below hand-written built-ins: they arrive per connected
     * account, in bulk, and are the reason a surface overflows in the
     * first place.
     */
    public static boolean isPackTool(String toolName) {
        return toolName != null && toolName.indexOf(PACK_SEPARATOR) > 0;
    }
}
