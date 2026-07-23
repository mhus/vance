package de.mhus.vance.api.addon;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
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
}

