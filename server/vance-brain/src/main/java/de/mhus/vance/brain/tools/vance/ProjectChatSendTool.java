package de.mhus.vance.brain.tools.vance;

import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.vance.activity.EntityRef;
import de.mhus.vance.brain.vance.activity.VanceActivityKind;
import de.mhus.vance.brain.vance.activity.VanceActivityService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sends a chat message to the Arthur chat-process of another project
 * — Vance's primary "tell the worker what to do" tool.
 *
 * <p>Mechanic: appends a {@code UserChatInput} to the target
 * chat-process's pending queue (atomic Mongo {@code $push}), then
 * schedules a lane turn. Pod-agnostic: in single-pod the schedule
 * fires locally; in multi-pod the {@code LaneScheduler} on the
 * hosting pod sees the change-stream and wakes the lane there.
 *
 * <p>Asynchronous — this tool returns as soon as the message is
 * enqueued. Arthur's reply (or a {@code BLOCKED} status if it gets
 * stuck) lands later as a {@code ProcessEvent} in Vance's pending
 * queue, routed by the standard {@code ParentNotificationListener}
 * because {@code project_create} set Vance as the chat-process's
 * cross-project parent.
 *
 * <p>{@code fromUser} is set to {@code "vance:<vanceProcessId>"} so
 * Arthur (and the audit trail) sees that this message originated from
 * a hub orchestrator rather than the user typing directly. Arthur
 * still treats it as a regular {@code UserChatInput}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectChatSendTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional. Defaults to the active "
                                    + "project (project_switch)."),
                    "message", Map.of(
                            "type", "string",
                            "description", "What to tell the project's Arthur. "
                                    + "Treat it like a user message — be "
                                    + "explicit, name the goal, ask for the "
                                    + "data to be included in the reply.")),
            "required", List.of("message"));

    private final VanceContext vanceContext;
    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final LaneScheduler laneScheduler;
    private final VanceActivityService activityService;

    @Override
    public String name() {
        return "project_chat_send";
    }

    @Override
    public String description() {
        return "Send a chat message to another project's Arthur "
                + "chat-process. Asynchronous: returns when enqueued; "
                + "Arthur's reply comes back later as a ProcessEvent. "
                + "Use this to steer / continue conversations with workers "
                + "you previously spawned via project_create.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("project_chat_send requires a Vance process scope");
        }
        String message = stringOrThrow(params, "message");
        ProjectDocument project = vanceContext.resolveProject(params, ctx, false);

        // Resolve the chat-process for this project. We pick the most
        // recently created session whose chatProcessId is set — that's
        // what project_create produces. If we ever support multiple
        // sessions per worker project we'll thread a sessionId param
        // through here.
        List<SessionDocument> sessions = sessionService.listForProject(
                ctx.tenantId(), project.getName());
        SessionDocument session = sessions.stream()
                .filter(s -> s.getChatProcessId() != null)
                .reduce((a, b) -> {
                    Instant ai = a.getCreatedAt();
                    Instant bi = b.getCreatedAt();
                    if (ai == null) return b;
                    if (bi == null) return a;
                    return ai.isAfter(bi) ? a : b;
                })
                .orElseThrow(() -> new ToolException(
                        "No chat-process found in project '"
                                + project.getName() + "'. Call project_create "
                                + "first."));

        ThinkProcessDocument chat = thinkProcessService.findById(session.getChatProcessId())
                .orElseThrow(() -> new ToolException(
                        "Session '" + session.getSessionId()
                                + "' references missing chat-process '"
                                + session.getChatProcessId() + "'"));

        PendingMessageDocument msg = PendingMessageDocument.builder()
                .type(PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.now())
                .fromUser("vance:" + ctx.processId())
                .content(message)
                .build();

        if (!thinkProcessService.appendPending(chat.getId(), msg)) {
            throw new ToolException(
                    "Failed to enqueue message — chat-process "
                            + chat.getId() + " disappeared");
        }
        eventEmitter.scheduleTurn(chat.getId());

        log.info("project_chat_send: tenant='{}' project='{}' chat='{}' chars={}",
                ctx.tenantId(), project.getName(), chat.getId(), message.length());

        activityService.append(
                ctx.tenantId(), ctx.userId(),
                ctx.sessionId(), ctx.processId(),
                VanceActivityKind.PROCESS_STEERED,
                "Arthur in `" + project.getName() + "` angesprochen",
                List.of(EntityRef.project(project.getName()),
                        EntityRef.process(chat.getId(), chat.getName())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        out.put("sessionId", session.getSessionId());
        out.put("chatProcessId", chat.getId());
        out.put("enqueued", true);
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s.trim();
    }
}
