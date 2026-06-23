package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianControlEngine;
import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reports the current state of the paired Trillian User process:
 * runtime status, inbox depth, and the bound Trillian user-name.
 */
@Component
@RequiredArgsConstructor
public class UserStatusTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of());

    private final TrillianInternalApi api;

    @Override
    public String name() {
        return "user_status";
    }

    @Override
    public String description() {
        return "Get the current status of the paired Trillian User "
                + "worker: runtime status, queued-task count, bound "
                + "service-account name.";
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
        return Set.of("read-only");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("user_status requires a process scope");
        }
        Optional<ThinkProcessDocument> peerOpt = api.findPeer(ctx.processId());
        if (peerOpt.isEmpty()) {
            throw new ToolException(
                    "No Trillian User peer process found — this tool is only available "
                            + "inside a Trillian-Control session");
        }
        ThinkProcessDocument peer = peerOpt.get();
        TrillianInternalApi.PeerStateSnapshot snap = api.snapshotPeerState(peer);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("peerProcessName", peer.getName());
        out.put("status", snap.status() == null ? null : snap.status().name());
        out.put("pendingInbox", snap.pendingInboxCount());
        Object userName = peer.getEngineParams() == null
                ? null : peer.getEngineParams().get(
                        TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
        out.put("trillianUserName", userName);
        return out;
    }
}
