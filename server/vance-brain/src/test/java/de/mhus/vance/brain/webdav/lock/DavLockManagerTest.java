package de.mhus.vance.brain.webdav.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.webdav.WebDavProperties;
import de.mhus.vance.shared.redis.LocalMessagingService;
import io.milton.http.LockInfo;
import io.milton.http.LockResult;
import io.milton.http.LockTimeout;
import io.milton.http.LockToken;
import io.milton.resource.LockableResource;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the lock manager on the in-process messaging service. Security regression (code-review-2
 * HIGH): {@code refresh} must only extend a lock for its own token holder. Ignoring the presented
 * token let any WRITE holder refresh someone else's lock AND receive the owner's real token back,
 * defeating the exclusive-lock contract.
 */
class DavLockManagerTest {

    private DavLockManager manager;
    private LockableResource resource;

    @BeforeEach
    void setUp() {
        WebDavProperties props = mock(WebDavProperties.class);
        when(props.getLockTimeout()).thenReturn(Duration.ofSeconds(300));
        when(props.getLockTimeoutMax()).thenReturn(Duration.ofSeconds(3600));

        manager = new DavLockManager(new LocalMessagingService(), props);
        resource = mock(LockableResource.class);
        when(resource.getUniqueId()).thenReturn("res-1");
    }

    private LockToken lockAs(String user) {
        LockInfo info =
                new LockInfo(LockInfo.LockScope.EXCLUSIVE, LockInfo.LockType.WRITE, user, LockInfo.LockDepth.ZERO);
        LockResult result = manager.lock(new LockTimeout(60L), info, resource);
        assertThat(result.isSuccessful()).isTrue();
        return result.getLockToken();
    }

    @Test
    void lock_isExclusive() {
        lockAs("alice");

        LockResult second = manager.lock(new LockTimeout(60L), null, resource);

        assertThat(second.isSuccessful()).isFalse();
    }

    @Test
    void getCurrentToken_reportsTheLockOrNull() {
        assertThat(manager.getCurrentToken(resource)).isNull();

        LockToken token = lockAs("alice");

        assertThat(manager.getCurrentToken(resource).tokenId).isEqualTo(token.tokenId);
    }

    @Test
    void refresh_withWrongToken_failsAndDoesNotLeakOwnerToken() {
        LockToken owner = lockAs("alice");

        LockResult result = manager.refresh("attacker-bogus", new LockTimeout(60L), resource);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getLockToken()).isNull(); // owner token not disclosed
        assertThat(manager.getCurrentToken(resource).tokenId).isEqualTo(owner.tokenId); // lock untouched
    }

    @Test
    void refresh_withOwnerToken_succeeds() {
        LockToken owner = lockAs("alice");

        LockResult result = manager.refresh(owner.tokenId, new LockTimeout(60L), resource);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getLockToken().tokenId).isEqualTo(owner.tokenId);
    }

    @Test
    void refresh_absentKey_reEstablishesLenient() {
        // TTL-lapsed / etag-instead-of-token clients: re-establish rather than fail.
        LockResult result = manager.refresh("anything", new LockTimeout(60L), resource);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(manager.getCurrentToken(resource)).isNotNull();
    }

    @Test
    void unlock_withOwnerToken_deletesLock() {
        LockToken owner = lockAs("alice");

        manager.unlock(owner.tokenId, resource);

        assertThat(manager.getCurrentToken(resource)).isNull();
    }

    @Test
    void unlock_withWrongToken_doesNotDeleteAnotherHoldersLock() {
        LockToken owner = lockAs("alice");

        manager.unlock("attacker-bogus", resource);

        assertThat(manager.getCurrentToken(resource).tokenId).isEqualTo(owner.tokenId);
    }
}
