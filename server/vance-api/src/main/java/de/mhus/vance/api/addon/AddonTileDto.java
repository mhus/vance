package de.mhus.vance.api.addon;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Declarative landing-tile metadata for an addon, sourced from the addon's
 * {@code META-INF/vance-addon.yaml} {@code tile:} block. Lets the Web-UI render
 * a launcher tile for the addon without loading its federation remote. The tile
 * target is derived by the host from the addon name ({@code addon.html?addon=<name>}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("addon")
public class AddonTileDto {

    /** Tile heading. */
    private String label;

    /** Optional sub-line. */
    private @Nullable String description;

    /** Minimum {@code WebUiLevel} to show the tile: {@code standard|expert|admin}. */
    private @Nullable String minLevel;
}
