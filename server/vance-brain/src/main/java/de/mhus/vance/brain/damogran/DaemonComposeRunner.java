package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.daemon.DaemonRegistry;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.worktarget.WorkTargetService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code target: DAEMON} — run the compose against a named, project-scoped
 * <b>daemon</b> host's filesystem. exec-only, no managed workspace; see
 * {@link RemoteExecComposeRunner}. The daemon name is {@code workspace.name}
 * (the addressable target). Requires that daemon to be connected in the project.
 */
@Component
public class DaemonComposeRunner extends RemoteExecComposeRunner {

    private final DaemonRegistry daemonRegistry;

    public DaemonComposeRunner(WorkTargetService workTargetService,
                               ThinkProcessService thinkProcessService,
                               @Lazy ToolDispatcher toolDispatcher,
                               DamogranTransport transport,
                               DaemonRegistry daemonRegistry) {
        super(workTargetService, thinkProcessService, toolDispatcher, transport);
        this.daemonRegistry = daemonRegistry;
    }

    @Override
    public String target() {
        return "DAEMON";
    }

    @Override
    protected WorkTarget workTargetFor(DamogranManifest manifest) {
        return WorkTarget.daemon(manifest.workspace().name());
    }

    @Override
    protected void requireConnected(
            String tenantId, String projectId, ThinkProcessDocument process, DamogranManifest manifest) {
        String daemonName = manifest.workspace().name();
        boolean live = daemonRegistry.find(tenantId, projectId, daemonName)
                .filter(ref -> !ref.stale())
                .isPresent();
        if (!live) {
            throw new DamogranException("DAEMON target: daemon '" + daemonName
                    + "' is not connected in this project");
        }
    }
}
