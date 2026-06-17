package de.mhus.vance.brain.rag;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic project-RAG indexer tick — analogous to
 * {@link de.mhus.vance.brain.documents.summary.DocumentSummaryScheduler}
 * but for the {@code _documents} RAG. Walks the projects this pod owns,
 * claims a batch of dirty documents per project, and hands each to
 * {@link ProjectRagIndexer}.
 *
 * <p>Per-pod isolation comes from {@code projectService.findRunningByHomeNode(...)}
 * plus the atomic claim in {@code DocumentService.claimForRagIndex}.
 * Failures release the claim eagerly; a follow-up tick retries. Claim TTL
 * is the backstop against pod crashes between claim and write.
 *
 * <p>See {@code planning/project-rag.md} §4.4 and §5.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectRagIndexScheduler {

    private final ProjectService projectService;
    private final LocationService locationService;
    private final ClusterService clusterService;
    private final ProjectRagService projectRagService;
    private final RagService ragService;
    private final DocumentService documentService;
    private final ProjectRagIndexer indexer;
    private final MeterRegistry meterRegistry;

    @Value("${vance.rag.indexer.batchSize:10}")
    private int batchSize;

    @Value("${vance.rag.indexer.claimTtlMinutes:10}")
    private int claimTtlMinutes;

    @Scheduled(fixedDelayString = "${vance.rag.indexer.intervalMs:30000}",
            initialDelayString = "${vance.rag.indexer.initialDelayMs:60000}")
    public void tick() {
        String podId = locationService.getPodAddress();
        String selfCluster = clusterService.selfNodeName();
        List<ProjectDocument> projects = selfCluster.isBlank()
                ? new ArrayList<>()
                : new ArrayList<>(projectService.findRunningByHomeNode(selfCluster));
        projects.addAll(projectService.findPodlessActive());
        if (projects.isEmpty()) return;

        Duration claimTtl = Duration.ofMinutes(claimTtlMinutes);
        for (ProjectDocument project : projects) {
            // Cascade kill-switch wins over the per-project rag.project.enabled
            // toggle — provider=none at tenant OR project level skips the claim
            // path entirely, no embed-model lookup, no Mongo claim roundtrip.
            if (!ragService.isEmbeddingEnabled(project.getTenantId(), project.getName())) {
                runCounter("tenant_disabled").increment();
                continue;
            }
            if (!projectRagService.isEnabled(project.getTenantId(), project.getName())) {
                runCounter("skipped").increment();
                continue;
            }

            List<DocumentDocument> claimed;
            try {
                claimed = documentService.claimForRagIndex(
                        project.getTenantId(), project.getName(),
                        podId, batchSize, claimTtl);
            } catch (RuntimeException e) {
                log.warn("RAG-index claim failed tenant='{}' project='{}': {}",
                        project.getTenantId(), project.getName(), e.toString());
                runCounter("failed").increment();
                continue;
            }
            if (claimed.isEmpty()) continue;

            log.debug("RAG-index tick tenant='{}' project='{}' claimed={}",
                    project.getTenantId(), project.getName(), claimed.size());
            for (DocumentDocument doc : claimed) {
                processOne(doc);
            }
        }
    }

    private void processOne(DocumentDocument doc) {
        try {
            indexer.reindexDocument(doc);
            runCounter("success").increment();
        } catch (RuntimeException e) {
            log.warn("RAG-index run failed tenant='{}' project='{}' doc='{}' path='{}': {}",
                    doc.getTenantId(), doc.getProjectId(),
                    doc.getId(), doc.getPath(), e.toString());
            runCounter("failed").increment();
            try {
                documentService.releaseRagClaim(doc.getId());
            } catch (RuntimeException releaseFail) {
                log.warn("RAG-index claim release failed doc='{}': {}",
                        doc.getId(), releaseFail.toString());
            }
        }
    }

    private Counter runCounter(String outcome) {
        return Counter.builder("vance.rag.indexer.runs")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
