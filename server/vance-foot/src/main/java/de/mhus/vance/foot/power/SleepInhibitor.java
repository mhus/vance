package de.mhus.vance.foot.power;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.ui.BusyListener;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Keeps the host machine from suspending to sleep while the brain is
 * working. Registered as a {@link BusyListener}, so it holds an OS
 * sleep-inhibitor for exactly the span the
 * {@link de.mhus.vance.foot.ui.BusyIndicator} reports work in flight
 * (chat round-trip + any async worker turns) and releases it the moment
 * things go idle again.
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
 * kill that skips {@link #shutdown()}, the machine is never left awake
 * forever by an orphaned child.
 *
 * <p>Disabled entirely via {@code vance.sleep-guard.enabled=false} or
 * on any platform whose inhibitor command is unavailable — in which
 * case start/stop are silent no-ops.
 */
@Component
@Slf4j
public class SleepInhibitor implements BusyListener {

    private final boolean enabled;
    private final long ownPid;

    /** The running inhibitor child, or {@code null} while idle. Guarded by {@code this}. */
    private @Nullable Process process;

    public SleepInhibitor(FootConfig config) {
        this.enabled = config.getSleepGuard().isEnabled();
        this.ownPid = ProcessHandle.current().pid();
    }

    @Override
    public synchronized void onBusyStart() {
        if (!enabled) return;
        if (process != null && process.isAlive()) return;
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
            log.trace("sleep-guard: acquired via {} (pid={})",
                    cmd.get(0), process.pid());
        } catch (IOException e) {
            process = null;
            log.trace("sleep-guard: failed to start {}", cmd.get(0), e);
        }
    }

    @Override
    public synchronized void onBusyEnd() {
        release();
    }

    @PreDestroy
    synchronized void shutdown() {
        release();
    }

    private synchronized void release() {
        Process p = process;
        if (p == null) return;
        process = null;
        p.destroy();
        log.trace("sleep-guard: released");
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
