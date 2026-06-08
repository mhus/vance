package de.mhus.vance.addon.brain.rlang;

import de.mhus.vance.toolpack.ToolException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lazy lifecycle manager for the Rserve daemon. Spawns
 * {@code R CMD Rserve --no-save --RS-conf /dev/null --RS-port <port>}
 * on the first {@link #ensureRunning()} call when no daemon answers
 * yet, pumps its stdout/stderr into a dedicated SLF4J logger, and
 * shuts it down on bean destruction.
 *
 * <p>Why lazy: Eddie/Arthur sessions that never touch R should not
 * pay the ~80 MB R-interpreter RAM. Configuration errors (R not on
 * PATH, port collision) surface at the use point with a clean
 * {@link ToolException} instead of polluting the boot log.
 *
 * <p>Concurrency: {@link #ensureRunning()} is {@code synchronized}.
 * Hot-path is "probe succeeds, return" — only the cold-start path
 * builds a process. Inside the lock we double-check via
 * {@link RserveHealth#probe()} so a parallel thread that lost the
 * race doesn't try to start a second daemon.
 *
 * <p>Crash recovery: if the daemon dies between calls (port-bind
 * survives because the kernel released it), the next
 * {@code ensureRunning()} probes, sees "not reachable", and respawns.
 * Zombie cases after {@code kill -9} of the JVM are an op-side
 * concern — the next Brain boot probes, finds the old daemon
 * listening, and reuses it (R version mismatch detection is a v2
 * concern, not v1).
 */
@Component
@Slf4j
public class RserveDaemonManager {

    /** Logger receiving Rserve stdout/stderr line-by-line. */
    private static final Logger RSERVE_LOG = LoggerFactory.getLogger(
            "de.mhus.vance.addon.brain.rlang.rserve");

    /** Poll interval while waiting for the daemon to come online. */
    private static final long POLL_INTERVAL_MS = 200L;

    private final RserveProperties props;
    private final RserveHealth health;

    private final Object lock = new Object();
    private @Nullable Process process;
    private @Nullable Thread pumpThread;

    public RserveDaemonManager(RserveProperties props, RserveHealth health) {
        this.props = props;
        this.health = health;
    }

    @PostConstruct
    void announce() {
        if (!props.isEnabled()) {
            log.info("rlang addon: vance.rserve.enabled=false — r_script tool will refuse calls");
            return;
        }
        if (!props.isAutostart() || !isLoopback(props.getHost())) {
            log.info("rlang addon active; daemon expected to be reachable at {}:{} (autostart={})",
                    props.getHost(), props.getPort(), props.isAutostart());
        } else {
            log.info("rlang addon active; Rserve daemon will start on first tool call ({}:{})",
                    props.getHost(), props.getPort());
        }
    }

    /**
     * Ensure an Rserve daemon is reachable on the configured host/port.
     * Returns normally when reachable, throws {@link ToolException} with
     * a user-meaningful message otherwise.
     */
    public void ensureRunning() {
        if (!props.isEnabled()) {
            throw new ToolException(
                    "r_script disabled (vance.rserve.enabled=false)");
        }

        // Fast path — daemon already up.
        if (health.isReachable()) {
            return;
        }

        synchronized (lock) {
            // Re-probe inside the lock: a parallel caller may have
            // started the daemon while we were waiting.
            if (health.isReachable()) {
                return;
            }

            // We can only spawn a local daemon. If host is remote
            // and unreachable, fail with a clear hint.
            if (!props.isAutostart()) {
                throw new ToolException(
                        "Rserve not reachable at " + props.getHost() + ":" + props.getPort()
                                + " and vance.rserve.autostart=false. "
                                + "Start the daemon externally or enable autostart.");
            }
            if (!isLoopback(props.getHost())) {
                throw new ToolException(
                        "Rserve not reachable at " + props.getHost() + ":" + props.getPort()
                                + " and host is not loopback — cannot autostart a remote daemon.");
            }

            // If we previously spawned a process and it has died,
            // clear the reference so we spawn afresh.
            if (process != null && !process.isAlive()) {
                log.warn("Previously spawned Rserve died (exitValue={}); respawning",
                        safeExitValue(process));
                process = null;
            }

            startDaemon();
            waitForReachable();
        }
    }

    private void startDaemon() {
        log.info("Starting Rserve daemon on port {}…", props.getPort());
        ProcessBuilder pb = new ProcessBuilder(List.of(
                "R", "CMD", "Rserve",
                "--no-save",
                "--RS-conf", "/dev/null",
                "--RS-port", String.valueOf(props.getPort())))
                .redirectErrorStream(true);
        try {
            process = pb.start();
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("cannot run program") || msg.contains("no such file")) {
                throw new ToolException(
                        "Cannot start Rserve: R is not on PATH. Install R "
                                + "(macOS: `brew install r`; Linux: apt-get install r-base) "
                                + "and run `R -e \"install.packages('Rserve')\"` once.");
            }
            throw new ToolException("Cannot start Rserve: " + e.getMessage());
        }

        pumpThread = new Thread(
                () -> pumpOutput(process.getInputStream()),
                "rserve-output");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    private void waitForReachable() {
        long deadline = System.currentTimeMillis()
                + TimeUnit.SECONDS.toMillis(props.getStartupTimeoutSec());
        while (System.currentTimeMillis() < deadline) {
            // If the process died during startup, surface that
            // immediately rather than waiting for the timeout.
            if (process != null && !process.isAlive()) {
                int exit = safeExitValue(process);
                process = null;
                throw new ToolException(
                        "Rserve startup failed (process exited with " + exit
                                + "). Check that the Rserve R package is installed "
                                + "(`R -e \"install.packages('Rserve')\"`) and that "
                                + "port " + props.getPort() + " is free.");
            }
            if (health.isReachable()) {
                log.info("Rserve daemon ready on port {}", props.getPort());
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolException("Interrupted while waiting for Rserve startup");
            }
        }
        // Timeout — kill the spawn so we don't leave it dangling.
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        throw new ToolException(
                "Rserve did not become reachable on port " + props.getPort()
                        + " within " + props.getStartupTimeoutSec() + "s");
    }

    private static void pumpOutput(InputStream in) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                RSERVE_LOG.info(line);
            }
        } catch (IOException e) {
            log.debug("Rserve output pump ended: {}", e.getMessage());
        }
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private static int safeExitValue(Process p) {
        try { return p.exitValue(); } catch (IllegalThreadStateException e) { return Integer.MIN_VALUE; }
    }

    @PreDestroy
    void stop() {
        synchronized (lock) {
            if (process == null) return;
            if (process.isAlive()) {
                log.info("Stopping Rserve daemon (pid={})",
                        process.toHandle().pid());
                process.destroy();
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        log.warn("Rserve did not stop in 2s — destroyForcibly");
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            process = null;
        }
    }
}
