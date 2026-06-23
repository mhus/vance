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
 * Pauses the paired Trillian User worker — new task_request events
 * still queue but don't wake the engine. Reverse via
 * {@code user_continue}.
 */
@Component
@RequiredArgsConstructor
public class UserStopTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of());

    private final TrillianInternalApi api;
    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;

    @Override
    public String name() {
        return "user_stop";
    }

    @Override
    public String description() {
        return "Pause the paired Trillian User worker. Queued tasks "
                + "stay queued; the engine just stops draining until "
                + "user_continue.";
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
            throw new ToolException("user_stop requires a process scope");
        }
        Optional<ThinkProcessDocument> peerOpt = api.findPeer(ctx.processId());
        if (peerOpt.isEmpty()) {
            throw new ToolException(
                    "No Trillian User peer process found — this tool is only available "
                            + "inside a Trillian-Control session");
        }
        ThinkProcessDocument peer = peerOpt.get();
        ThinkProcessStatus current = peer.getStatus();
        if (current == ThinkProcessStatus.PAUSED
                || current == ThinkProcessStatus.CLOSED) {
            return shape(peer.getName(), current);
        }
        try {
            laneScheduler.submit(peer.getId(), () -> {
                thinkProcessService.updateStatus(peer.getId(), ThinkProcessStatus.PAUSED);
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted waiting for user_stop");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new ToolException("user_stop failed: " + cause.getMessage(), cause);
        }
        return shape(peer.getName(), ThinkProcessStatus.PAUSED);
    }

    private static Map<String, Object> shape(String name, ThinkProcessStatus status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("peerProcessName", name);
        out.put("status", status == null ? null : status.name());
        return out;
    }
}
