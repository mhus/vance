package de.mhus.vance.foot.command;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.api.command.ProcessCommandRequest;
import de.mhus.vance.api.command.ProcessCommandResponse;
import de.mhus.vance.api.thinkprocess.ProcessPauseRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.thinkprocess.ProcessStopRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.audit.ConversationAuditService;
import de.mhus.vance.foot.chat.PendingAskUserPicker;
import de.mhus.vance.foot.connection.BrainException;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ide.IdeContextBuilder;
import de.mhus.vance.foot.permission.PendingPermissionPrompt;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.BusyIndicator;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PendingLinePrompt;
import de.mhus.vance.foot.ui.PromptGate;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Single entry point for "user input" — the line either starts with a slash
 * (slash command, dispatched through {@link CommandService}) or it is chat
 * content steered to the active think-process via {@link ConnectionService}.
 *
 * <p>Both the JLine REPL ({@code ChatRepl}) and the debug REST endpoint
 * ({@code DebugRestServer}) call into this service so the two surfaces stay
 * structurally identical — every remote-control automation gets the exact
 * same behavior the human user sees in the REPL.
 *
 * <p>The {@link PromptGate} is flipped to exclusive while the input is being
 * processed, so async streaming sinks can write directly to the terminal
 * without corrupting an active prompt.
 *
 * <p><b>Attachments.</b> Files staged with {@code /attach} are uploaded as
 * project documents immediately before the steer frame goes out and ride
 * along as {@code ProcessSteerRequest.attachments}. The queue is drained
 * before the upload and a failed upload aborts the send: a broken file
 * must not silently re-attach itself to every later message, and
 * "I attached three files" / "the model saw two" is a mismatch nobody
 * notices until the answer is wrong. See {@link PendingAttachmentService}
 * and {@link AttachmentUploadService}.
 */
@Service
public class ChatInputService {

    /**
     * Default <em>idle</em> timeout for the chat round-trip to the brain.
     * Interpreted by {@link de.mhus.vance.foot.connection.ConnectionService#request}
     * as "give up if nothing inbound arrives for this long" — not as an
     * absolute wall-clock cap on the turn. Streaming frames
     * ({@code CHAT_MESSAGE_APPENDED}, {@code PROCESS_PROGRESS}, tool-result
     * pushes, PING heartbeats, …) reset the clock, so a Frankie / Marvin
     * turn that keeps producing output happily runs for as long as it
     * needs. 10 min of <em>complete silence</em> from the brain is the
     * "something is genuinely wrong" threshold; below that, the user can
     * still interrupt via {@code /stop} or {@code /pause}.
     */
    public static final Duration DEFAULT_CHAT_TIMEOUT = Duration.ofMinutes(10);

    /** Timeout for fire-and-forget pause requests. Short — pause is a side-channel. */
    public static final Duration PAUSE_TIMEOUT = Duration.ofSeconds(10);

    /** Timeout for a control-plane engine command round-trip. Short — commands are cheap. */
    public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final CommandService commandService;
    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal chatTerminal;
    private final PromptGate promptGate;
    private final BusyIndicator busyIndicator;
    private final IdeContextBuilder ideContextBuilder;
    private final PendingAskUserPicker askUserPicker;
    private final PendingPermissionPrompt pendingPermission;
    private final PendingLinePrompt pendingLine;

    /**
     * Background executor for async chat submission. Keeps the REPL
     * responsive while the brain is processing — critical for ESC-stop
     * to be interceptable while a chat-process is "thinking".
     */
    private final ExecutorService asyncExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "chat-async-submit");
                t.setDaemon(true);
                return t;
            });

    private final AutoAiService autoAi;
    private final ConversationAuditService audit;
    private final PendingAttachmentService pendingAttachments;
    private final AttachmentUploadService attachmentUpload;

    public ChatInputService(CommandService commandService,
                            ConnectionService connection,
                            SessionService sessions,
                            ChatTerminal chatTerminal,
                            PromptGate promptGate,
                            BusyIndicator busyIndicator,
                            IdeContextBuilder ideContextBuilder,
                            PendingAskUserPicker askUserPicker,
                            PendingPermissionPrompt pendingPermission,
                            PendingLinePrompt pendingLine,
                            AutoAiService autoAi,
                            ConversationAuditService audit,
                            PendingAttachmentService pendingAttachments,
                            AttachmentUploadService attachmentUpload) {
        this.commandService = commandService;
        this.connection = connection;
        this.sessions = sessions;
        this.chatTerminal = chatTerminal;
        this.promptGate = promptGate;
        this.busyIndicator = busyIndicator;
        this.ideContextBuilder = ideContextBuilder;
        this.askUserPicker = askUserPicker;
        this.pendingPermission = pendingPermission;
        this.pendingLine = pendingLine;
        this.autoAi = autoAi;
        this.audit = audit;
        this.pendingAttachments = pendingAttachments;
        this.attachmentUpload = attachmentUpload;
    }

    /**
     * Submits a raw input line. If it starts with {@code /} it is dispatched
     * as a slash command; otherwise it is treated as chat input. Mirrors the
     * REPL behavior exactly.
     */
    public InputResult submit(String line) {
        if (line == null) {
            line = "";
        }
        // An active line prompt (e.g. /login) claims input first — even a
        // blank line is a valid answer (accept default / skip), so this must
        // run before the blank-input short-circuit below.
        if (pendingLine.offerAnswer(line)) {
            return InputResult.command(line, true, null);
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return InputResult.command("", false, "blank input");
        }
        // An active sandbox prompt swallows the next line as its answer —
        // it must never reach the brain or the slash dispatcher.
        if (pendingPermission.offerAnswer(trimmed)) {
            return InputResult.command(trimmed, true, null);
        }
        promptGate.enterExclusive();
        try {
            // "//verb" is a direct engine command — checked before the
            // single-slash branch since "//" also startsWith "/".
            if (trimmed.startsWith("//")) {
                return sendEngineCommandLocked(trimmed);
            }
            if (trimmed.startsWith("/")) {
                boolean matched = commandService.execute(trimmed);
                return InputResult.command(trimmed, matched, null);
            }
            String chatText = expandPickerShortcut(trimmed);
            return sendChatLocked(chatText, DEFAULT_CHAT_TIMEOUT);
        } finally {
            promptGate.exitExclusive();
        }
    }

    /**
     * If an ASK_USER picker is active and the user typed a single
     * digit matching one of the options (1-based), replace the input
     * with the option's label so the brain sees a normal text reply.
     * Non-numeric / out-of-range / no-active-picker → input passes
     * through verbatim. The picker itself is cleared by the chat
     * message handler once the USER echo lands.
     */
    private String expandPickerShortcut(String trimmed) {
        if (!askUserPicker.hasActive()) return trimmed;
        String resolved = askUserPicker.resolveNumericPick(trimmed);
        if (resolved == null) return trimmed;
        chatTerminal.verbose("→ picker: " + trimmed + " → " + resolved);
        return resolved;
    }

    /**
     * REPL variant of {@link #submit}: dispatches commands synchronously
     * (they're cheap), but routes chat content through the async
     * executor so the REPL can return to {@code readLine} immediately.
     * That keeps ESC-stop interceptable while the chat-process is
     * "thinking" — without the async path the REPL thread is blocked
     * inside {@code connection.request} for the duration of the engine
     * turn and {@code readLine} can't fire its key bindings.
     *
     * <p>Streaming output (chat-message-appended, process-progress) is
     * unaffected — those notifications already render via
     * {@code printAbove}-based writes that respect the prompt gate.
     */
    public void submitFromRepl(String line) {
        final String raw = line == null ? "" : line;
        // Answer to an active line prompt (e.g. /login): deliver synchronously
        // on the REPL input thread (the awaiting worker is the taker) and
        // before the blank short-circuit, since blank is a valid answer.
        if (pendingLine.offerAnswer(raw)) {
            return;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return;
        // Answer to an active sandbox prompt: handle synchronously on the
        // REPL input thread. Routing it through the async chat executor
        // would deadlock — that thread is blocked inside the very chat
        // round-trip whose tool call is waiting for this answer.
        if (pendingPermission.offerAnswer(trimmed)) {
            return;
        }
        if (trimmed.startsWith("/")) {
            // Slash commands stay synchronous — they're cheap and the
            // REPL expects their feedback before the next prompt.
            submit(raw);
            return;
        }
        // Chat content → async dispatch so the REPL is free to capture ESC.
        asyncExecutor.submit(() -> submit(raw));
    }

    /**
     * ESC-triggered pause. Sends a {@code process-pause} <b>only</b> when
     * a chat turn / tool round-trip is actually in flight. A lone ESC
     * while idle — including a stray ESC byte the terminal injects on
     * focus change or sleep-wake — is a no-op: pausing an IDLE process
     * would flip it to PAUSED and mint a bogus "USER INTERRUPTED —
     * RECONSIDER" banner on the next user turn. Explicit {@code /pause}
     * ({@link #requestPause()}) stays unconditional.
     */
    public void requestPauseFromInterrupt() {
        if (!busyIndicator.isBusy()) {
            return;
        }
        requestPause();
    }

    /**
     * Fire-and-forget {@code process-pause} for everything active in
     * the bound session (chat + workers). Halts further turns; the
     * user's next typed chat message lets Arthur decide what to do
     * (resume + steer, or stop + create fresh).
     *
     * <p><b>Bypasses the chat asyncExecutor on purpose.</b> The
     * single-thread executor that handles user-typed chat content
     * may be blocked inside a synchronous {@code process-steer}
     * round-trip (waiting for the brain's reply). Queueing the pause
     * behind that would mean the pause arrives only after the very
     * turn the user wanted to interrupt has finished. We therefore
     * call {@link de.mhus.vance.foot.connection.ConnectionService#send}
     * directly from the caller thread (typically the JLine ESC widget
     * or the slash-dispatch thread) — non-blocking, fires immediately.
     * The brain's {@code ENGINE_HALT_REQUESTED} progress ping arrives
     * back as user feedback through the existing notification channel.
     */
    public void requestPause() {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            // No session bound — nothing to pause. Stay silent rather
            // than confusing the user with "pause failed: no session"
            // for a benign idle-state ESC press.
            return;
        }
        boolean sent = connection.send(de.mhus.vance.api.ws.WebSocketEnvelope.request(
                "pause_" + System.nanoTime(),
                MessageType.PROCESS_PAUSE,
                ProcessPauseRequest.builder().build()));
        if (!sent) {
            chatTerminal.error("pause failed: not connected");
            return;
        }
        // Surface a positive confirmation. Previously this path was
        // silent on success, so when the brain dropped the PAUSE
        // frame (or didn't pause Frankie for some other reason) the
        // user had no way to tell from the foot side whether the
        // intent had even left the building.
        chatTerminal.info("↳ pause requested (ESC)");
        // Drop the busy spinner immediately. The pending chat-request
        // (PROCESS_STEER) is still waiting for its reply on the
        // asyncExecutor — but from the user's POV, /pause means "I'm
        // done waiting". When the steer reply eventually arrives, the
        // exit() in sendChatLocked's finally is a no-op (caps at 0).
        busyIndicator.clear();
    }

    /**
     * Fire-and-forget {@code process-stop} broadcast for the active
     * workers in the bound session. Symmetric to {@link #requestPause()}
     * but harder: workers go to {@code CLOSED} ({@code closeReason=STOPPED})
     * instead of {@code PAUSED}. Arthur sees the resulting STOPPED
     * parent-notifications and can decide whether to spawn fresh.
     *
     * <p>Same async-bypass rationale as {@link #requestPause()} —
     * goes directly through {@code connection.send} instead of the
     * chat asyncExecutor so it isn't blocked behind a long-running
     * chat round-trip.
     */
    public void requestStop() {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            return;
        }
        boolean sent = connection.send(de.mhus.vance.api.ws.WebSocketEnvelope.request(
                "stop_" + System.nanoTime(),
                MessageType.PROCESS_STOP,
                ProcessStopRequest.builder().build()));
        if (!sent) {
            chatTerminal.error("stop failed: not connected");
            return;
        }
        // Same UX rationale as requestPause(): clear the spinner now,
        // don't keep the user staring at an animation while the
        // already-in-flight steer reply trickles in.
        busyIndicator.clear();
    }

    @PreDestroy
    void shutdown() {
        asyncExecutor.shutdown();
    }

    /**
     * Sends {@code line} as a chat message regardless of leading slash —
     * skips the command dispatcher entirely. Used by {@code POST /debug/chat}
     * so a remote driver can post content like {@code "/usr/bin/ls"} as chat
     * without it being misrouted into the slash dispatcher.
     */
    public InputResult sendChat(String line, Duration timeout) {
        return sendChat(line, timeout, false);
    }

    /**
     * Voice-aware variant of {@link #sendChat(String, Duration)}. {@code
     * voiceMode=true} flips the per-turn flag on the outbound
     * {@link ProcessSteerRequest} — the brain forwards it to the engine
     * prompt (Pebble variable {@code voiceMode}) so the LLM renders a
     * TTS-friendly answer (short prose, code fences for material that
     * must stay visible but should not be spoken). Per-message, not
     * session state; the next call without {@code voiceMode=true}
     * reverts to text-mode for that turn. See {@code
     * specification/voice-mode.md}.
     */
    public InputResult sendChat(String line, Duration timeout, boolean voiceMode) {
        if (line == null || line.isEmpty()) {
            return InputResult.chat("", false, "blank input");
        }
        promptGate.enterExclusive();
        try {
            return sendChatLocked(line, timeout, voiceMode);
        } finally {
            promptGate.exitExclusive();
        }
    }

    /** Project of the bound session — the scope attachments are uploaded into. */
    private String projectIdForAttachments() {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null || bound.projectId() == null || bound.projectId().isBlank()) {
            throw new IllegalStateException("no project bound to this session");
        }
        return bound.projectId();
    }

    private InputResult sendChatLocked(String line, Duration timeout) {
        return sendChatLocked(line, timeout, false);
    }

    private InputResult sendChatLocked(String line, Duration timeout, boolean voiceMode) {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            String msg = "No bound session — /connect, then /session-resume or /session-create.";
            chatTerminal.error(msg);
            return InputResult.chat(line, false, msg);
        }
        String process = sessions.activeProcess();
        if (process == null) {
            String msg = "No active process — /process <name> first, "
                    + "or use /process-steer <name> <message>.";
            chatTerminal.error(msg);
            return InputResult.chat(line, false, msg);
        }
        // Mark busy *around* the synchronous brain round-trip — the
        // status-bar animation polls this flag and shows the user
        // that something is in flight even while the REPL prompt is
        // back and waiting for input.
        busyIndicator.enter("chat-roundtrip");
        try {
            // Auto-AI rewriting — see planning/multi-user-sessions.md §6.
            // Strips a leading @no escape, otherwise prepends @ai when
            // auto-mode is on. Applied just before the wire-send.
            String wireLine = autoAi.apply(line);
            // Audit the user input locally at send time. The server
            // does not echo a chat-message-appended for USER turns in
            // solo sessions, so the inbound handler would never see
            // them — auditing here guarantees every user message lands
            // in the .jsonl regardless of session topology.
            audit.appendUserInput(process, wireLine, voiceMode);
            // Staged /attach files become project documents now, and the
            // turn carries their ids. Draining before the upload means a
            // failed upload does not leave the queue armed for the next
            // message — the user is told and can attach again.
            java.util.List<de.mhus.vance.api.attachment.AttachmentRef> attachments =
                    java.util.List.of();
            if (!pendingAttachments.isEmpty()) {
                java.util.List<java.nio.file.Path> files = pendingAttachments.drain();
                try {
                    attachments = attachmentUpload.upload(files, projectIdForAttachments());
                    chatTerminal.info("📎 sent " + attachments.size() + " attachment"
                            + (attachments.size() == 1 ? "" : "s") + ".");
                } catch (RuntimeException e) {
                    chatTerminal.error("Attachment upload failed: " + e.getMessage()
                            + " — message not sent.");
                    return InputResult.chat(line, false, "attachment upload failed");
                }
            }
            ProcessSteerRequest steer = ProcessSteerRequest.builder()
                    .processName(process)
                    .content(wireLine)
                    .ideContext(ideContextBuilder.buildAndConsumeForSteer().orElse(null))
                    .voiceMode(voiceMode ? Boolean.TRUE : null)
                    .attachments(attachments.isEmpty() ? null : attachments)
                    .build();
            // Chat-steer uses the streaming variant: a single engine
            // turn (Frankie, Marvin, …) can legitimately run for many
            // minutes while the brain pushes progress / tool / chat
            // frames in between. Strict request() would false-positive
            // abort. requestStreaming resets the deadline on every
            // inbound envelope and only nags ("still waiting") when
            // the brain has been completely silent for the timeout
            // window — connection drops still surface as
            // IllegalStateException via failAllPending.
            ProcessSteerResponse response = connection.requestStreaming(
                    MessageType.PROCESS_STEER,
                    steer,
                    ProcessSteerResponse.class,
                    timeout);
            chatTerminal.verbose("→ steered " + response.getProcessName()
                    + " (status=" + response.getStatus() + ")");
            return InputResult.chat(line, true, null);
        } catch (BrainException e) {
            chatTerminal.error(e.getMessage());
            return InputResult.chat(line, false, e.getMessage());
        } catch (Exception e) {
            String detail = e.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = e.getClass().getSimpleName();
            }
            String msg = "Steer failed: " + detail;
            chatTerminal.error(msg);
            return InputResult.chat(line, false, msg);
        } finally {
            busyIndicator.exit("chat-roundtrip");
        }
    }

    /**
     * Handles a {@code //verb [args]} line — a direct control-plane
     * command to the active think-process's engine. Sent synchronously
     * via {@code process-command}; the reply's outcome is rendered
     * inline. An unknown verb comes back as a defined no-op, not a hard
     * failure. Must run inside the prompt gate (called from
     * {@link #submit}). See {@code planning/engine-commands.md} §2.
     */
    private InputResult sendEngineCommandLocked(String trimmed) {
        String body = trimmed.substring(2).trim(); // strip the leading "//"
        if (body.isEmpty()) {
            chatTerminal.error("Usage: //<command> [args]");
            return InputResult.command(trimmed, false, "empty command");
        }
        int sp = indexOfWhitespace(body);
        String verb = sp < 0 ? body : body.substring(0, sp);
        String rest = sp < 0 ? "" : body.substring(sp + 1).trim();

        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            String msg = "No bound session — /connect, then /session-resume or /session-create.";
            chatTerminal.error(msg);
            return InputResult.command(trimmed, false, msg);
        }
        String process = sessions.activeProcess();
        if (process == null) {
            String msg = "No active process — /process <name> first.";
            chatTerminal.error(msg);
            return InputResult.command(trimmed, false, msg);
        }

        // v1 argument grammar: the raw remainder rides as a single
        // `text` param. A structured `key=value` grammar is an open
        // decision (planning/engine-commands.md §5).
        Map<String, Object> params = rest.isEmpty() ? Map.of() : Map.of("text", rest);
        ProcessCommandRequest req = ProcessCommandRequest.builder()
                .processName(process)
                .command(verb)
                .params(params)
                .build();

        busyIndicator.enter("engine-command");
        try {
            ProcessCommandResponse resp = connection.request(
                    MessageType.PROCESS_COMMAND, req, ProcessCommandResponse.class, COMMAND_TIMEOUT);
            renderCommandResult(resp);
            boolean ok = resp.getOutcome() == EngineCommandOutcome.OK;
            return InputResult.command(trimmed, ok, ok ? null : resp.getMessage());
        } catch (BrainException e) {
            chatTerminal.error(e.getMessage());
            return InputResult.command(trimmed, false, e.getMessage());
        } catch (Exception e) {
            String detail = e.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = e.getClass().getSimpleName();
            }
            String msg = "Command failed: " + detail;
            chatTerminal.error(msg);
            return InputResult.command(trimmed, false, msg);
        } finally {
            busyIndicator.exit("engine-command");
        }
    }

    private void renderCommandResult(ProcessCommandResponse resp) {
        String label = "// " + resp.getCommand();
        String detail = resp.getMessage() == null ? "" : ": " + resp.getMessage();
        Object value = resp.getValue();
        String valueSuffix = value == null ? "" : "  " + value;
        switch (resp.getOutcome()) {
            case OK -> chatTerminal.info(label + " → ok" + detail + valueSuffix);
            case UNKNOWN -> chatTerminal.error(label + " → unknown command" + detail);
            case ERROR -> chatTerminal.error(label + " → error" + detail);
        }
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Discriminator for which path {@link #submit(String)} took. */
    public enum InputKind { COMMAND, CHAT }

    /**
     * Result of a {@link #submit(String)} or {@link #sendChat(String, Duration)}
     * call. {@code ok} carries the routed-and-handled-cleanly bit:
     * <ul>
     *   <li>{@code COMMAND} — {@code true} if a slash command matched,
     *       {@code false} for unknown / blank.</li>
     *   <li>{@code CHAT} — {@code true} if the brain acknowledged the
     *       {@code PROCESS_STEER}, {@code false} on missing session/process
     *       or any error talking to the brain.</li>
     * </ul>
     * {@code error} is {@code null} on success and otherwise the human-
     * readable reason already shown on the terminal.
     */
    public record InputResult(InputKind kind, String line, boolean ok, @Nullable String error) {
        static InputResult command(String line, boolean matched, @Nullable String error) {
            return new InputResult(InputKind.COMMAND, line, matched, error);
        }

        static InputResult chat(String line, boolean delivered, @Nullable String error) {
            return new InputResult(InputKind.CHAT, line, delivered, error);
        }
    }
}
