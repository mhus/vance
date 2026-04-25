package de.mhus.vance.brain.init;

import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.session.SessionService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Reclaims pod-level state on startup.
 *
 * <p>Rationale: pod-affinity now lives on the project. When a pod
 * comes back up after a crash or rolling restart, it still owns its
 * ACTIVE projects in Mongo, but the {@code boundConnectionId} fields
 * on the sessions under those projects refer to the previous
 * incarnation and are stale — they would block resume with a
 * {@code 409}. This bean clears those stale bindings so the next
 * client connect can resume cleanly.
 *
 * <p>Project status itself is left alone. The first session-bind
 * after startup will refresh {@code podIp} and {@code claimedAt}
 * through {@link ProjectManagerService#claimForLocalPod}.
 *
 * <p>Other pods' sessions are not touched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectStartupReclaimer {

    private final ProjectManagerService projectManager;
    private final SessionService sessionService;

    @PostConstruct
    void reclaim() {
        List<ProjectDocument> mine = projectManager.projectsOwnedByLocalPod();
        if (mine.isEmpty()) {
            log.info("ProjectStartupReclaimer: no projects to reclaim");
            return;
        }
        List<String> projectNames = mine.stream().map(ProjectDocument::getName).toList();
        long n = sessionService.unbindAllForProjects(projectNames);
        log.info("ProjectStartupReclaimer: {} project(s) owned by this pod, {} stale binding(s) cleared",
                projectNames.size(), n);
    }
}
