package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bridge tool: spawn a new top-level Trillian-Control session in the
 * caller's project. Returns the new sessionId; the caller (typically
 * Arthur) can hand it to the human so they can pick the session up
 * in the UI.
 *
 * <p>Not part of the default tool set — added per-recipe via
 * {@code allowedToolsAdd} for engines that should be able to delegate
 * to Trillian (e.g. Arthur).
 *
 * <p>The new session has no bound connection — it's "headless until
 * picked up". The human takes ownership by opening the session in
 * their client; standard session-bootstrap re-binds it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianSessionCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "title", Map.of(
                            "type", "string",
                            "description", "Display name for the new session "
                                    + "(optional — auto-generated if missing)."),
                    "initialMessage", Map.of(
                            "type", "string",
                            "description", "Optional first chat message to seed the "
                                    + "Trillian-Control conversation — sent as if the "
                                    + "caller typed it.")),
            "required", List.of());

    private static final String CLIENT_NAME = "trillian-bridge";
    private static final String CLIENT_VERSION = "0.1.0";

    private final SessionService sessionService;
    private final SessionChatBootstrapper chatBootstrapper;
    private final ThinkEngineService thinkEngineService;
    private final LaneScheduler laneScheduler;

    @Override
    public String name() {
        return "trillian_session_create";
    }

    @Override
    public String description() {
        return "Spawn a fresh top-level Trillian-Control session in the "
                + "current project. The session has its own paired "
                + "Trillian-User worker. The human can open the session "
                + "later via its sessionId.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null || ctx.userId() == null) {
            throw new ToolException(
                    "trillian_session_create requires a project + user scope");
        }
        String title = stringParam(params, "title", null);
        String initialMessage = stringParam(params, "initialMessage", null);
        if (title == null || title.isBlank()) {
            title = "Trillian (spawned by " + ctx.userId() + ")";
        }

        SessionDocument session = sessionService.create(
                ctx.tenantId(),
                ctx.userId(),
                ctx.projectId(),
                title,
                /*profile*/ "default",
                CLIENT_VERSION,
                CLIENT_NAME);
        log.info("trillian_session_create: spawned session id='{}' by caller userId='{}'",
                session.getSessionId(), ctx.userId());

        Optional<ThinkProcessDocument> chatProcess = chatBootstrapper.ensureChatProcess(
                session, /*parentProcessId*/ null,
                TrillianSessionBootstrapper.DEFAULT_CONTROL_RECIPE);
        if (chatProcess.isEmpty()) {
            throw new ToolException(
                    "Failed to spawn Trillian-Control chat process for session '"
                            + session.getSessionId() + "'");
        }

        ThinkProcessDocument control = chatProcess.get();

        // Optional initial-steer — fire-and-forget on the control lane.
        if (initialMessage != null && !initialMessage.isBlank()) {
            SteerMessage.UserChatInput input = new SteerMessage.UserChatInput(
                    Instant.now(),
                    /*messageId*/ null,
                    ctx.userId(),
                    initialMessage);
            try {
                laneScheduler.submit(control.getId(),
                        () -> thinkEngineService.steer(control, input));
            } catch (RuntimeException e) {
                log.warn("Initial-steer lane-submit failed for trillian session '{}': {}",
                        session.getSessionId(), e.toString());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getSessionId());
        out.put("controlProcessId", control.getId());
        out.put("controlProcessName", control.getName());
        return out;
    }

    private static @org.jspecify.annotations.Nullable String stringParam(
            @org.jspecify.annotations.Nullable Map<String, Object> params,
            String key,
            @org.jspecify.annotations.Nullable String fallback) {
        if (params == null) return fallback;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }
}
