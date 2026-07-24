package de.mhus.vance.foot.power;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SleepInhibitorTest {

    private @org.jspecify.annotations.Nullable List<String> commandFor(String osName) {
        String prev = System.getProperty("os.name");
        System.setProperty("os.name", osName);
        try {
            return SleepInhibitor.command(4242L);
        } finally {
            if (prev != null) System.setProperty("os.name", prev);
        }
    }

    @Test
    void command_onMac_usesCaffeinatePinnedToPid() {
        List<String> cmd = commandFor("Mac OS X");
        assertThat(cmd).containsExactly("caffeinate", "-i", "-w", "4242");
    }

    @Test
    void command_onLinux_usesSystemdInhibitWatchingPid() {
        List<String> cmd = commandFor("Linux");
        assertThat(cmd)
                .startsWith("systemd-inhibit", "--what=idle:sleep")
                .contains("--mode=block")
                .containsSubsequence("tail", "--pid=4242", "-f", "/dev/null");
    }

    @Test
    void command_onWindows_usesHiddenPowershellSettingExecutionState() {
        List<String> cmd = commandFor("Windows 11");
        assertThat(cmd).startsWith("powershell");
        assertThat(cmd).containsSubsequence("-WindowStyle", "Hidden");
        assertThat(String.join(" ", cmd))
                .contains("SetThreadExecutionState(0x80000001)")
                .contains("-Id 4242");
    }

    @Test
    void command_onUnknownPlatform_returnsNull() {
        assertThat(commandFor("SunOS")).isNull();
    }

    // --- linger / trailing-edge debounce ---------------------------------

    /** A {@link SleepInhibitor.Hold} that only records acquire/release, no real process. */
    private static final class FakeHold implements SleepInhibitor.Hold {
        final AtomicInteger acquires = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        volatile boolean held;

        @Override
        public void acquire() {
            if (held) return;
            held = true;
            acquires.incrementAndGet();
        }

        @Override
        public void release() {
            if (!held) return;
            held = false;
            releases.incrementAndGet();
        }

        @Override
        public boolean isHeld() {
            return held;
        }
    }

    @Test
    void busyStart_acquiresInhibitor() {
        FakeHold hold = new FakeHold();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            SleepInhibitor guard = new SleepInhibitor(true, 10_000, hold, sched);

            guard.onBusyStart();

            assertThat(hold.held).isTrue();
            assertThat(hold.acquires).hasValue(1);
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void busyEnd_doesNotReleaseImmediately_holdsThroughTheLingerWindow() {
        FakeHold hold = new FakeHold();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            // Long linger so the release deadline cannot fire during the test.
            SleepInhibitor guard = new SleepInhibitor(true, 60_000, hold, sched);

            guard.onBusyStart();
            guard.onBusyEnd();

            assertThat(hold.held).isTrue();
            assertThat(hold.releases).hasValue(0);
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void newWorkInsideLingerWindow_cancelsRelease_noReAcquire() throws Exception {
        FakeHold hold = new FakeHold();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            SleepInhibitor guard = new SleepInhibitor(true, 60, hold, sched);

            guard.onBusyStart();
            guard.onBusyEnd();     // schedules release in 60ms
            guard.onBusyStart();   // resumes immediately — must cancel the pending release

            // Wait well past the original deadline; the release must never fire.
            Thread.sleep(200);

            assertThat(hold.held).isTrue();
            assertThat(hold.acquires).hasValue(1);   // still the same hold, no thrash
            assertThat(hold.releases).hasValue(0);
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void lingerElapsesWithoutNewWork_releasesInhibitor() throws Exception {
        FakeHold hold = new FakeHold();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            SleepInhibitor guard = new SleepInhibitor(true, 40, hold, sched);

            guard.onBusyStart();
            guard.onBusyEnd();

            awaitReleased(hold);

            assertThat(hold.held).isFalse();
            assertThat(hold.releases).hasValue(1);
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void disabled_neverAcquires() {
        FakeHold hold = new FakeHold();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            SleepInhibitor guard = new SleepInhibitor(false, 40, hold, sched);

            guard.onBusyStart();
            guard.onBusyEnd();

            assertThat(hold.held).isFalse();
            assertThat(hold.acquires).hasValue(0);
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void shutdown_releasesImmediately_andCancelsPendingLinger() {
        FakeHold hold = new FakeHold();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        SleepInhibitor guard = new SleepInhibitor(true, 60_000, hold, sched);

        guard.onBusyStart();
        guard.onBusyEnd();   // long-pending release
        guard.shutdown();

        assertThat(hold.held).isFalse();
        assertThat(hold.releases).hasValue(1);
        assertThat(sched.isShutdown()).isTrue();
    }

    private static void awaitReleased(FakeHold hold) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (hold.held && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }
}
