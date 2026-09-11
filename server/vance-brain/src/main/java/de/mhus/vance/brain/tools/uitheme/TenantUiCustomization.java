package de.mhus.vance.brain.tools.uitheme;

import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.Map;

/**
 * Shared constants of the tenant-UI customization tools. The paths and
 * the logo extension priority must stay identical to
 * {@code de.mhus.vance.brain.uitheme.UiCustomizationController} — both
 * sides of the feature read and write the same documents, and a drift
 * would make the tools write files the controller never serves (or
 * vice versa).
 */
public final class TenantUiCustomization {

    /** The tenant-wide system project every tool here writes to. */
    public static final String TENANT_PROJECT = HomeBootstrapService.TENANT_PROJECT_NAME;

    /** Document path of the custom stylesheet inside {@link #TENANT_PROJECT}. */
    public static final String CUSTOM_CSS_PATH = "_vance/config/custom.css";

    /** Path prefix of the logo document inside {@link #TENANT_PROJECT}. */
    public static final String LOGO_PATH_PREFIX = "_vance/config/logo.";

    /**
     * Accepted logo MIME types and the file extension each maps to. The
     * extension set must stay identical to the controller's serving
     * priority; the map is the write-side guard that rejects anything
     * the read side would never pick up.
     */
    public static final Map<String, String> LOGO_EXTENSION_BY_MIME =
            Map.of("image/svg+xml", "svg", "image/png", "png", "image/webp", "webp", "image/jpeg", "jpg");

    private TenantUiCustomization() {}
}
