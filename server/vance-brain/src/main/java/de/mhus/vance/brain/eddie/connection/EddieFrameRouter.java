package de.mhus.vance.brain.eddie.connection;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.shared.thinkprocess.WorkerLinkSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Classifies incoming Working-WS frames from a worker and dispatches
 * them to the right handler:
 *
 * <ul>
 *   <li><b>Plan-mirror frames</b> ({@code todos-updated},
 *       {@code plan-proposed}, {@code process-mode-changed}) →
 *       {@link PlanFrameHandler} (wired in step 4 — plan-mirror +
 *       fusion).</li>
 *   <li><b>Voice-relevant frames</b> ({@code chat-message-appended},
 *       {@code chat-message-stream-chunk}, {@code process-event}) →
 *       {@link ChatFrameHandler} (wired in step 3c — triage +
 *       working-memory).</li>
 *   <li><b>Side-channel frames</b> ({@code process-progress}) →
 *       {@link ProgressFrameHandler} (forward with {@code forwardedBy}
 *       tag — wired alongside the frame-router).</li>
 *   <li>Anything else → ignored (welcome / pong / errors are
 *       handled internally by {@link EddieWorkerConnection}).</li>
 * </ul>
 *
 * <p>The router itself is stateless. State (the {@code WorkerLinkSnapshot}
 * being updated, Eddie's lane wakeup) is owned by the handlers. The
 * router takes a {@code WorkerLinkSnapshot} reference because every
 * incoming frame is associated with one worker connection, and most
 * handlers want it as a parameter.
 *
 * <p>v1: handler slots default to no-op so {@link EddieWorkerConnection}
 * can run without dependencies. Step 3c installs the {@code ChatFrameHandler};
 * step 4 installs the {@code PlanFrameHandler}.
 */
@Component
@Slf4j
public class EddieFrameRouter {

    private final @Nullable PlanFrameHandler planHandler;
    private final @Nullable ChatFrameHandler chatHandler;
    private final @Nullable ProgressFrameHandler progressHandler;

    /**
     * Spring constructor — handlers are optional. Step 3c registers a
     * {@link ChatFrameHandler} bean (triage + working-memory), step 4 a
     * {@link PlanFrameHandler} (plan-mirror + fusion). Until then the
     * router runs in no-op mode.
     */
    @Autowired
    public EddieFrameRouter(
            @Autowired(required = false) @Nullable PlanFrameHandler planHandler,
            @Autowired(required = false) @Nullable ChatFrameHandler chatHandler,
            @Autowired(required = false) @Nullable ProgressFrameHandler progressHandler) {
        this.planHandler = planHandler;
        this.chatHandler = chatHandler;
        this.progressHandler = progressHandler;
    }

    /** No-handler instance — the router still discards incoming frames cleanly. */
    public static EddieFrameRouter noOp() {
        return new EddieFrameRouter(null, null, null);
    }

    public void onFrame(WebSocketEnvelope envelope, WorkerLinkSnapshot link) {
        String type = envelope.getType();
        if (type == null) {
            log.debug("Eddie worker-frame without type, dropped");
            return;
        }
        switch (type) {
            case MessageType.TODOS_UPDATED,
                 MessageType.PLAN_PROPOSED,
                 MessageType.PROCESS_MODE_CHANGED -> {
                if (planHandler != null) planHandler.onPlanFrame(envelope, link);
            }
            case MessageType.CHAT_MESSAGE_APPENDED,
                 MessageType.CHAT_MESSAGE_STREAM_CHUNK -> {
                if (chatHandler != null) chatHandler.onChatFrame(envelope, link);
            }
            // process-event arrives via Mongo inbox in v1; routing it on the
            // working-WS too is a future addition. Falls through to default.
            case MessageType.PROCESS_PROGRESS -> {
                if (progressHandler != null) progressHandler.onProgressFrame(envelope, link);
            }
            default -> log.trace("Eddie worker-frame type='{}' — no handler, dropped", type);
        }
    }

    /** Plan-mirror sink — populated in step 4 (plan-mirror + fusion). */
    @FunctionalInterface
    public interface PlanFrameHandler {
        void onPlanFrame(WebSocketEnvelope envelope, WorkerLinkSnapshot link);
    }

    /** Triage / working-memory sink — populated in step 3c. */
    @FunctionalInterface
    public interface ChatFrameHandler {
        void onChatFrame(WebSocketEnvelope envelope, WorkerLinkSnapshot link);
    }

    /** Progress-side-channel sink — forwards with {@code forwardedBy} tag. */
    @FunctionalInterface
    public interface ProgressFrameHandler {
        void onProgressFrame(WebSocketEnvelope envelope, WorkerLinkSnapshot link);
    }
}
