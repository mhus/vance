package de.mhus.vance.api.addon;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * An addon's contribution to the profile screen.
 *
 * <p>Declared in the addon's {@code META-INF/vance-addon.yaml}, so the
 * host can render the tab strip before loading a single remote — the same
 * reasoning as {@link AddonTileDto} for the landing page.
 *
 * <p>What belongs here is what a person owns rather than what a project
 * needs: which store account this installation is signed in to, whether
 * that account may publish. Those are properties of the user, and the
 * profile is where a user's properties live.
 */
@GenerateTypeScript("addon")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddonProfileTabDto {

    /** Tab label. An addon without one contributes no tab. */
    private String label;

    /**
     * Module Federation expose that returns the tab's component.
     *
     * <p>Defaults to {@code ./profile}. Named rather than fixed because an
     * addon may well contribute more than one surface, and a convention
     * that cannot be overridden becomes a reason to fork the convention.
     */
    private @Nullable String expose;

    /**
     * Where the tab sits in the strip. Lower comes first; unset sorts
     * after everything numbered, then by label.
     */
    private @Nullable Integer sortIndex;

    /**
     * Minimum {@code WebUiLevel} to show the tab: {@code standard|expert|admin}.
     * Unset means every level sees it.
     *
     * <p>Same knob and same meaning as {@link AddonTileDto#getMinLevel()},
     * because the two surfaces are two doors to the same addon: gating only
     * the landing tile would leave the addon's name on a screen the reader
     * has no way into. Like there, this is a clutter filter — the brain still
     * decides what the account may actually do.
     */
    private @Nullable String minLevel;
}
