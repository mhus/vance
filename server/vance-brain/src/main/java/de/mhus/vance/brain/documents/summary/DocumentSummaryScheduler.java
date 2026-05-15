package de.mhus.vance.brain.documents.summary;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One pod-local scheduler tick: walk every project this pod owns,
 * check the per-project {@code autoSummary.enabled} cascade setting,
 * claim a batch of dirty documents, and hand each to
 * {@link DocumentSummaryDriver}.
 *
 * <p>Per-pod isolation: {@code projectService.findRunningByPod()}
 * already filters to projects this pod owns, and
 * {@link DocumentService#claimForSummary} is atomic per document —
 * so even when a {@code _user_<name>} project briefly visits another
 * pod, the claim mechanic prevents double-summarisation.
 *
 * <p>Sequential per tick on purpose — no thread-pool — to keep the
 * LLM pressure predictable. {@code batchSize} bounds the work per
 * project per tick.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentSummaryScheduler {

    /** Cascade setting key — default {@code true} (feature opt-out, not opt-in). */
    public static final String SETTING_ENABLED = "autoSummary.enabled";

    private final ProjectService projectService;
    private final LocationService locationService;
    private final ClusterService clusterService;
    private final SettingService settingService;
    private final DocumentService documentService;
    private final DocumentSummaryDriver driver;

    @Value("${vance.autoSummary.batchSize:10}")
    private int batchSize;

    @Value("${vance.autoSummary.claimTtlMinutes:10}")
    private int claimTtlMinutes;

    @Scheduled(fixedDelayString = "${vance.autoSummary.intervalMs:300000}",
            initialDelayString = "${vance.autoSummary.initialDelayMs:60000}")
    public void tick() {
        String podId = locationService.getPodAddress();
        String selfCluster = clusterService.selfNodeName();
        // Pod-owned RUNNING projects (regular projects with a Home Pod
        // = our cluster node name) plus podless projects (system /
        // per-user) which never reach RUNNING but still hold documents
        // we want to summarise. The per-document claim in
        // {@link DocumentService#claimForSummary} is atomic, so multiple
        // pods racing on a podless project is safe — no document is
        // summarised twice.
        List<ProjectDocument> projects = selfCluster.isBlank()
                ? new ArrayList<>()
                : new ArrayList<>(projectService.findRunningByHomeCluster(selfCluster));
        projects.addAll(projectService.findPodlessActive());
        if (projects.isEmpty()) return;

        Duration claimTtl = Duration.ofMinutes(claimTtlMinutes);
        for (ProjectDocument project : projects) {
            boolean enabled = settingService.getBooleanValueCascade(
                    project.getTenantId(), project.getName(),
                    /*thinkProcessId*/ null,
                    SETTING_ENABLED, /*default*/ true);
            if (!enabled) continue;

            List<DocumentDocument> claimed;
            try {
                claimed = documentService.claimForSummary(
                        project.getTenantId(), project.getName(),
                        podId, batchSize, claimTtl);
            } catch (RuntimeException e) {
                log.warn("Auto-summary claim failed tenant='{}' project='{}': {}",
                        project.getTenantId(), project.getName(), e.toString());
                continue;
            }
            if (claimed.isEmpty()) continue;

            log.debug("Auto-summary tick tenant='{}' project='{}' claimed={}",
                    project.getTenantId(), project.getName(), claimed.size());
            for (DocumentDocument doc : claimed) {
                processOne(project, doc);
            }
        }
    }

    private void processOne(ProjectDocument project, DocumentDocument doc) {
        try {
            driver.run(project, doc);
        } catch (RuntimeException e) {
            log.warn("Auto-summary run failed tenant='{}' project='{}' doc='{}' path='{}': {}",
                    project.getTenantId(), project.getName(),
                    doc.getId(), doc.getPath(), e.toString());
            // Release the claim so a subsequent tick can retry.
            // summaryDirty stays true — the doc remains a candidate.
            try {
                documentService.releaseClaim(doc.getId());
            } catch (RuntimeException releaseFail) {
                log.warn("Auto-summary claim release failed doc='{}': {}",
                        doc.getId(), releaseFail.toString());
            }
        }
    }
}
