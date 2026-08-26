package de.mhus.vance.api.addon;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Public read view of an addon. Returned by
 * {@code GET /face/addons}, which only ever lists enabled rows — so
 * the {@code enabled} flag does not appear on the wire (its only
 * value to the face would be "this is in the list", which is already
 * implied by inclusion).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("addon")
public class AddonDto {

    /** Stable addon name — matches the bundle's {@code vance-addon.yaml id:}. */
    private String name;

    /** Source location: either {@code bundled:<id>} or an external URL. */
    private String path;

    /**
     * Optional SHA-256 of the source {@code .vab}, format
     * {@code "sha256:<hex>"}. The face container verifies its own
     * download against this — it caches the {@code .vab} per addon
     * just like the brain does, but in its own container-local
     * cache directory.
     */
    private @Nullable String checksum;

    /**
     * Optional landing-tile metadata (from the addon's {@code vance-addon.yaml}
     * {@code tile:} block). Present only for addons that declare one; the Web-UI
     * renders a launcher tile from it, gated by the viewer's UI level.
     */
    private @Nullable AddonTileDto tile;

    /**
     * The tab this addon adds to the profile screen, or null when it adds
     * none. Declared in the manifest so the strip can be built without
     * loading remotes first.
     */
    private @Nullable AddonProfileTabDto profile;

    /**
     * Document-kind ids this addon's {@code ./register} expose contributes,
     * or null when it contributes none.
     *
     * <p>This is what lets the Web-UI stop loading every federation remote at
     * boot: knowing that {@code calendar} lives in the calendar addon is enough
     * to defer the fetch until a document of that kind is actually opened.
     *
     * <p>It is a <b>load trigger, not a matching rule</b>. The addon's own
     * {@code matches()} predicate — which may additionally test the MIME type —
     * still decides whether its entry applies once loaded. Declaring a kind the
     * addon ends up rejecting costs one round trip; omitting one it does handle
     * makes that kind unreachable.
     */
    private @Nullable List<String> kinds;

    /**
     * Load this addon's {@code ./register} expose at boot instead of when one
     * of its kinds is opened.
     *
     * <p>The escape hatch for contributions the host cannot trigger from a kind
     * id. The known case is a block-editor block: {@code registeredBlocks()} is
     * read when the Tiptap editor is <i>constructed</i>, so a registration that
     * arrives later is ignored — and the block editor is shared by six addons,
     * any of which may render a fence contributed by another. There is no
     * document kind whose opening implies "someone might use workbook's block".
     *
     * <p>Null / false is the normal case. Setting it costs one remote fetch per
     * page load, which is precisely what the lazy path exists to avoid — so it
     * wants a reason written next to it in the manifest.
     */
    private @Nullable Boolean eager;
}

