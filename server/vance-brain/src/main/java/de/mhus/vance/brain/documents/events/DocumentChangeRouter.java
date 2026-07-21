package de.mhus.vance.brain.documents.events;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.document.DocumentChangedEvent;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Routes {@link DocumentChangedEvent}s to the pod(s) whose caches need to
 * refresh. The classification rules live in §4 of
 * {@code planning/document-change-events.md} and map roughly to:
 *
 * <ul>
 *   <li>System projects ({@code _tenant}, {@code _vance}) →
 *       broadcast to every live pod in the cluster (incl. self), because
 *       the cascade affects every project in the tenant.</li>
 *   <li>User-Hub projects ({@code _user_*}) → no event: they are
 *       <em>podless</em>, no other pod caches anything bleibendes.</li>
 *   <li>Regular project whose {@code homeNode} is self → local event only.</li>
 *   <li>Regular project whose {@code homeNode} is a remote live pod →
 *       remote-only event.</li>
 *   <li>Regular project whose {@code homeNode} is {@code null} (unclaimed) or
 *       points to a stale/unknown pod → no event (no live cache holder).</li>
 * </ul>
 *
 * <p>The router itself only re-publishes a local {@link
 * RoutedDocumentChangedEvent} or hands the change to the {@link
 * DocumentChangeDispatcher} for asynchronous remote delivery. Self-targets are
 * fired synchronously so the bug pattern „write, then immediately re-read on
 * the same pod sees fresh state" stays intact.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentChangeRouter {

    private final ProjectService projectService;
    private final ClusterService clusterService;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentChangeDispatcher dispatcher;
    private final MetricService metrics;

    @EventListener
    public void onDocumentChanged(DocumentChangedEvent event) {
        try {
            route(event);
        } catch (RuntimeException ex) {
            // The router is the last line between a successful Mongo write
            // and any cache-refresh side effect. A failure here must not
            // unwind the write — log it loudly and let the next read fall
            // back to a lazy bootstrap.
            log.warn("DocumentChangeRouter: failed to route '{}/{}/{}': {}",
                    event.tenantId(), event.projectId(), event.path(), ex.toString(), ex);
        }
    }

    private void route(DocumentChangedEvent event) {
        Classification classification = classify(event);
        metrics.counter("vance.document.routing.classified",
                "target", classification.kind.tag).increment();

        if (classification.kind == Kind.NONE) {
            log.debug("DocumentChangeRouter: drop '{}/{}/{}' — no cache holder",
                    event.tenantId(), event.projectId(), event.path());
            return;
        }

        // Self-target: fire inline so the publisher's own pod sees the
        // refresh before its next read on the same thread / request.
        if (classification.fireSelf) {
            publishRouted(event);
        }

        // Remote targets: enqueue for the async dispatcher. The router
        // never blocks on HTTP — the dispatcher's bounded queue absorbs
        // bursts and drops on overflow.
        for (String endpoint : classification.remoteEndpoints) {
            dispatcher.enqueue(endpoint, event);
        }
    }

    /**
     * Decide who needs to refresh. Visible for tests — the table in
     * {@code DocumentChangeRouterTest} mirrors the spec table 1:1.
     */
    Classification classify(DocumentChangedEvent event) {
        String projectId = event.projectId();

        // System projects cascade — every live pod in the tenant has a
        // stale cascade-view if it has loaded any project in this tenant.
        if (HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)
                || "_vance".equals(projectId)) {
            return broadcast(event);
        }

        // _user_<login> hub projects are podless by design (per memory
        // user_projects_no_home_pod): Eddie sits on a random WS-pod, no
        // bleibender cache. No event needed; the next Eddie-spawn does a
        // lazy bootstrap from Mongo.
        if (projectId != null
                && projectId.startsWith(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX)) {
            return Classification.none();
        }

        Optional<ProjectDocument> projectOpt =
                projectService.findByTenantAndName(event.tenantId(), projectId);
        if (projectOpt.isEmpty()) {
            // Unknown project — no live cache holder. Could happen for
            // tenant-bootstrap writes that race the project document write
            // itself; the next bootstrap loads from Mongo.
            return Classification.none();
        }

        String homeNode = projectOpt.get().getHomeNode();
        if (homeNode == null || homeNode.isBlank()) {
            // Unclaimed project — nobody owns it, nobody caches.
            return Classification.none();
        }

        String self = clusterService.selfNodeName();
        if (homeNode.equals(self)) {
            return Classification.selfOnly();
        }

        // Remote home pod: resolve endpoint. If the pod is gone or its row
        // went stale/stopped the resolve returns empty — the cache is rebuilt
        // on the new owner's next claim.
        Optional<String> endpoint = clusterService.resolveLiveEndpoint(homeNode);
        if (endpoint.isEmpty()) {
            log.warn("DocumentChangeRouter: homeNode '{}' for '{}/{}' has no live endpoint — "
                            + "drop refresh, next bootstrap on the new owner loads fresh",
                    homeNode, event.tenantId(), projectId);
            return Classification.none();
        }
        return Classification.remoteOnly(endpoint.get());
    }

    private Classification broadcast(DocumentChangedEvent event) {
        List<BrainPodDocument> live = clusterService.liveClusterPods();
        Set<String> remoteEndpoints = new LinkedHashSet<>();
        String self = clusterService.selfNodeName();
        boolean fireSelf = false;
        for (BrainPodDocument pod : live) {
            String nodeName = pod.getNodeName();
            if (nodeName == null) continue;
            if (nodeName.equals(self)) {
                fireSelf = true;
                continue;
            }
            String endpoint = pod.getEndpoint();
            if (endpoint != null && !endpoint.isBlank()) {
                remoteEndpoints.add(endpoint);
            }
        }
        // Self always fires for system-project writes: even if our pod
        // isn't in the live list yet (heartbeat race during boot), our
        // local caches must refresh. fireSelf left at true when self was
        // seen in the live list; below covers the boot-race fallback.
        if (!fireSelf) fireSelf = true;
        Kind kind = remoteEndpoints.isEmpty() ? Kind.SELF : Kind.BROADCAST;
        return new Classification(kind, fireSelf, List.copyOf(remoteEndpoints));
    }

    private void publishRouted(DocumentChangedEvent event) {
        RoutedDocumentChangedEvent routed = switch (event) {
            case DocumentChangedEvent.Upserted u -> new RoutedDocumentChangedEvent.Upserted(
                    u.tenantId(), u.projectId(), u.path(), u.documentId());
            case DocumentChangedEvent.Deleted d -> new RoutedDocumentChangedEvent.Deleted(
                    d.tenantId(), d.projectId(), d.path(), d.documentId());
        };
        eventPublisher.publishEvent(routed);
    }

    // ──────────────────── Result shape ────────────────────

    enum Kind {
        NONE("none"),
        SELF("self"),
        REMOTE("remote"),
        BROADCAST("broadcast");

        final String tag;
        Kind(String tag) { this.tag = tag; }
    }

    /**
     * Outcome of classifying one event. Visible for tests.
     */
    record Classification(Kind kind, boolean fireSelf, List<String> remoteEndpoints) {
        static Classification none() {
            return new Classification(Kind.NONE, false, List.of());
        }
        static Classification selfOnly() {
            return new Classification(Kind.SELF, true, List.of());
        }
        static Classification remoteOnly(String endpoint) {
            return new Classification(Kind.REMOTE, false, List.of(endpoint));
        }
    }
}
