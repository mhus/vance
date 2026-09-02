package de.mhus.vance.api.addon;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry an addon contributes to a Cortex menu.
 *
 * <p>Declared in the addon's {@code META-INF/vance-addon.yaml} {@code menu:}
 * block, for the same reason as {@link AddonTileDto} and
 * {@link AddonProfileTabDto}: the host renders the entry from the manifest
 * alone and fetches the addon's bundle only when somebody clicks it. The
 * alternative — asking every addon at boot what it wants in the menu — would
 * mean loading every remote on every page load, which is precisely the cost
 * the lazy kind-triggered path exists to avoid, and a menu entry has no kind
 * to trigger on.
 *
 * <p><b>The price of that, stated plainly:</b> visibility has to be decidable
 * without running the addon's code, so it is expressed as data
 * ({@link #getKinds()} / {@link #getMimes()}) rather than as a predicate. The
 * same rule already governs {@code kinds:} on the addon itself.
 */
@GenerateTypeScript("addon")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddonMenuItemDto {

    /**
     * Entry id, unique within the addon. The host namespaces it with the addon
     * name, so two addons may use the same id.
     */
    private String id;

    /**
     * Which menu the entry lands in: {@code view}, {@code actions} or
     * {@code extras}. An entry naming anything else is dropped rather than
     * placed somewhere — there is no location for it, and a silent move to a
     * default menu would hide the entry from where its author looked for it.
     */
    private String slot;

    /** Menu label. An entry without one is not renderable and is dropped. */
    private String label;

    /**
     * Module Federation expose that carries the handler. Defaults to
     * {@code ./menu}. Named rather than fixed for the same reason as
     * {@link AddonProfileTabDto#getExpose()}.
     */
    private @Nullable String expose;

    /**
     * Name of the exported function the host calls, defaults to {@code run}.
     * It receives the menu context (project, active document, selection) and
     * may return a promise.
     */
    private @Nullable String handler;

    /**
     * Document kinds the entry applies to (exact match on the active
     * document's kind), or null for "does not depend on the kind".
     */
    private @Nullable List<String> kinds;

    /**
     * MIME prefixes the entry applies to (e.g. {@code text/}), matched against
     * the active document's MIME type, or null for "does not depend on it".
     *
     * <p>Declared beside {@code kinds} rather than folded into it because most
     * plain documents carry no kind at all — a Markdown file is
     * {@code text/markdown} with an empty kind, so a kind list cannot address
     * it. When both lists are declared they are <b>alternatives</b>: the entry
     * shows when the document matches either.
     */
    private @Nullable List<String> mimes;

    /**
     * Minimum {@code WebUiLevel} to show the entry:
     * {@code standard|expert|admin}. Unset means every level sees it. Same
     * knob and same meaning as on the tile and the profile tab — a clutter
     * filter, not an authorisation.
     */
    private @Nullable String minLevel;

    /**
     * Where the entry sits among the contributed ones. Lower comes first;
     * unset sorts after everything numbered, then by label. Contributed
     * entries always sit below the host's own, behind a separator.
     */
    private @Nullable Integer sortIndex;
}
