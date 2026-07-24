package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The label-scoped stripe lock ({@link WorkspaceService#withLabelLock}) that
 * makes find-or-create on a RootDir atomic against a concurrent provision of
 * the same workspace name (the Damogran two-runs-on-virtual-threads race).
 */
class WorkspaceServiceLabelLockTest {

    private final WorkspaceService service = new WorkspaceService(
            mock(WorkspaceProperties.class),
            List.of(),
            mock(WorkspaceSnapshotRepository.class),
            mock(WorkspaceRootService.class));

    @Test
    void withLabelLock_returnsActionResult() {
        String out = service.withLabelLock("acme", "p-1", "ws", () -> "done");
        assertThat(out).isEqualTo("done");
    }

    @Test
    void withLabelLock_propagatesException() {
        assertThatThrownBy(() -> service.withLabelLock("acme", "p-1", "ws", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    void withLabelLock_sameKey_isMutuallyExclusive() throws Exception {
        CountDownLatch aInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean bEntered = new AtomicBoolean(false);

        Thread a = new Thread(() -> service.withLabelLock("acme", "p-1", "ws", () -> {
            aInside.countDown();
            await(release);
            return null;
        }));
        a.start();
        assertThat(aInside.await(2, TimeUnit.SECONDS)).isTrue(); // A holds the lock

        Thread b = new Thread(() -> service.withLabelLock("acme", "p-1", "ws", () -> {
            bEntered.set(true);
            return null;
        }));
        b.start();

        // B must NOT enter the same-key critical section while A holds it.
        Thread.sleep(150);
        assertThat(bEntered).isFalse();

        release.countDown();
        b.join(2000);
        a.join(2000);
        assertThat(bEntered).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
