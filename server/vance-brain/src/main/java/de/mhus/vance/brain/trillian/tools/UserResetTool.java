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
 * Soft-reset of the Trillian User worker: clear pending tasks and
 * return the engine to IDLE. The worker process stays the same
 * Mongo document — Nature-0 doesn't tear it down and respawn.
 *
 * <p>Hard reset (close + recreate) is a future Nature-A+ feature;
 * for Nature-0 a clear+IDLE is the practical "start fresh".
 */
@Component
@RequiredArgsConstructor
public class UserResetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of());

    private final TrillianInternalApi api;
    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;

    @Override
    public String name() {
        return "user_reset";
    }

    @Override
    public String description() {
        return "Soft-reset the Trillian User worker: clear queued "
                + "tasks and return the engine to IDLE. Use when the "
                + "worker is in a confused state and you want a clean "
                + "slate without losing the user-identity.";
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
            throw new ToolException("user_reset requires a process scope");
        }
        Optional<ThinkProcessDocument> peerOpt = api.findPeer(ctx.processId());
        if (peerOpt.isEmpty()) {
            throw new ToolException(
                    "No Trillian User peer process found — this tool is only available "
                            + "inside a Trillian-Control session");
        }
        ThinkProcessDocument peer = peerOpt.get();
        int cleared = api.clearPending(peer.getId());

        try {
            laneScheduler.submit(peer.getId(), () -> {
                thinkProcessService.updateStatus(peer.getId(), ThinkProcessStatus.IDLE);
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted waiting for user_reset");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new ToolException("user_reset failed: " + cause.getMessage(), cause);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("peerProcessName", peer.getName());
        out.put("cleared", cleared);
        out.put("status", ThinkProcessStatus.IDLE.name());
        return out;
    }
}
