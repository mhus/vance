package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import de.mhus.vance.shared.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On terminal {@link ThinkProcessStatus#CLOSED} disposes every RootDir
 * the closing process created with {@code deleteOnCreatorClose=true} —
 * primarily its lazy temp RootDir. {@code SUSPENDED} is intentionally
 * <em>not</em> handled: a suspended process still owns its temp content
 * and resumes against it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkspaceCreatorCleanupListener {

    private final WorkspaceService workspaceService;
    private final ThinkProcessService thinkProcessService;

    @EventListener
    public void onStatusChange(ThinkProcessStatusChangedEvent event) {
        if (event.newStatus() != ThinkProcessStatus.CLOSED) {
            return;
        }
        thinkProcessService.findById(event.processId())
                .filter(p -> StringUtils.isNotBlank(p.getTenantId())
                        && StringUtils.isNotBlank(p.getProjectId()))
                .ifPresent(p -> {
                    String tenantId = p.getTenantId();
                    String projectId = p.getProjectId();
                    try {
                        workspaceService.clearWorkingDir(tenantId, projectId, event.processId());
                        workspaceService.disposeByCreator(tenantId, projectId, event.processId());
                    } catch (RuntimeException e) {
                        log.warn("disposeByCreator failed for {}/{}/{}: {}",
                                tenantId, projectId, event.processId(), e.toString());
                    }
                });
    }
}
