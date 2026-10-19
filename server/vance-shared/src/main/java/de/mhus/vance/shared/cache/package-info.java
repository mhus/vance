/**
 * Short-lived, scoped caches that front slow lookups the call path makes often.
 *
 * <p>The two building blocks in this package share one shape:
 * a {@link java.util.concurrent.ConcurrentHashMap} keyed by an opaque
 * caller-built {@code K}, holding a value together with an expiry in
 * {@link System#nanoTime()} nanos. {@link TtlTenantCache} is the plain TTL
 * variant; {@link CronTenantCache} adds a wall-clock refresh tick, an
 * immediate reload on the write path, and idle eviction.
 *
 * <p>These caches exist because the settings cascade is up to three uncached
 * Mongo reads per call, and a chat turn with one retry paid a dozen of them
 * for two numbers that change approximately never. They are deliberately
 * <em>narrow</em>: the caller supplies both the loader and the key, and the
 * only readers are paths where a minute of staleness is acceptable. A
 * general-purpose settings cache would have to be exact on invalidation and
 * is not what lives here.
 */
@NullMarked
package de.mhus.vance.shared.cache;

import org.jspecify.annotations.NullMarked;
