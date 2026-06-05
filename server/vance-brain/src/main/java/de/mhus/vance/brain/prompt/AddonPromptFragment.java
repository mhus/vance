package de.mhus.vance.brain.prompt;

/**
 * One Pebble-template fragment shipped by an addon, scoped to a single
 * engine. Discovered by {@link AddonPromptFragmentRegistry} at boot from
 * classpath resources at
 * {@code vance-defaults/_vance/prompts/<engine>/<addon-id>.md}.
 *
 * <p>{@code template} is the raw Pebble source — Pebble caches compiled
 * templates by source string inside {@link PromptTemplateRenderer}, so
 * we hand the renderer the string each turn and let it dedupe.
 *
 * @param addonId     filename stem (without {@code .md}); convention is
 *                    the addon module's short id (e.g. {@code "calendar"},
 *                    {@code "kanban"}). Used for deterministic ordering.
 * @param engine      engine name from the parent directory, e.g.
 *                    {@code "arthur"}, {@code "eddie"}, {@code "ford"}.
 * @param sourcePath  classpath path the fragment was loaded from, kept
 *                    for diagnostic logs on a render or compile error.
 * @param template    raw Pebble template body — UTF-8 text from the file.
 */
public record AddonPromptFragment(
        String addonId, String engine, String sourcePath, String template) {
}
