/**
 * Server-side geocoding for {@code kind: map} documents. Resolves
 * free-form place names ("Hamburg Altona") to WGS84 coordinates via
 * the public Nominatim service, caches results in-memory so repeated
 * queries don't hit the upstream API.
 *
 * <p>OSM Nominatim usage policy compliance: dedicated User-Agent,
 * one request per place, results cached indefinitely (place→coord
 * mappings don't drift in practice). Usage stays well below the
 * "absolute maximum 1 request/second" limit because typical map
 * documents have at most a handful of unique places and every
 * subsequent lookup is a cache hit across the whole brain instance.
 *
 * <p>Spec: {@code specification/doc-kind-map.md}.
 */
@NullMarked
package de.mhus.vance.brain.geocode;

import org.jspecify.annotations.NullMarked;
