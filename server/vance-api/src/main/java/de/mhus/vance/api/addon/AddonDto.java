package de.mhus.vance.api.addon;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
