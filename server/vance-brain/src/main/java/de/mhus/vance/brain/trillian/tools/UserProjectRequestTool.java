package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianControlEngine;
import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.permission.PermissionRequestPort;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Asks for the paired Trillian worker to be allowed into another project.
 *
 * <p>Exists because the worker's account name is generated per session
 * (`_trillian-<nature>-<instance>`) and means nothing to the human: expecting them to
 * copy it into an admin form is a bad answer to a real need. The tool
 * fills subject and role in itself, so the chat can carry the whole
 * exchange — "let it work in test1 too" — while the throwaway name stays
 * internal.
 *
 * <p>It grants nothing. The request goes to whoever administers the
 * target project, and only their approval performs the change; see
 * {@code planning/permission-request-inbox.md}. Role is WRITER: a worker
 * let into a foreign project is there to work, not to administer.
 *
 * <p>Gated on the control role, so the worker loop cannot widen its own
 * reach — only the side the human talks to may ask.
 */
@Component
@RequiredArgsConstructor
public class UserProjectRequestTool implements Tool {

    private final TrillianInternalApi api;
    private final ObjectProvider<PermissionRequestPort> requestPortProvider;

    @Override
    public String name() {
        return "user_project_request";
    }

    @Override
    public String description() {
        return "Request access for the paired Trillian worker to another project, so it can "
                + "spawn workers there. This does NOT grant anything: an administrator of the "
                + "target project has to approve it. Tell the user that approval is pending — "
                + "do not claim the worker can already work there.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("projectId", Map.of("type", "string",
                "description", "Name of the project the worker should be allowed to work in."));
        props.put("reason", Map.of("type", "string",
                "description", "Why the access is needed. Shown to the approver as your "
                        + "stated reason."));
        return Map.of("type", "object", "properties", props,
                "required", List.of("projectId"));
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("user_project_request requires a process scope");
        }
        String projectId = str(params, "projectId");
        if (StringUtils.isBlank(projectId)) {
            throw new ToolException("missing required parameter: projectId");
        }
        String trillianUser = boundWorkerName(ctx);

        PermissionRequestPort port = requestPortProvider.getIfAvailable();
        if (port == null) {
            // An external governor manages rights elsewhere; asking through
            // Vance is not a thing here.
            throw new ToolException("This deployment manages permissions outside Vance — "
                    + "ask an administrator to grant '" + trillianUser + "' access to '"
                    + projectId + "' directly.");
        }

        PermissionRequestPort.PermissionRequestReceipt receipt = port.requestProjectWriter(
                ctx.tenantId(), projectId, trillianUser, str(params, "reason"),
                ctx.userId() == null ? "system" : ctx.userId(), ctx.processId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", true);
        out.put("granted", false);
        out.put("projectId", projectId);
        out.put("subjectId", trillianUser);
        out.put("role", "WRITER");
        out.put("status", receipt.status());
        out.put("requestId", receipt.requestId());
        if (receipt.itemId() != null) {
            out.put("itemId", receipt.itemId());
        }
        if (receipt.decider() != null) {
            out.put("awaitingApprovalBy", receipt.decider());
        }
        if (receipt.reused()) {
            out.put("note", "An identical request is already awaiting approval.");
        } else if (receipt.itemId() == null) {
            out.put("note", "No administrator was found for that project — "
                    + "the request cannot be decided and will expire.");
        }
        return out;
    }

    /** The `_trillian-<nature>-<instance>` account of the paired worker loop. */
    private String boundWorkerName(ToolInvocationContext ctx) {
        Optional<ThinkProcessDocument> peer = api.findPeer(ctx.processId());
        if (peer.isEmpty()) {
            throw new ToolException(
                    "No Trillian User peer process found — this tool is only available "
                            + "inside a Trillian-Control session");
        }
        Object name = peer.get().getEngineParams() == null
                ? null : peer.get().getEngineParams().get(
                        TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
        if (name == null || StringUtils.isBlank(name.toString())) {
            throw new ToolException("The paired Trillian worker has no bound service account");
        }
        return name.toString();
    }

    private static @org.jspecify.annotations.Nullable String str(
            Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v == null ? null : v.toString().trim();
    }
}
