package de.mhus.vance.api.applications;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Response of {@code GET /brain/{tenant}/applications/targets} — the places one
 * app instance offers.
 *
 * <p>An empty list is a normal answer, not an error: most apps have no places,
 * and the link then addresses the app itself. A picker must render that case
 * rather than treat it as a failure.
 */
@GenerateTypeScript("applications")
public record ApplicationTargetsResponse(
        List<ApplicationTargetDto> targets) {}
