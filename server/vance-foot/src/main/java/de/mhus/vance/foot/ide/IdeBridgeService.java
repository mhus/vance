package de.mhus.vance.foot.ide;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.WindowTitleService;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Owns the lifecycle of the Claude Code IDE plugin bridge. Disabled by
 * default; turned on by the {@code --intellij-claude} option on
 * {@code vance-foot chat} via {@link FootConfig.Claude#setEnabled(boolean)}.
 *
 * <p>When enabled, {@link #start(Path)} runs a single-threaded scheduler
 * that connects on demand and reconnects with a fixed 5 s back-off whenever
 * the WebSocket drops. Reconnect cadence is intentionally slower than
 * Claude's 1 s poll (planning §5 tip 10) — Foot users are interactive,
 * a few seconds latency on plugin restart is fine.
 */
@Service
@Slf4j
public class IdeBridgeService {

    private static final long RECONNECT_INTERVAL_SECONDS = 5L;

    private final FootConfig config;
    private final IdeNotificationDispatcher dispatcher;
    private final ObjectProvider<ChatTerminal> terminalProvider;
    private final WindowTitleService windowTitle;

    private final AtomicReference<@Nullable IdeMcpClient> clientRef = new AtomicReference<>();
    private final AtomicReference<@Nullable IdeLockfile> activeLockfile = new AtomicReference<>();
    private final AtomicReference<@Nullable ScheduledExecutorService> schedulerRef = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<Path> cwdRef = new AtomicReference<>(Paths.get("."));
    private final AtomicBoolean searchAnnounced = new AtomicBoolean(false);

    public IdeBridgeService(FootConfig config,
                            IdeNotificationDispatcher dispatcher,
                            ObjectProvider<ChatTerminal> terminalProvider,
                            WindowTitleService windowTitle) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.terminalProvider = terminalProvider;
        this.windowTitle = windowTitle;
    }

    /**
     * Starts the discovery/reconnect loop. No-op when the IDE bridge is
     * disabled in config or {@link #start(Path)} was already called.
     *
     * @param cwd working directory to match against lockfile workspace
     *            folders
     */
    public void start(Path cwd) {
        if (!config.getIde().getClaude().isEnabled()) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        cwdRef.set(cwd.toAbsolutePath());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vance-foot-ide-bridge");
            t.setDaemon(true);
            return t;
        });
        schedulerRef.set(scheduler);
        scheduler.scheduleWithFixedDelay(this::tick, 0, RECONNECT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        ScheduledExecutorService scheduler = schedulerRef.getAndSet(null);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        IdeMcpClient client = clientRef.getAndSet(null);
        if (client != null) {
            client.close("foot shutdown");
            dispatcher.notifyConnectionState(false);
        }
        activeLockfile.set(null);
        windowTitle.setIdeAttached(false);
    }

    public boolean isConnected() {
        IdeMcpClient client = clientRef.get();
        return client != null && client.isOpen();
    }

    /** The lockfile we are currently connected to, or {@code null} when not connected. */
    public Optional<IdeLockfile> activeLockfile() {
        return Optional.ofNullable(activeLockfile.get());
    }

    /** A typed tools wrapper bound to the active client; empty when not connected. */
    public Optional<IdeTools> tools() {
        IdeMcpClient client = clientRef.get();
        if (client == null || !client.isOpen()) {
            return Optional.empty();
        }
        return Optional.of(new IdeTools(client));
    }

    private void tick() {
        IdeMcpClient client = clientRef.get();
        if (client != null && client.isOpen()) {
            return;
        }
        if (client != null) {
            // stale reference — drop and notify before reconnecting
            clientRef.set(null);
            activeLockfile.set(null);
            dispatcher.notifyConnectionState(false);
            windowTitle.setIdeAttached(false);
            terminalInfo("IDE bridge: disconnected — retrying every "
                    + RECONNECT_INTERVAL_SECONDS + "s");
        }
        try {
            connectOnce();
        } catch (Exception e) {
            log.debug("IDE bridge connect attempt failed: {}", e.toString());
        }
    }

    private void connectOnce() throws Exception {
        IdeLockfileReader reader = IdeLockfileReader.ofDefaultDirectory();
        Optional<IdeLockfile> lockfile = reader.pickFor(
                cwdRef.get(),
                System.getenv("CLAUDE_CODE_SSE_PORT"));
        if (lockfile.isEmpty()) {
            if (searchAnnounced.compareAndSet(false, true)) {
                terminalInfo("IDE bridge: no Claude Code IDE plugin lockfile matches cwd "
                        + cwdRef.get() + " — will keep checking every "
                        + RECONNECT_INTERVAL_SECONDS + "s");
            }
            return;
        }
        IdeLockfile lf = lockfile.get();
        URI uri = URI.create("ws://127.0.0.1:" + lf.port());

        IdeMcpClient candidate = new IdeMcpClient(
                uri,
                lf.authToken(),
                ProcessHandle.current().pid(),
                "vance-foot",
                config.getClient().getVersion(),
                dispatcher::dispatch);
        candidate.connect();
        clientRef.set(candidate);
        activeLockfile.set(lf);
        searchAnnounced.set(false);
        log.info("IDE bridge connected to {} ({}, port {})",
                lf.ideName() == null ? "IDE plugin" : lf.ideName(),
                lf.workspaceFolders(),
                lf.port());
        terminalInfo("IDE bridge: connected to "
                + (lf.ideName() == null ? "IDE plugin" : lf.ideName())
                + " (port " + lf.port() + ", workspace " + lf.workspaceFolders() + ")");
        dispatcher.notifyConnectionState(true);
        windowTitle.setIdeAttached(true);
    }

    private void terminalInfo(String message) {
        ChatTerminal terminal = terminalProvider.getIfAvailable();
        if (terminal != null) {
            terminal.info(message);
        }
    }
}
