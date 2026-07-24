package de.mhus.vance.brain.webdav.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.webdav.WebDavProperties;
import io.milton.http.LockResult;
import io.milton.http.LockTimeout;
import io.milton.resource.LockableResource;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Security regression (code-review-2 HIGH): {@code refresh} must only extend a
 * lock for its own token holder. Ignoring the presented token let any WRITE
 * holder refresh someone else's lock AND receive the owner's real token back,
 * defeating the exclusive-lock contract.
 */
class RedisLockManagerTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RedisLockManager manager;
    private LockableResource resource;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        WebDavProperties props = mock(WebDavProperties.class);
        when(props.getLockTimeout()).thenReturn(Duration.ofSeconds(300));
        when(props.getLockTimeoutMax()).thenReturn(Duration.ofSeconds(3600));

        manager = new RedisLockManager(redis, props);
        resource = mock(LockableResource.class);
        when(resource.getUniqueId()).thenReturn("res-1");
    }

    @Test
    void refresh_withWrongToken_failsAndDoesNotLeakOwnerToken() {
        when(ops.get(anyString())).thenReturn("T_A\nalice\n300"); // live owner lock

        LockResult result = manager.refresh("attacker-bogus", new LockTimeout(60L), resource);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getLockToken()).isNull();               // owner token not disclosed
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class)); // lock untouched
    }

    @Test
    void refresh_withOwnerToken_succeeds() {
        when(ops.get(anyString())).thenReturn("T_A\nalice\n300");

        LockResult result = manager.refresh("T_A", new LockTimeout(60L), resource);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getLockToken().tokenId).isEqualTo("T_A");
        verify(ops).set(anyString(), anyString(), any(Duration.class));
    }
}
