package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code target: CLIENT} — run the compose against the connected <b>Foot</b>'s
 * filesystem (the user's machine). exec-only, no managed workspace; see
 * {@link RemoteExecComposeRunner}. Requires a Foot client bound to the process's
 * session.
 */
@Component
public class ClientComposeRunner extends RemoteExecComposeRunner {

    private final WorkTargetService workTargetService;

    public ClientComposeRunner(WorkTargetService workTargetService,
                               ThinkProcessService thinkProcessService,
                               @Lazy ToolDispatcher toolDispatcher,
                               DamogranTransport transport) {
        super(workTargetService, thinkProcessService, toolDispatcher, transport);
        this.workTargetService = workTargetService;
    }

    @Override
    public String target() {
        return "CLIENT";
    }

    @Override
    protected WorkTarget workTargetFor(DamogranManifest manifest) {
        return WorkTarget.client();
    }

    @Override
    protected void requireConnected(
            String tenantId, String projectId, ThinkProcessDocument process, DamogranManifest manifest) {
        if (!workTargetService.clientConnected(process.getSessionId())) {
            throw new DamogranException(
                    "CLIENT target: no Foot client is bound to this session — connect the foot CLI");
        }
    }
}
