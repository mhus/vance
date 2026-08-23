package de.mhus.vance.foot.remote;

import de.mhus.vance.api.ws.LiveChannels;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.RemoteAttachRequest;
import de.mhus.vance.api.ws.RemoteClientAnnounce;
import de.mhus.vance.api.ws.RemoteClientPrompt;
import de.mhus.vance.api.ws.RemoteClientState;
import de.mhus.vance.api.ws.RemoteInputRequest;
import de.mhus.vance.api.ws.RemoteInterruptRequest;
import de.mhus.vance.api.ws.RemoteOutputBatch;
import de.mhus.vance.api.ws.RemoteOutputLine;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.command.ChatInputService;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Foot's end of the remote-control channel.
 *
 * <p>Announces this client after every WELCOME, keeps the brain-side roster
 * entry alive with a heartbeat, streams terminal lines to attached watchers,
 * and feeds inbound lines into the very same input path the JLine prompt uses
 * ({@link ChatInputService#submit}) — which is why an answer to an open prompt,
 * a slash command and a chat message all work without three protocols.
 *
 * <p>Three properties worth keeping when touching this class:
 *
 * <ul>
 *   <li><b>Nobody watching costs nothing.</b> Lines are only queued while a
 *       watcher is attached; the line listener returns immediately otherwise.</li>
 *   <li><b>Input never runs on the WS dispatch thread.</b> A chat line can
 *       block for minutes; it goes to a dedicated single-thread executor so the
 *       socket keeps reading (heartbeats, interrupts) meanwhile.</li>
 *   <li><b>Remote input is echoed locally.</b> Every accepted line is printed
 *       at the terminal with a {@code [remote]} marker. A silent second input
 *       channel into a terminal someone will read later is not acceptable.</li>
 * </ul>
 */
@Service
@Slf4j
public class RemoteControlService implements RemoteWatcherState.PromptPublisher {

    private final FootConfig config;
    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final ChatInputService input;
    private final RemoteClientIdentity identity;
    private final RemoteControlGate gate;
    private final RemoteWatcherState watchers;
    private final FootStateService state;

    private final ObjectMapper json = JsonMapper.builder().build();

    /**
     * Lines waiting to be batched. Only filled while a watcher is attached.
     * Bounded — see {@link #onLine} for why the bound is enforced by the queue
     * rather than by a size check.
     */
    private final BlockingQueue<ChatTerminal.Line> pending = new LinkedBlockingQueue<>(2000);

    /** Whether the bound above forced a drop since the last batch went out. */
    private final AtomicBoolean droppedSinceLastBatch = new AtomicBoolean();

    /** Guards against two flushes overlapping on a burst. */
    private final AtomicBoolean flushing = new AtomicBoolean();

    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService inputExecutor;
    private volatile boolean announced;

    public RemoteControlService(FootConfig config,
                                @Lazy ConnectionService connection,
                                ChatTerminal terminal,
                                @Lazy ChatInputService input,
                                RemoteClientIdentity identity,
                                RemoteControlGate gate,
                                RemoteWatcherState watchers,
                                @Lazy FootStateService state) {
        this.config = config;
        this.connection = connection;
        this.terminal = terminal;
        this.input = input;
        this.identity = identity;
        this.gate = gate;
        this.watchers = watchers;
        this.state = state;
    }

    /**
     * Wires only the leaf collaborators. The channel listener is <b>not</b>
     * registered here: {@link ConnectionService} sits upstream of this bean in
     * the wiring graph (via {@code WelcomeHandler}), so touching its lazy proxy
     * during {@code @PostConstruct} would force it into existence while it is
     * still being constructed. Registration happens on first use instead —
     * see {@link #registerChannelOnce()}.
     */
    @PostConstruct
    void start() {
        terminal.addLineListener(this::onLine);
        watchers.setPublisher(this);
    }

    private final AtomicBoolean channelRegistered = new AtomicBoolean();

    private void registerChannelOnce() {
        if (channelRegistered.compareAndSet(false, true)) {
            connection.registerChannelListener(
                    LiveChannels.CLIENTS, (channel, envelope) -> onFrame(envelope));
        }
    }

    @PreDestroy
    void stop() {
        stopSchedulers();
    }

    // ─── outbound: announce + heartbeat ─────────────────────────────────

    /**
     * Invoked by the welcome handler once a connection is up — including after
     * a reconnect that landed on a different brain pod. Re-announcing is the
     * whole recovery mechanism: the roster entry is rewritten with the new pod,
     * and the command subscription follows the (unchanged) clientId, so nothing
     * has to be re-addressed.
     */
    public void triggerAfterWelcome() {
        if (!gate.isEnabled()) {
            log.debug("remote control disabled (mode=off) — not announcing");
            return;
        }
        registerChannelOnce();
        // Watchers from the previous connection are gone with their attach;
        // keeping them would make us stream into a channel nobody reads.
        watchers.clearWatchers();
        pending.clear();
        announce();
        startSchedulers();
    }

    private void announce() {
        RemoteClientAnnounce payload = RemoteClientAnnounce.builder()
                .clientId(identity.clientId())
                .label(identity.label())
                .host(identity.host())
                .cwd(identity.cwd())
                .pid(identity.pid())
                .version(identity.version())
                .profile(config.getClient().getProfile())
                .capabilities(List.of("output", "input", "interrupt", "prompts"))
                .lastSeq(terminal.lastSeq())
                .build();
        if (send(MessageType.CLIENT_ANNOUNCE, payload)) {
            announced = true;
            log.debug("remote control announced clientId={} mode={}", identity.clientId(), gate.mode());
        }
    }

    private synchronized void startSchedulers() {
        if (scheduler != null) {
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vance-foot-remote");
            t.setDaemon(true);
            return t;
        });
        long heartbeatMs = Math.max(5_000L, config.getRemote().getHeartbeat().toMillis());
        s.scheduleWithFixedDelay(this::heartbeat, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        long flushMs = Math.max(50L, config.getRemote().getFlushInterval().toMillis());
        s.scheduleWithFixedDelay(this::flush, flushMs, flushMs, TimeUnit.MILLISECONDS);
        scheduler = s;
        inputExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vance-foot-remote-input");
            t.setDaemon(true);
            return t;
        });
    }

    private synchronized void stopSchedulers() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) s.shutdownNow();
        ExecutorService ie = inputExecutor;
        inputExecutor = null;
        if (ie != null) ie.shutdownNow();
    }

    private void heartbeat() {
        if (!gate.isEnabled() || !connection.isOpen()) {
            return;
        }
        // A heartbeat after a silent reconnect has to re-create the roster
        // entry, not just refresh a TTL that expired in the meantime.
        if (!announced) {
            announce();
            return;
        }
        send(MessageType.CLIENT_HEARTBEAT, state.snapshot());
    }

    // ─── outbound: terminal lines ───────────────────────────────────────

    private void onLine(ChatTerminal.Line line) {
        if (!watchers.hasWatchers()) {
            return;
        }
        // Bounded queue: a runaway producer cannot grow it between two flush
        // ticks, and the drop is recorded rather than left for the watcher to
        // infer. `offer` on a full queue returns false in O(1) — an unbounded
        // queue plus a size() check would be O(n) per recorded line, i.e.
        // quadratic on the terminal's own write path during exactly the burst
        // that makes it matter.
        while (!pending.offer(line)) {
            if (pending.poll() != null) {
                droppedSinceLastBatch.set(true);
            }
        }
    }

    private void flush() {
        if (pending.isEmpty() || !connection.isOpen()) {
            return;
        }
        if (!watchers.hasWatchers()) {
            pending.clear();
            return;
        }
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            int max = Math.max(1, config.getRemote().getMaxBatchLines());
            List<RemoteOutputLine> batch = new ArrayList<>(Math.min(max, pending.size()));
            ChatTerminal.Line line;
            while (batch.size() < max && (line = pending.poll()) != null) {
                batch.add(toWire(line));
            }
            if (batch.isEmpty()) {
                return;
            }
            // Report a drop instead of shipping a shorter list that reads as a
            // gapless log. The watcher shows a gap marker on this flag; making
            // it infer the hole from a seq jump would mean every consumer has
            // to implement continuity checking correctly.
            send(MessageType.CLIENT_OUTPUT, RemoteOutputBatch.builder()
                    .clientId(identity.clientId())
                    .lines(batch)
                    .truncated(droppedSinceLastBatch.getAndSet(false))
                    .build());
        } finally {
            flushing.set(false);
        }
    }

    private static RemoteOutputLine toWire(ChatTerminal.Line line) {
        return RemoteOutputLine.builder()
                .seq(line.seq())
                .timestamp(line.timestamp().toString())
                .level(line.level().name())
                .text(line.text())
                .build();
    }

    @Override
    public void publish(RemoteClientPrompt prompt) {
        send(MessageType.CLIENT_PROMPT, prompt);
    }

    // ─── inbound ────────────────────────────────────────────────────────

    private void onFrame(WebSocketEnvelope envelope) {
        String type = envelope.getType();
        if (type == null) {
            return;
        }
        switch (type) {
            case MessageType.CLIENT_ATTACH -> onAttach(convert(envelope, RemoteAttachRequest.class));
            case MessageType.CLIENT_DETACH -> onDetach(convert(envelope, RemoteAttachRequest.class));
            case MessageType.CLIENT_INPUT -> onInput(convert(envelope, RemoteInputRequest.class));
            case MessageType.CLIENT_INTERRUPT ->
                    onInterrupt(convert(envelope, RemoteInterruptRequest.class));
            default -> log.debug("remote control: unknown frame type '{}'", type);
        }
    }

    private void onAttach(@Nullable RemoteAttachRequest req) {
        if (req == null) {
            return;
        }
        String watcherId = watcherIdOf(req);
        boolean first = !watchers.hasWatchers();
        watchers.addWatcher(watcherId);
        if (first) {
            terminal.println(Verbosity.INFO, "⇄ remote control attached (%s)",
                    gate.isInputAllowed() ? "input allowed" : "read-only — /remote allow to permit input");
        }
        // Replay what the watcher missed straight from the terminal ring. The
        // gap, if any, is reported rather than hidden: a shorter list that
        // silently starts later reads as a gapless history.
        ChatTerminal.Backlog backlog =
                terminal.since(req.getSinceSeq(), Math.max(1, config.getRemote().getMaxBatchLines()));
        if (!backlog.lines().isEmpty() || backlog.truncated()) {
            send(MessageType.CLIENT_OUTPUT, RemoteOutputBatch.builder()
                    .clientId(identity.clientId())
                    .lines(backlog.lines().stream().map(RemoteControlService::toWire).toList())
                    .truncated(backlog.truncated())
                    .build());
        }
        send(MessageType.CLIENT_STATE, state.snapshot());
    }

    private void onDetach(@Nullable RemoteAttachRequest req) {
        if (req == null) {
            return;
        }
        watchers.removeWatcher(watcherIdOf(req));
        if (!watchers.hasWatchers()) {
            pending.clear();
            terminal.println(Verbosity.VERBOSE, "⇄ remote control detached");
        }
    }

    /**
     * Which watcher a frame is about. The value is stamped by the brain from
     * the watcher's connection — it cannot be derived here, because the frame
     * arrives relayed and the socket it came in on is the brain's.
     *
     * <p>It must not fall back to the {@code clientId}: that is <em>our own</em>
     * id and identical for every watcher, so two attached devices would share
     * one entry and the first detach would silence the stream for the second.
     * An unstamped frame (older brain) is treated as a single anonymous
     * watcher, which is the pre-existing behaviour and no worse than it.
     */
    private static String watcherIdOf(RemoteAttachRequest req) {
        String stamped = req.getWatcherId();
        return stamped == null || stamped.isBlank() ? "watcher:anonymous" : "watcher:" + stamped;
    }

    private void onInput(@Nullable RemoteInputRequest req) {
        if (req == null || req.getLine() == null) {
            return;
        }
        FootStateService.InputGate verdict = state.inputGate();
        if (!verdict.accepted()) {
            terminal.println(Verbosity.WARN, "⇄ remote input refused: %s", verdict.reason());
            send(MessageType.CLIENT_STATE, state.snapshot());
            return;
        }
        // Local echo first, and always: whoever reads this terminal later must
        // be able to see that the line did not come from this keyboard.
        terminal.println(Verbosity.INFO, "❯ [remote] %s", req.getLine());

        // An answer to a waiting prompt is delivered *here*, not on the input
        // executor. That executor is single-threaded and a chat submit occupies
        // it for the whole round-trip — including the tool call whose
        // permission ask is waiting for this very answer. Queueing the answer
        // behind it would deadlock until the prompt times out into a deny,
        // which is exactly the failure ChatInputService.submitFromRepl
        // documents and avoids. offerAnswer is a non-blocking queue offer, so
        // the socket dispatch thread can do it directly.
        if (input.offerToActivePrompt(req.getLine())) {
            return;
        }

        ExecutorService exec = inputExecutor;
        if (exec == null) {
            return;
        }
        exec.submit(() -> {
            try {
                input.submit(req.getLine());
            } catch (RuntimeException e) {
                terminal.println(Verbosity.WARN, "⇄ remote input failed: %s", e.toString());
            }
        });
    }

    private void onInterrupt(@Nullable RemoteInterruptRequest req) {
        if (req == null) {
            return;
        }
        FootStateService.InputGate verdict = state.inputGate();
        if (!verdict.accepted()) {
            terminal.println(Verbosity.WARN, "⇄ remote interrupt refused: %s", verdict.reason());
            return;
        }
        if (req.isHard()) {
            terminal.println(Verbosity.INFO, "⇄ [remote] stop");
            input.requestStop();
        } else {
            terminal.println(Verbosity.INFO, "⇄ [remote] pause");
            input.requestPause();
        }
    }

    private <T> @Nullable T convert(WebSocketEnvelope envelope, Class<T> type) {
        try {
            return json.convertValue(envelope.getData(), type);
        } catch (RuntimeException e) {
            log.debug("remote control: cannot decode {}: {}", type.getSimpleName(), e.toString());
            return null;
        }
    }

    // ─── /remote command surface ────────────────────────────────────────

    /** Pushes a fresh state frame — used after a local mode change. */
    public void publishState() {
        if (gate.isEnabled() && connection.isOpen()) {
            send(MessageType.CLIENT_STATE, state.snapshot());
        }
    }

    /** Announces now (after {@code /remote on}) if not already registered. */
    public void announceNow() {
        if (!gate.isEnabled()) {
            return;
        }
        registerChannelOnce();
        announce();
        startSchedulers();
    }

    /** Stops announcing and drops watchers (after {@code /remote off}). */
    public void shutdownChannel() {
        watchers.clearWatchers();
        pending.clear();
        announced = false;
        stopSchedulers();
    }

    public int watcherCount() {
        return watchers.watcherCount();
    }

    private boolean send(String type, Object payload) {
        return connection.sendOnChannel(
                LiveChannels.CLIENTS, WebSocketEnvelope.notification(type, payload));
    }
}
