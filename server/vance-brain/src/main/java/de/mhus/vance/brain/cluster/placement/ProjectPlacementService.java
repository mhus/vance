package de.mhus.vance.brain.cluster.placement;

import de.mhus.vance.brain.cluster.ClusterBringClient;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.cluster.BrainPodCapacity;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.PodSelector;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.project.LifecycleType;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The one place that answers "which pod should run this project", and
 * dispatches the bring to it.
 *
 * <p>Before this service the question had seven answers. Two call sites
 * carried byte-identical copies of a {@code haveLocalRoom()} helper, two more
 * carried near-identical copies of the pick-a-pod loop that had already
 * drifted apart (one clamped {@code maxScore} to at least 1, the other did
 * not — so a pod row with {@code maxScore = 0} was usable for one and unusable
 * for the other), and three paths took a project without asking about capacity
 * at all. The consequence was not a bug report but a silent one: the two
 * shortcuts that skipped the cluster view were the <em>common</em> paths, so
 * {@code resourcesMaxScore} was, in practice, advisory. Full account in
 * {@code planning/project-placement-labels.md} §1.
 *
 * <p><b>Placement is not ownership.</b> This service decides and dispatches;
 * it never writes the lease. The claim CAS in
 * {@code ProjectService.claim} stays the last word and keeps every path
 * race-free — two pods can legitimately decide about the same project at the
 * same moment, and exactly one of them wins the lease.
 *
 * <p>Two stages, and they answer different questions:
 * <ol>
 *   <li><b>Filter</b> — which pods are <em>eligible</em>: the project's
 *       {@code placementSelector} against each pod's {@code labels}, decided by
 *       {@link PodSelector} and nowhere else. A project without a selector is
 *       eligible everywhere except on an {@code exclusive} pod, so an
 *       installation that configures nothing behaves exactly as it did before
 *       labels existed — and pays nothing for them, because an empty selector
 *       returns without reading a single label.</li>
 *   <li><b>Fit</b> — which of the eligible ones has room, cheapest-loaded
 *       first. {@code ClusterService.liveClusterPods()} already returns the
 *       list sorted by load fraction, so "first that fits" <em>is</em>
 *       "least loaded that fits".</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectPlacementService {

    private final ClusterService clusterService;
    private final ProjectService projectService;
    private final MetricService metricService;
    private final ClusterBringClient bringClient;
    /**
     * {@link ObjectProvider} to keep the bean graph acyclic: the lifecycle
     * service reaches back here through {@code create}, and its {@code bring}
     * is only ever needed at dispatch time — long after both beans exist.
     */
    private final ObjectProvider<ProjectLifecycleService> lifecycleServiceProvider;

    /**
     * Decide where {@code project} should run. Pure computation over the live
     * pod list — no writes, no dispatch, safe to call for a look.
     *
     * <p>Order matters and is deliberate: the local short-circuit comes
     * <em>before</em> the "are there any pods" check, because an unregistered
     * pod (boot, single-pod dev) has unbounded local headroom and must be able
     * to place a project before it has seen itself in {@code brain_pods}.
     */
    public PlacementDecision decide(ProjectDocument project, PlacementTrigger trigger) {
        if (livesWhereverAsked(project)) {
            return new PlacementDecision.Here();
        }
        if (trigger.prefersLocal()
                && isEligibleHere(project)
                && localHeadroom() >= project.getHomeResourceScore()) {
            return new PlacementDecision.Here();
        }
        return record(project, evaluate(project));
    }

    /**
     * The same filter-then-fit computation as {@link #decide}, <b>without the
     * local preference and without side effects</b>: no counter, no
     * {@code pendingSince} write.
     *
     * <p>For readers. {@link PlacementDemandService} derives the demand by
     * asking this question about every waiting project, and a report endpoint
     * that a provisioner polls must not write to Mongo once per project per
     * poll — nor mark a project as "waiting since" at the moment somebody
     * merely looked.
     */
    public PlacementDecision evaluate(ProjectDocument project) {
        if (livesWhereverAsked(project)) {
            return new PlacementDecision.Here();
        }
        List<BrainPodDocument> pods = clusterService.liveClusterPods();
        int[] projected = currentScores(pods);
        return pick(pods, projected, project);
    }

    /**
     * Decide for a whole batch, carrying a reservation buffer across it: a pod
     * chosen for one project counts as that much fuller for the next.
     *
     * <p>Without the buffer, every project in a round lands on the same
     * cheapest pod, because none of the decisions can see the others — which
     * is exactly why the distributor grew its own copy of the pick loop
     * instead of calling the shared one. The buffer belongs here; that it did
     * not is what made the copy look justified.
     *
     * <p><b>No local preference, and no trigger parameter to ask for one.</b>
     * A batch is a distribution. Preferring the local pod for every item would
     * pile the whole round onto one pod — the very thing the buffer prevents.
     *
     * @return one decision per input, index-aligned.
     */
    public List<PlacementDecision> decideBatch(List<ProjectDocument> projects) {
        List<BrainPodDocument> pods = clusterService.liveClusterPods();
        int[] projected = currentScores(pods);
        List<PlacementDecision> decisions = new ArrayList<>(projects.size());
        for (ProjectDocument project : projects) {
            if (livesWhereverAsked(project)) {
                decisions.add(new PlacementDecision.Here());
                continue;
            }
            PlacementDecision decision = record(project, pick(pods, projected, project));
            if (decision instanceof PlacementDecision.On on) {
                // Reserve: the pod we just chose counts as that much fuller for
                // the rest of the round. Index lookup rather than a map because
                // pods is a short list and identity is positional here.
                projected[pods.indexOf(on.pod())] += project.getHomeResourceScore();
            }
            decisions.add(decision);
        }
        return decisions;
    }

    /**
     * Decide and act. Throws {@link ClusterFullException} when nowhere fits,
     * so callers that have no answer for that case do not have to invent one.
     *
     * @return the decision that was dispatched — never
     *         {@link PlacementDecision.Unschedulable}.
     */
    public PlacementDecision place(ProjectDocument project, PlacementTrigger trigger) {
        PlacementDecision decision = decide(project, trigger);
        if (decision instanceof PlacementDecision.Unschedulable unschedulable) {
            throw new ClusterFullException(unschedulable.gap(),
                    "Cannot place project '" + project.getTenantId() + "/" + project.getName()
                            + "' (score=" + project.getHomeResourceScore() + "): "
                            + unschedulable.gap());
        }
        dispatch(decision, project);
        return decision;
    }

    /**
     * Execute a decision: bring locally, or ask the target pod to bring.
     * Separate from {@link #decide} because the distributor decides a whole
     * batch first and then dispatches item by item, tolerating a failure per
     * item.
     */
    public void dispatch(PlacementDecision decision, ProjectDocument project) {
        String tenantId = project.getTenantId();
        String name = project.getName();
        switch (decision) {
            case PlacementDecision.Here ignored -> bringLocally(tenantId, name, "this pod");
            case PlacementDecision.On on -> {
                BrainPodDocument target = on.pod();
                if (isSelf(target)) {
                    bringLocally(tenantId, name, target.getNodeName());
                } else {
                    bringClient.requestBring(target.getEndpoint(), tenantId, name);
                    log.info("Placed '{}/{}' on remote pod '{}' ({})",
                            tenantId, name, target.getNodeName(), target.getEndpoint());
                }
            }
            case PlacementDecision.Unschedulable unschedulable -> throw new IllegalStateException(
                    "Cannot dispatch an unschedulable decision for '" + tenantId + "/" + name
                            + "' (" + unschedulable.gap() + ") — check the decision first");
        }
    }

    /**
     * Score units this pod could still take before it hits its own
     * {@code resourcesMaxScore}. May be negative when the pod is overbooked,
     * which is a legitimate state — the cap has always been best-effort.
     *
     * <p>{@link Integer#MAX_VALUE} when this pod has no registry row yet.
     * "I cannot see myself" must not read as "I am full": that would stop a
     * booting pod from placing anything, and it is the previous behaviour of
     * the two deleted {@code haveLocalRoom()} copies, which both defaulted to
     * "there is room" for the same reason.
     */
    /**
     * Whether this pod's labels satisfy the project's selector. <b>Capacity is
     * not part of this</b> — the two attach paths (session create/resume,
     * workspace adopt) ask exactly this question and must not ask the other
     * one: there is a user waiting, and refusing them over a soft score cap
     * would be worse than the overrun the spec already calls acceptable
     * ({@code ClusterProperties} {@code resources.maxScore}).
     *
     * <p>{@code true} when this pod has no registry row yet. "I cannot see
     * myself" must not read as "I am not allowed" — a booting pod has to be
     * able to serve, and the same reasoning governs {@link #localHeadroom}.
     */
    public boolean isEligibleHere(ProjectDocument project) {
        if (livesWhereverAsked(project)) {
            return true;
        }
        return clusterService.selfPod()
                .map(pod -> PodSelector.isEligible(project, pod))
                .orElse(true);
    }

    /**
     * By name, for callers that hold a project id rather than the document.
     * An unknown project answers {@code true} — there is nothing to refuse, and
     * the caller's own not-found path is the right place to report it.
     */
    public boolean isEligibleHere(String tenantId, String projectName) {
        return projectService.findByTenantAndName(tenantId, projectName)
                .map(this::isEligibleHere)
                .orElse(true);
    }

    public int localHeadroom() {
        return clusterService.selfPod()
                .map(BrainPodCapacity::headroom)
                .orElse(Integer.MAX_VALUE);
    }

    /**
     * Side-effects of a decision that nobody should have to remember at a call
     * site: count it, and mark an unplaceable project as waiting.
     *
     * <p>The mark is written here rather than by the distributor, and that is
     * the whole reason a freshly created project shows up in the demand at all:
     * it is in no orphan query, so if only the distributor stamped it, a person
     * who just pressed "create" would wait while the report showed nothing
     * ({@code planning/project-placement-labels.md} §6.2).
     *
     * <p>Best-effort: the mark is diagnostic, so a failed write must not turn a
     * placement decision into an exception.
     */
    private PlacementDecision record(ProjectDocument project, PlacementDecision decision) {
        if (!(decision instanceof PlacementDecision.Unschedulable unschedulable)) {
            return decision;
        }
        metricService.counter("vance.cluster.placement.unschedulable",
                "gap", unschedulable.gap().name().toLowerCase()).increment();
        try {
            if (projectService.markPendingPlacement(
                    project.getTenantId(), project.getName(), Instant.now())) {
                log.info("Project '{}/{}' is waiting for a pod ({})",
                        project.getTenantId(), project.getName(), unschedulable.gap());
            }
        } catch (RuntimeException e) {
            log.warn("Could not mark '{}/{}' as pending placement: {}",
                    project.getTenantId(), project.getName(), e.toString());
        }
        return decision;
    }

    private void bringLocally(String tenantId, String name, String where) {
        lifecycleServiceProvider.getObject().bring(tenantId, name);
        log.info("Placed '{}/{}' locally on '{}'", tenantId, name, where);
    }

    private boolean isSelf(BrainPodDocument pod) {
        String selfNode = clusterService.selfNodeName();
        return selfNode != null && selfNode.equals(pod.getNodeName());
    }

    /**
     * Projects with no pod affinity at all — the podless system projects
     * ({@code _vance}, {@code _user_<login>}) and anything an operator pinned
     * to {@link LifecycleType#HOMELESS}. They take no lease and live on
     * whichever pod the client's WS landed on, so there is nothing to decide.
     *
     * <p>Both conditions are checked although SYSTEM projects get HOMELESS at
     * create time: legacy documents exist where the two disagree, and routing
     * such a project to a remote pod would send it somewhere it can never be
     * owned (see {@code ProjectManagerServicePodlessTest}).
     */
    private static boolean livesWhereverAsked(ProjectDocument project) {
        return project.getLifecycleType() == LifecycleType.HOMELESS
                || ProjectService.isPodless(project.getName());
    }

    private static int[] currentScores(List<BrainPodDocument> pods) {
        return pods.stream().mapToInt(BrainPodDocument::getResourcesCurrentScore).toArray();
    }

    /**
     * Filter, then fit: the first <em>eligible</em> pod in the load-sorted list
     * with room for the project, or an {@link PlacementDecision.Unschedulable}
     * naming which of the two stages came up empty.
     *
     * <p>The distinction between the two gaps is made here and nowhere else,
     * and it is the reason both stages run in one loop: "no pod carries the
     * labels" and "the right pods are full" are the same symptom and two
     * different actions for whoever provides pods
     * ({@code planning/project-placement-labels.md} §6.1). Deciding it
     * afterwards from the outcome alone is not possible.
     *
     * <p>{@code maxScore} is clamped to at least 1 — that clamp is the reason
     * the two former copies of this loop behaved differently on a pod row with
     * an unset {@code resourcesMaxScore}: one had it, one did not.
     */
    private static PlacementDecision pick(
            List<BrainPodDocument> pods, int[] projected, ProjectDocument project) {
        int score = project.getHomeResourceScore();
        boolean anyEligible = false;
        for (int i = 0; i < pods.size(); i++) {
            BrainPodDocument pod = pods.get(i);
            if (!PodSelector.isEligible(project, pod)) {
                continue;
            }
            anyEligible = true;
            if (projected[i] + score <= BrainPodCapacity.effectiveMaxScore(pod)) {
                return new PlacementDecision.On(pod);
            }
        }
        return new PlacementDecision.Unschedulable(
                anyEligible ? PlacementGap.NO_CAPACITY : PlacementGap.NO_ELIGIBLE_POD);
    }
}
