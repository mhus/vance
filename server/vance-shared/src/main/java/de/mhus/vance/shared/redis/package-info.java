/**
 * Live-feature messaging: the {@link de.mhus.vance.shared.redis.VanceRedisMessagingService}
 * contract (Pub/Sub on the {@code vance:{tenant}:{channel}} topic convention, hashes and single
 * values with TTL) and its in-process implementation
 * {@link de.mhus.vance.shared.redis.LocalMessagingService}, which every single node runs on. The
 * Redis-backed implementation that spans pods lives in the cloud edition ({@code vance-ee-redis});
 * the kernel carries no Redis client.
 */
@NullMarked
package de.mhus.vance.shared.redis;

import org.jspecify.annotations.NullMarked;
