package de.mhus.vance.foot.power;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.ui.BusyListener;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Keeps the host machine from suspending to sleep while the brain is
 * working. Registered as a {@link BusyListener}, so it holds an OS
 * sleep-inhibitor across the span the
 * {@link de.mhus.vance.foot.ui.BusyIndicator} reports work in flight
 * (chat round-trip + any async worker turns).
 *
 * <p><b>Trailing-edge debounce (linger).</b> Agent activity is bursty:
 * a chat round-trip finishes, then async worker turns fire moments
 * later, so the busy-counter flickers {@code busy → idle → busy} many
 * times in one logical session. Releasing the OS inhibitor on every
 * idle would thrash the native child process on and off — and let the
 * machine try to suspend in the gap between turns. Instead, when work
 * settles we schedule the release {@code linger} minutes out
 * (default 5, {@code vance.sleep-guard.linger}); any new work inside
 * that window cancels the pending release and reuses the still-held
 * inhibitor. Worst case the host stays awake a few extra minutes after
 * the agent is truly done, which is harmless.
 *
 * <p><b>No GUI, no synthetic input.</b> foot is a CLI tool — we never
 * open an AWT context or jiggle the mouse. Instead we spawn the
 * platform's native, headless inhibitor as a child process:
 * <ul>
 *   <li><b>macOS</b> — {@code caffeinate -i -w <foot-pid>}: prevents
 *       idle <em>system</em> sleep (display may still sleep).</li>
 *   <li><b>Linux</b> — {@code systemd-inhibit --what=idle:sleep …}
 *       holding a logind lock for as long as its child lives.</li>
 *   <li><b>Windows</b> — a hidden PowerShell that calls
 *       {@code SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)}
 *       and parks; the flag is released automatically when that thread
 *       exits.</li>
 * </ul>
 *
 * <p>Every variant is pinned to foot's own PID (via {@code -w},
 * {@code tail --pid=}, or a {@code Get-Process} watch loop), so the
 * inhibitor <em>self-terminates when foot dies</em> — even on a hard
 * kill that skips {@link #shutdown()} (and any pending linger release),
 * the machine is never left awake forever by an orphaned child.
 *
 * <p>Disabled entirely via {@code vance.sleep-guard.enabled=false} or
 * on any platform whose inhibitor command is unavailable — in which
 * case start/stop are silent no-ops.
 */
@Component
@Slf4j
public class SleepInhibitor implements BusyListener {

    private final boolean enabled;
    private final long lingerMillis;
    private final ScheduledExecutorService scheduler;

    /** The OS-level inhibitor. All access is under {@code this}. */
    private final Hold hold;

    /** A scheduled linger release awaiting its deadline, or {@code null}. Guarded by {@code this}. */
    private @Nullable ScheduledFuture<?> pendingRelease;

    /**
     * Generation counter for pending releases. Bumped whenever a release
     * is scheduled or cancelled; the scheduled task only fires if its
     * captured generation still matches, so a task that already left the
     * scheduler queue when new work arrived is a no-op instead of a race.
     * Guarded by {@code this}.
     */
    private long releaseSeq;

    @org.springframework.beans.factory.annotation.Autowired
    public SleepInhibitor(FootConfig config) {
        this.enabled = config.getSleepGuard().isEnabled();
        this.lingerMillis = config.getSleepGuard().getLinger().toMillis();
        this.hold = new ProcessHold(ProcessHandle.current().pid());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vance-sleep-guard");
            t.setDaemon(true);
            return t;
        });
    }

    /** Test seam: inject a fake {@link Hold} and a deterministic scheduler. */
    SleepInhibitor(boolean enabled, long lingerMillis, Hold hold,
                   ScheduledExecutorService scheduler) {
        this.enabled = enabled;
        this.lingerMillis = lingerMillis;
        this.hold = hold;
        this.scheduler = scheduler;
    }

    @Override
    public synchronized void onBusyStart() {
        if (!enabled) return;
        cancelPendingRelease();   // work resumed inside the linger window — keep the inhibitor
        hold.acquire();           // no-op if already held
    }

    @Override
    public synchronized void onBusyEnd() {
        if (!enabled) return;
        if (!hold.isHeld()) return;
        scheduleRelease();        // linger before actually letting go
    }

    @PreDestroy
    synchronized void shutdown() {
        cancelPendingRelease();
        hold.release();
        scheduler.shutdownNow();
    }

    private synchronized void scheduleRelease() {
        long seq = ++releaseSeq;
        if (pendingRelease != null) pendingRelease.cancel(false);
        pendingRelease = scheduler.schedule(
                () -> lingerElapsed(seq), lingerMillis, TimeUnit.MILLISECONDS);
        log.trace("sleep-guard: release scheduled in {}ms", lingerMillis);
    }

    private synchronized void lingerElapsed(long seq) {
        if (seq != releaseSeq) return;   // superseded by newer activity
        pendingRelease = null;
        hold.release();
    }

    private synchronized void cancelPendingRelease() {
        releaseSeq++;                    // invalidate any in-flight release task
        if (pendingRelease != null) {
            pendingRelease.cancel(false);
            pendingRelease = null;
        }
    }

    /**
     * The OS-level sleep inhibitor. Split out as a package-private seam
     * so the linger/debounce logic above is unit-testable without
     * spawning real {@code caffeinate}/{@code systemd-inhibit} children.
     * Implementations are always called under the {@link SleepInhibitor}
     * monitor, so they need no internal synchronization.
     */
    interface Hold {
        /** Acquire the OS inhibitor; no-op if already held. */
        void acquire();

        /** Release the OS inhibitor; no-op if not held. */
        void release();

        /** Whether the OS inhibitor is currently held. */
        boolean isHeld();
    }

    /** Default {@link Hold}: the platform-native inhibitor child process, pinned to foot's PID. */
    private static final class ProcessHold implements Hold {

        private final long ownPid;
        private @Nullable Process process;

        ProcessHold(long ownPid) {
            this.ownPid = ownPid;
        }

        @Override
        public void acquire() {
            if (isHeld()) return;
            List<String> cmd = command(ownPid);
            if (cmd == null) {
                log.trace("sleep-guard: no inhibitor available on this platform, skipping");
                return;
            }
            try {
                process = new ProcessBuilder(cmd)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                log.trace("sleep-guard: acquired via {} (pid={})", cmd.get(0), process.pid());
            } catch (IOException e) {
                process = null;
                log.trace("sleep-guard: failed to start {}", cmd.get(0), e);
            }
        }

        @Override
        public void release() {
            Process p = process;
            if (p == null) return;
            process = null;
            p.destroy();
            log.trace("sleep-guard: released");
        }

        @Override
        public boolean isHeld() {
            return process != null && process.isAlive();
        }
    }

    /**
     * Builds the platform-native inhibitor command, tied to {@code pid}
     * so it self-terminates when foot exits. Returns {@code null} on an
     * unsupported platform.
     */
    static @Nullable List<String> command(long pid) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            // -i: inhibit idle system sleep; -w: exit when the given pid exits.
            return List.of("caffeinate", "-i", "-w", Long.toString(pid));
        }
        if (os.contains("nux") || os.contains("nix")) {
            // Hold an idle:sleep lock for as long as the child lives; the
            // child ends the instant foot's pid disappears.
            return List.of("systemd-inhibit",
                    "--what=idle:sleep",
                    "--who=vance-foot",
                    "--why=Vance agent is working",
                    "--mode=block",
                    "tail", "--pid=" + pid, "-f", "/dev/null");
        }
        if (os.contains("win")) {
            // Set the ES_SYSTEM_REQUIRED flag (kernel resets it when this
            // thread exits), then park until foot's pid is gone. Hidden
            // window, no console UI.
            String ps = "$s=Add-Type -MemberDefinition '"
                    + "[DllImport(\"kernel32.dll\")] public static extern uint "
                    + "SetThreadExecutionState(uint e);' -Name P -Namespace W -PassThru;"
                    + "$s::SetThreadExecutionState(0x80000001);"
                    + "while(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue)"
                    + "{Start-Sleep -Seconds 5}";
            return List.of("powershell", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-Command", ps);
        }
        return null;
    }
}
