package de.mhus.vance.api.geocode;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a successful geocoding lookup — WGS84 coordinates plus
 * the canonical display name returned by Nominatim ("Altona, Hamburg,
 * Germany" for a query of "Hamburg Altona"). The display name lets
 * the client show a tooltip distinguishing ambiguous matches.
 *
 * <p>Used by {@code GET /brain/{tenant}/geocode?q=...} for resolving
 * {@code place:} entries in {@code kind: map} documents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("geocode")
public class GeocodeResult {

    private double lat;
    private double lon;
    private String displayName;
}
