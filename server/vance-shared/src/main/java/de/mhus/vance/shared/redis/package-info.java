/**
 * Shared Redis infrastructure for cross-pod messaging.
 *
 * <p>Three classes:
 * <ul>
 *   <li>{@link de.mhus.vance.shared.redis.VanceRedisProperties} — config
 *       binding on {@code vance.redis.*} plus a convenience
 *       {@code VANCE_REDIS_URL} parser.</li>
 *   <li>{@link de.mhus.vance.shared.redis.VanceRedisConfig} — Spring Beans
 *       for {@code LettuceConnectionFactory}, {@code StringRedisTemplate}
 *       and {@code RedisMessageListenerContainer}.</li>
 *   <li>{@link de.mhus.vance.shared.redis.VanceRedisMessagingService} —
 *       Pub/Sub wrapper with the
 *       {@code vance:{tenant}:{channel}} (or {@code vance:global:{channel}})
 *       topic convention. The single shared bus for cross-pod
 *       fan-out — document presence, document-change events, notify
 *       cross-session, inbox-push.</li>
 * </ul>
 *
 * <p>Ported from {@code nimbus-wb/world-shared/redis}; same pattern,
 * different topic prefix.
 */
@NullMarked
package de.mhus.vance.shared.redis;

import org.jspecify.annotations.NullMarked;
