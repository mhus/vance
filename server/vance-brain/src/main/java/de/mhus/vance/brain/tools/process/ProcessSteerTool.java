package de.mhus.vance.brain.tools.process;

import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sends a chat message to another think-process in the current
 * session and waits for that process's turn to finish. Useful for
 * orchestrator-style engines that delegate work to sub-processes.
 *
 * <p>The target turn runs on its own {@link LaneScheduler} lane —
 * different from the lane the calling tool is in — so concurrent
 * cross-process steers don't deadlock. Steering the
 * <em>current</em> process (self-steer) is rejected: it would
 * enqueue behind the in-flight tool call and never run.
 *
 * <p>Returns the new chat messages produced by the target during
 * this turn (typically one ASSISTANT message).
 */
@Component
@Slf4j
public class ProcessSteerTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Target process name in the current session."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Chat message to send.")),
            "required", List.of("name", "content"));

    private final ThinkProcessService thinkProcessService;
    /** Lazy — see {@link ProcessCreateTool} for the cycle rationale. */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final ChatMessageService chatMessageService;
    private final LaneScheduler laneScheduler;
    private final ProcessEventEmitter eventEmitter;

    public ProcessSteerTool(
            ThinkProcessService thinkProcessService,
            ObjectProvider<ThinkEngineService> thinkEngineServiceProvider,
            ChatMessageService chatMessageService,
            LaneScheduler laneScheduler,
            ProcessEventEmitter eventEmitter) {
        this.thinkProcessService = thinkProcessService;
        this.thinkEngineServiceProvider = thinkEngineServiceProvider;
        this.chatMessageService = chatMessageService;
        this.laneScheduler = laneScheduler;
        this.eventEmitter = eventEmitter;
    }

    @Override
    public String name() {
        return "process_steer";
    }

    @Override
    public String description() {
        return "Send a chat message to another think-process in the current "
                + "session and wait for its turn to complete. Returns the "
                + "new chat messages produced. Cannot target the current "
                + "process (use the normal user-message path).";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String sessionId = ctx.sessionId();
        if (sessionId == null) {
            throw new ToolException("process_steer requires a session scope");
        }
        String name = stringOrThrow(params, "name");
        String content = stringOrThrow(params, "content");

        ThinkProcessDocument target = thinkProcessService
                .findByName(ctx.tenantId(), sessionId, name)
                .orElseThrow(() -> new ToolException(
                        "Process '" + name + "' not found in current session"));

        if (target.getId() != null && target.getId().equals(ctx.processId())) {
            throw new ToolException(
                    "process_steer cannot target the current process — "
                            + "self-steer would deadlock");
        }

        int beforeSize = chatMessageService.history(
                ctx.tenantId(), sessionId, target.getId()).size();

        SteerMessage.UserChatInput message = new SteerMessage.UserChatInput(
                Instant.now(),
                /*idempotencyKey*/ null,
                /*fromUser*/ ctx.processId() == null
                        ? null
                        : "process:" + ctx.processId(),
                content);

        ThinkEngine targetEngine = thinkEngineServiceProvider.getObject()
                .resolveForProcess(target);
        if (targetEngine.asyncSteer()) {
            // Async target (Marvin & co.): queue + wake without
            // blocking on the lane. The orchestrator will see the
            // outcome later via ProcessEvent.
            thinkProcessService.appendPending(target.getId(),
                    PendingMessageDocument.builder()
                            .type(PendingMessageType.USER_CHAT_INPUT)
                            .at(java.time.Instant.now())
                            .fromUser(ctx.processId() == null
                                    ? null : "process:" + ctx.processId())
                            .content(content)
                            .build());
            eventEmitter.scheduleTurn(target.getId());
            log.info("process_steer async-queued name='{}' target={} engine='{}'",
                    name, target.getId(), targetEngine.name());
        } else {
            try {
                // Different lane → no deadlock with the current tool's lane.
                laneScheduler.submit(target.getId(),
                        () -> thinkEngineServiceProvider.getObject().steer(target, message)).get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ToolException("Interrupted waiting for target steer");
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                throw new ToolException(
                        "Target steer failed: " + cause.getMessage(), cause);
            }
        }

        ThinkProcessDocument refreshed = thinkProcessService.findById(target.getId())
                .orElse(target);
        List<ChatMessageDocument> full = chatMessageService.history(
                ctx.tenantId(), sessionId, target.getId());
        List<Map<String, Object>> newMessages = new ArrayList<>();
        for (ChatMessageDocument m : full.subList(beforeSize, full.size())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", m.getRole() == null ? null : m.getRole().name().toLowerCase());
            row.put("content", m.getContent());
            newMessages.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", refreshed.getName());
        out.put("status", refreshed.getStatus() == null
                ? null : refreshed.getStatus().name());
        out.put("newMessages", newMessages);
        out.put("count", newMessages.size());
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
