package de.mhus.vance.brain.documents.events;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.document.DocumentChangedEvent;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectOwnership;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Instant;
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
 * {@code planning/document-change-events.md}.
 *
 * <h2>The writing pod always refreshes itself</h2>
 * Self fires for every routed change; ownership only decides who else hears
 * about it:
 *
 * <ul>
 *   <li>The tenant-wide project ({@code _tenant}) → self plus every live pod in
 *       the cluster, because the cascade affects every project in the tenant.
 *       It is the only broadcast case: {@code _vance} stopped being a
 *       {@code projectId} in commit {@code 4f9532f7b}.</li>
 *   <li>Lease held by a remote pod → self plus that pod.</li>
 *   <li>Lease held by self, no valid lease at all, podless project, unknown
 *       project → self only.</li>
 * </ul>
 *
 * <p><b>Why self is unconditional.</b> This used to drop the event entirely
 * when nobody held a lease, reasoning "no owner ⇒ nobody caches". That is
 * provably false: {@code ServerToolService.lookup/listAll} bootstraps a project
 * scope on <em>any</em> read on <em>any</em> pod, ownership-independent — so the
 * pod that just served this write is precisely the one that may hold a stale
 * one. The drop is also what forced the kit-provisioning listener onto the raw
 * event; a cache-coherence router that silently loses events for the common
 * case ({@code EPHEMERAL} project after a restart) invites every subsystem to
 * build its own channel. See
 * {@code planning/project-ownership-lease-design.md} §1.3.
 *
 * <p><b>What makes that safe.</b> A local event must not conjure runtime
 * behaviour on a pod that has not activated the project — a scheduler or hook
 * registration is not a cache. The two listeners that create such state
 * ({@code UrsaSchedulerDocumentListener}, {@code UrsaHookDocumentListener})
 * therefore filter on {@code ProjectActivationRegistry}; the pure-cache
 * listeners need no filter, and {@code ServerToolRegistry.refreshOne} already
 * no-ops when the scope is not loaded. Keeping that distinction in the
 * listeners rather than in the router is deliberate: only the listener knows
 * whether its state is a cache or a running thing.
 *
 * <p>The router itself only re-publishes a local {@link
 * RoutedDocumentChangedEvent} or hands the change to the {@link
 * DocumentChangeDispatcher} for asynchronous remote delivery. Self-targets are
 * fired synchronously so the bug pattern „write, then immediately re-read on
 * the same pod sees fresh state" stays intact.
 *
 * <p>Volume is bounded by {@code DocumentService.isEventPublishable}: only
 * {@code _vance/**} minus logs and trash reaches this bus at all.
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

        // Self fires inline: the publisher's own pod must see the refresh
        // before its next read on the same thread / request, and it is the one
        // pod that provably may hold a scope for this project (see the class
        // comment). Every classification sets this today.
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

        // The tenant-scope project cascade — every live pod in the tenant has
        // a stale cascade-view if it has loaded any project in this tenant.
        if (HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            return broadcast(event);
        }

        // _user_<login> hub projects are podless by design (per memory
        // user_projects_no_home_pod): Eddie sits on a random WS-pod, so there
        // is no remote holder to notify — but this pod is very likely the one
        // Eddie runs on, so the local refresh matters.
        if (projectId != null
                && projectId.startsWith(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX)) {
            return Classification.selfOnly();
        }

        Optional<ProjectDocument> projectOpt =
                projectService.findByTenantAndName(event.tenantId(), projectId);
        if (projectOpt.isEmpty()) {
            // Unknown project — happens for tenant-bootstrap writes that race
            // the project document write itself. Nobody to notify remotely;
            // our own scopes are keyed by project *name*, so refresh locally.
            return Classification.selfOnly();
        }

        Optional<String> holder = ProjectOwnership.liveOwnerPodId(
                projectOpt.get(), Instant.now(), clusterService.leaseTtl());
        if (holder.isEmpty() || holder.get().equals(clusterService.selfPodId())) {
            // No valid lease, or it is ours — either way there is no second
            // pod that needs telling.
            return Classification.selfOnly();
        }

        // Remote holder: resolve endpoint. A missing row (admin purge, cleanup
        // sweep) means we cannot deliver there — the holder rebuilds on its
        // next claim; we still refresh locally.
        Optional<String> endpoint = clusterService.resolveEndpointByPodId(holder.get());
        if (endpoint.isEmpty()) {
            log.warn("DocumentChangeRouter: lease holder '{}' for '{}/{}' has no endpoint row — "
                            + "local refresh only, the holder reloads on its next claim",
                    projectOpt.get().getHomeNode(), event.tenantId(), projectId);
            return Classification.selfOnly();
        }
        return Classification.selfAndRemote(endpoint.get());
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

    /**
     * How far the change fans out. Self is always included, so this names the
     * remote reach: none, one lease holder, or the whole cluster.
     */
    enum Kind {
        SELF("self"),
        REMOTE("remote"),
        BROADCAST("broadcast");

        final String tag;
        Kind(String tag) { this.tag = tag; }
    }

    /**
     * Outcome of classifying one event. Visible for tests.
     *
     * <p>{@code fireSelf} is kept in the record even though it is always
     * {@code true} today: it is what the tests assert against, and a future
     * target that genuinely must not fire locally would set it rather than
     * grow a second flag.
     */
    record Classification(Kind kind, boolean fireSelf, List<String> remoteEndpoints) {
        static Classification selfOnly() {
            return new Classification(Kind.SELF, true, List.of());
        }
        static Classification selfAndRemote(String endpoint) {
            return new Classification(Kind.REMOTE, true, List.of(endpoint));
        }
    }
}
