package de.mhus.vance.brain.chattheme;

import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the CSS stylesheet for a chat session theme — the
 * recipe-selected look of the web chat transcript.
 *
 * <p>Chat themes are a vocabulary of their own, deliberately separate
 * from the report themes of {@code report-themes.md}: a chat theme is
 * browser CSS (design tokens, dark mode, hover states), not the
 * openhtmltopdf print subset a PDF theme has to obey. What the two share
 * is the mechanics — the document cascade, the name validation, and the
 * serving pipeline (sanitize, scope) — not the files.
 *
 * <p><b>Storage & cascade.</b> A theme is one file at
 * {@code _vance/chat-themes/<name>.css}, resolved through
 * {@link DocumentService#lookupCascade(String, String, String)}
 * (project → {@code _vance} tenant → classpath), first hit wins — the
 * same mechanics as recipes, manuals and report themes. No subfolders,
 * no merge between layers; overriding means replacing the file.
 *
 * <p><b>The default is the normal state.</b> Unlike the report system
 * (where {@code default.css} is an always-loaded base layer under an
 * optional theme), a chat session resolves to exactly <b>one</b> theme:
 * the recipe's {@code webTheme} if it resolves, otherwise the theme
 * named {@code default}. The bundled
 * {@code vance-defaults/_vance/chat-themes/default.css} is authored
 * visually neutral (comments plus the design-token catalogue, no
 * rules), so a session without an explicit theme looks unchanged — and
 * {@code default} is an override point: a project or tenant layer that
 * ships its own {@code default.css} restyles every chat of that scope
 * without touching a single recipe.
 *
 * <p><b>Failure policy is fail-open throughout.</b> A blank, invalid
 * (failed {@link #THEME_NAME}) or unresolvable theme name logs a WARN
 * and falls back to {@code default}. A missing bundled default is an
 * internal misconfiguration (the file ships with the brain) and
 * surfaces as an empty stylesheet plus a WARN — a styling problem never
 * blocks a chat render.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatThemeResolver {

    /** Path prefix for chat theme files inside the document cascade. */
    static final String THEME_PATH_PREFIX = "_vance/chat-themes/";

    /**
     * The theme name every fallback converges on: recipes without
     * {@code webTheme}, invalid names and unresolvable names all resolve
     * to this. Overridable through the cascade like any other theme.
     */
    static final String DEFAULT_THEME_NAME = "default";

    /**
     * Theme name validator — lowercase letters, digits, hyphen.
     * Deliberately forbids slashes, dots and anything that could
     * traverse or escape the {@code _vance/chat-themes/} folder. The
     * cascade path is built by this resolver, never from raw input.
     * Unlike the report system (which treats an invalid name as a
     * per-render WARN and skips the layer), an invalid recipe value
     * fails the recipe load — here the same regex runs against
     * request-path input and degrades to the default theme, because a
     * styling problem must not turn into an HTTP error the client
     * would surface.
     */
    static final Pattern THEME_NAME = Pattern.compile("[a-z0-9-]+");

    private final DocumentService documentService;
    private final RecipeLoader recipeLoader;

    /**
     * The theme name a web chat session should fetch, derived from the
     * recipe its chat process runs. This is the one place the recipe
     * layer and the chat-theme layer meet: the recipe says
     * {@code webTheme: <name>}, and the client needs a single name to
     * call the chat-theme endpoint with.
     *
     * <p>Fail-open like everything here: no recipe (engine-default or
     * bundled-config chat process), a recipe without
     * {@code webTheme:}, or a recipe that no longer loads — all answer
     * {@code default}. A session list or bootstrap must never break
     * over a styling question.
     *
     * @param tenantId   caller's tenant, for the recipe cascade
     * @param projectId  the session's project, for the recipe cascade
     * @param recipeName recipe name of the session's chat process
     *                   ({@code ThinkProcessDocument.recipeName}); may be
     *                   {@code null} for engine-default processes
     * @return the theme name to fetch — never {@code null} or blank;
     *         {@code default} when nothing more specific applies
     */
    public String effectiveThemeName(String tenantId, String projectId, @Nullable String recipeName) {
        if (recipeName == null || recipeName.isBlank()) {
            return DEFAULT_THEME_NAME;
        }
        String theme;
        try {
            theme = recipeLoader
                    .load(tenantId, projectId, recipeName)
                    .map(ResolvedRecipe::webTheme)
                    .orElse(null);
        } catch (RuntimeException e) {
            // The recipe loaded fine when the process was spawned;
            // whatever corrupted it since must not take the session
            // list or the bootstrap down with it. A styling question
            // degrades, it does not fail.
            log.warn(
                    "Could not load recipe '{}' to resolve its webTheme — " + "using the default chat theme. ({})",
                    recipeName,
                    e.toString());
            return DEFAULT_THEME_NAME;
        }
        if (theme == null || theme.isBlank()) {
            return DEFAULT_THEME_NAME;
        }
        return theme;
    }

    /**
     * Assembles the raw (unsanitized, unscoped) CSS for a chat theme
     * name. Callers must pass the result through
     * {@code CssSanitizer.sanitize(...)} and
     * {@code CssScopePrefixer.scope(..., ".chat-theme")} before it
     * reaches a client — this resolver only resolves content, it does
     * not prepare it for a browser.
     *
     * @param tenantId  caller's tenant, for the cascade lookup. Must not
     *                  be blank.
     * @param projectId active project, for the cascade lookup (the
     *                  innermost layer). Must not be blank.
     * @param themeName theme name from the recipe's {@code webTheme}
     *                  field or the request path. Blank, invalid or
     *                  unresolvable names fall back to
     *                  {@code default} (with a WARN for the two
     *                  failure cases).
     * @return the theme CSS, never {@code null}; empty only when even
     *         the bundled default is missing (internal error, WARN
     *         logged)
     */
    public String resolveStylesheet(String tenantId, String projectId, @Nullable String themeName) {
        String name = normalize(themeName);

        Optional<String> css = lookupTheme(tenantId, projectId, name);
        if (css.isPresent()) return css.get();

        if (!DEFAULT_THEME_NAME.equals(name)) {
            log.warn(
                    "Chat theme '{}' not found at '{}{}' in any cascade layer "
                            + "(project → _vance → classpath) — falling back to the default theme.",
                    name,
                    THEME_PATH_PREFIX,
                    name + ".css");
            css = lookupTheme(tenantId, projectId, DEFAULT_THEME_NAME);
            if (css.isPresent()) return css.get();
        }

        // Only reachable when the bundled default itself is gone — the
        // file ships with the brain, so this is an internal
        // misconfiguration. Serve empty CSS rather than failing the
        // request: the chat renders unstyled, it does not break.
        log.warn(
                "Bundled default chat theme not found at classpath:vance-defaults/{}{}.css "
                        + "— chat will render without a theme. This is an internal misconfiguration.",
                THEME_PATH_PREFIX,
                DEFAULT_THEME_NAME);
        return "";
    }

    /**
     * Maps a raw theme name onto a cascade-resolvable one. Blank means
     * "no preference" (the default theme); an invalid name is a WARN,
     * not an error — the default theme answers for it.
     */
    private String normalize(@Nullable String themeName) {
        if (themeName == null || themeName.isBlank()) return DEFAULT_THEME_NAME;
        String name = themeName.trim();
        if (!THEME_NAME.matcher(name).matches()) {
            log.warn("Chat theme name '{}' is invalid (must match [a-z0-9-]+) " + "— using the default theme.", name);
            return DEFAULT_THEME_NAME;
        }
        return name;
    }

    private Optional<String> lookupTheme(String tenantId, String projectId, String name) {
        String path = THEME_PATH_PREFIX + name + ".css";
        Optional<LookupResult> hit = documentService.lookupCascade(tenantId, projectId, path);
        return hit.map(LookupResult::content);
    }
}
