package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.trillian.TrillianControlEngine;
import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resumes a previously paused Trillian User worker — flips status
 * back to IDLE and schedules a turn so any queued tasks get drained
 * immediately.
 */
@Component
@RequiredArgsConstructor
public class UserContinueTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of());

    private final TrillianInternalApi api;
    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;

    @Override
    public String name() {
        return "user_continue";
    }

    @Override
    public String description() {
        return "Resume the paired Trillian User worker. Drains any "
                + "task_request events that piled up during the pause.";
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
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("user_continue requires a process scope");
        }
        Optional<ThinkProcessDocument> peerOpt = api.findPeer(ctx.processId());
        if (peerOpt.isEmpty()) {
            throw new ToolException(
                    "No Trillian User peer process found — this tool is only available "
                            + "inside a Trillian-Control session");
        }
        ThinkProcessDocument peer = peerOpt.get();
        ThinkProcessStatus current = peer.getStatus();
        if (current == ThinkProcessStatus.CLOSED) {
            throw new ToolException(
                    "Trillian User worker is CLOSED — use user_reset to recreate it");
        }
        try {
            laneScheduler.submit(peer.getId(), () -> {
                thinkProcessService.updateStatus(peer.getId(), ThinkProcessStatus.IDLE);
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted waiting for user_continue");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new ToolException("user_continue failed: " + cause.getMessage(), cause);
        }
        // Wake the lane so queued task_request events are picked up now.
        api.wakePeer(peer.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("peerProcessName", peer.getName());
        out.put("status", ThinkProcessStatus.IDLE.name());
        return out;
    }
}
