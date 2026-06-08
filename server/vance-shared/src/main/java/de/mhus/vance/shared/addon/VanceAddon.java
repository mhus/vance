package de.mhus.vance.shared.addon;

import org.jspecify.annotations.Nullable;

/**
 * Marker contract every Brain addon implements as a Spring bean.
 * Lets the Insights tab list addons by Spring-registration truth
 * (rather than just by the entrypoint's {@code .ready} marker on
 * disk), and gives each addon a place to report a free-form runtime
 * status string for diagnostics.
 *
 * <p>Conventionally exposed by a {@code @Component <Name>AddonMeta}
 * class colocated with the {@code @AutoConfiguration} entrypoint.
 * Keeping it separate from the AutoConfiguration class means
 * test setups can stub it without dragging the full ComponentScan.
 *
 * <p>Spec: {@code specification/addon-system.md}.
 */
public interface VanceAddon {

    /**
     * Stable addon name — must match the {@code id:} field in
     * {@code META-INF/vance-addon.yaml} and the {@code db.addons.name}
     * row. The Insights aggregator joins on this.
     */
    String id();

    /** Human-readable label for the UI. Defaults to {@link #id()}. */
    default String displayName() {
        return id();
    }

    /**
     * Free-form runtime status. {@code null} means "nothing to report"
     * (the Insights row stays clean). Non-null is shown verbatim to
     * the admin — typically a short hint like
     * {@code "Rserve running on port 6311"} or an error like
     * {@code "R is not on PATH"}. There is no enum or severity level
     * in v1; the text alone is the signal.
     */
    default @Nullable String status() {
        return null;
    }
}
