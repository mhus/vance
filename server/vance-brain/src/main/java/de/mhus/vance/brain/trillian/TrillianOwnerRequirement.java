package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.project.ProjectOwnerRequirementContributor;
import de.mhus.vance.brain.project.ProjectOwnerRequirementService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A live Trillian loop is waiting work for the hub it lives in. Nothing
 * external arrives to wake it — the heartbeat on the hub's home pod is the
 * only reason its self-checks and appointments happen at all — so the hub has
 * to be kept placed exactly like a project carrying a scheduler document.
 *
 * <p>The answer changes when a loop is created ({@link TrillianSessionBootstrapper}
 * asks for a recompute) and when it closes (the status listener below). A
 * close missed while the brain was down is caught by the boot re-check of
 * every pinned project ({@code ProjectOwnerRequirementService.releaseNoLongerQualifying});
 * a loop whose hub was never pinned — it predates this contributor — by the
 * boot re-check that pins ({@link #projectsWithWork()}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianOwnerRequirement implements ProjectOwnerRequirementContributor {

    private final ThinkProcessService thinkProcessService;

    /** Lazy: the requirement service collects this bean as a contributor. */
    private final ObjectProvider<ProjectOwnerRequirementService> requirementServiceProvider;

    @Override
    public boolean requiresOwner(String tenantId, String projectId) {
        return thinkProcessService.existsLiveByEngineInProject(tenantId, projectId, TrillianUserEngine.NAME);
    }

    /**
     * Every hub with a live loop — for the boot re-check that pins hubs whose
     * loop predates this contributor (or whose recompute was missed).
     */
    @Override
    public List<ProjectRef> projectsWithWork() {
        return thinkProcessService.findProjectsWithLiveEngine(TrillianUserEngine.NAME).stream()
                .map(e -> new ProjectRef(e.getKey(), e.getValue()))
                .toList();
    }

    /** Re-derives the flag for the hub a loop lives in. */
    public void recompute(String tenantId, String hubProject) {
        requirementServiceProvider.getObject().recompute(tenantId, hubProject);
    }

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        if (event.newStatus() != ThinkProcessStatus.CLOSED) {
            return;
        }
        ThinkProcessDocument process =
                thinkProcessService.findById(event.processId()).orElse(null);
        if (process == null || !TrillianUserEngine.NAME.equals(process.getThinkEngine())) {
            return;
        }
        try {
            recompute(process.getTenantId(), process.getProjectId());
        } catch (RuntimeException e) {
            log.warn(
                    "Trillian loop '{}' closed — could not re-derive ownerRequired of '{}/{}': {}",
                    process.getId(),
                    process.getTenantId(),
                    process.getProjectId(),
                    e.toString());
        }
    }
}
