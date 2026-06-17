package de.mhus.vance.shared.document.kind;

import org.jspecify.annotations.Nullable;

/**
 * A single point on the map — either a free-form place name to be
 * resolved via geocoding, or explicit WGS84 coordinates, or both.
 *
 * <p>Resolution rules at render time:
 * <ul>
 *   <li>Both {@code lat} and {@code lon} present → use them directly,
 *       ignore {@code place}.</li>
 *   <li>Only {@code place} present → caller must geocode it before
 *       rendering. Unresolved locations are skipped (with a warning)
 *       so a single bad name does not blank the whole map.</li>
 *   <li>Neither present → invalid, dropped during parse.</li>
 * </ul>
 *
 * <p>Spec: {@code specification/doc-kind-map.md} §2.
 */
public record MapLocation(
        @Nullable String place,
        @Nullable Double lat,
        @Nullable Double lon) {

    public boolean hasCoords() {
        return lat != null && lon != null
                && Double.isFinite(lat) && Double.isFinite(lon);
    }

    public boolean hasPlace() {
        return place != null && !place.isBlank();
    }
}
