/**
 * Shared web-cache infrastructure. Stores per-origin {@code llms.txt}
 * overviews so engines can prefix them onto the response of any URL
 * fetched from the same origin without paying a second HTTP round-trip
 * per call.
 *
 * <p>The cache is <b>global</b>: a {@code llms.txt} is identical for
 * every tenant that visits the same origin, so the storage key is the
 * scheme/host/port triplet alone — no {@code tenantId}.
 *
 * <p>Lifetime is enforced by a Mongo TTL index on {@code expireAt};
 * positive and negative hits carry different TTLs which the writer
 * supplies per record.
 */
@NullMarked
package de.mhus.vance.shared.web;

import org.jspecify.annotations.NullMarked;
