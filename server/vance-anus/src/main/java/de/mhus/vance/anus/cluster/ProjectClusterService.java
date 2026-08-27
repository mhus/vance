package de.mhus.vance.anus.cluster;

import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.BrainCallException;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four project operations that reach the cluster: find the holder, place,
 * hand off, and write the placement selector.
 *
 * <p>Extracted from {@code ProjectCommands} because they were only reachable by
 * typing. Every one of them is a multi-step exchange with distinct outcomes
 * that a caller has to react to differently — placing a project can end in five
 * ways, and a hand-off in three — so they are the part worth having without a
 * terminal in front of it. The CRUD around them was never trapped: {@code list}
 * / {@code create} / {@code update} / {@code close} are one call into
 * {@code ProjectService}, and delete/rename into {@code
 * ProjectMaintenanceService}; wrapping those would add a layer that says
 * nothing.
 *
 * <p><b>Facts, not messages.</b> Every method answers with a record whose
 * fields name the situation. The wording an operator reads is the shell's job —
 * that separation is what lets a second caller act on {@code
 * PlacementAttempt.outcome()} instead of matching on a string.
 *
 * <p>Two of these speak to a <em>specific</em> pod rather than to any brain,
 * and that is not an implementation detail: releasing a project tears down
 * in-memory state that exists only on the pod holding it, so the hand-off has
 * to be aimed. Asking the brain where that is beats teaching this process the
 * lease TTL it cannot see.
 */
@Service
public class ProjectClusterService {

    private final AnusBrainClient brainClient;

    /**
     * Own instance — anus runs without web auto-configuration, so there is no
     * Jackson 3 mapper bean to inject.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public ProjectClusterService(AnusBrainClient brainClient) {
        this.brainClient = brainClient;
    }

    // ─── where ──────────────────────────────────────────────────────────────

    /**
     * Whether a pod holds the project, and which.
     *
     * <p>The two unknowns are kept apart because they are different facts about
     * the far end and a caller words them differently: one is "the brain would
     * not answer", the other is "the brain answered something I cannot read".
     * Collapsing them would either lose that or force the caller to guess from
     * the text.
     */
    public enum Holder {
        /** A live lease, and the pod behind it is known. */
        HELD,
        /** Nobody holds it — including the podless case, see {@link HomeLookup#detail}. */
        NONE,
        /** The brain did not answer, or answered an error. */
        UNREACHABLE,
        /** The brain answered a success we could not parse. */
        UNREADABLE;

        /** Neither held nor free — the question stayed open. */
        public boolean isUnknown() {
            return this == UNREACHABLE || this == UNREADABLE;
        }
    }

    /**
     * @param detail the brain's own words — for {@link Holder#NONE} it says
     *     <em>why</em> nobody holds it, which is not the same answer for a
     *     never-placed project and for a podless one
     */
    public record HomeLookup(
            Holder holder,
            @Nullable String nodeName,
            @Nullable String endpoint,
            String detail) {}

    /**
     * Which pod holds the project.
     *
     * <p>Asked of the brain rather than computed here: {@code homePodId} alone
     * is not the answer, because a lease that stopped being renewed still names
     * a holder that is gone, and the TTL that decides it is brain
     * configuration.
     */
    public HomeLookup home(String tenant, String name) {
        Response response;
        try {
            response = brainClient.internal(homePath(tenant, name), "GET", null);
        } catch (BrainCallException e) {
            // A brain that does not answer is the same answer as one that
            // answers an error: we do not know who holds the project. Left
            // uncaught this used to abort the command with a stack trace.
            return new HomeLookup(Holder.UNREACHABLE, null, null,
                    String.valueOf(e.getMessage()));
        }
        if (response.statusCode() == 404) {
            return new HomeLookup(Holder.NONE, null, null, response.body());
        }
        if (!response.isSuccess()) {
            return new HomeLookup(Holder.UNREACHABLE, null, null,
                    "HTTP " + response.statusCode() + " " + response.body());
        }
        try {
            var parsed = objectMapper.readTree(response.body());
            String endpoint = parsed.get("endpoint").asString();
            String nodeName = parsed.has("nodeName") ? parsed.get("nodeName").asString() : endpoint;
            return new HomeLookup(Holder.HELD, nodeName, endpoint, response.body());
        } catch (RuntimeException e) {
            // A success status with a body we cannot read is not "nobody holds
            // it" — it is not knowing, and a caller that needs the project
            // quiet has to stop rather than guess.
            return new HomeLookup(Holder.UNREADABLE, null, null, response.body());
        }
    }

    // ─── claim ──────────────────────────────────────────────────────────────

    /**
     * How a placement attempt ended. Five values because the endpoint
     * distinguishes five situations and each one has a different next step —
     * collapsing them into success/failure would throw away the only thing that
     * makes the answer actionable.
     */
    public enum PlacementOutcome {
        /** Placed, and the body names the pod. */
        PLACED,
        /** A live pod already owns it; the claim CAS would have refused anyway. */
        ALREADY_RUNNING,
        /** No pod fits — the body carries the {@code PlacementGap}. */
        UNSCHEDULABLE,
        /** A pod was chosen and the bring to it failed: sound decision, failed execution. */
        BRING_FAILED,
        /** No such project. */
        NOT_FOUND,
        /** Anything else the far end answered. */
        ERROR
    }

    public record PlacementAttempt(PlacementOutcome outcome, int statusCode, String detail) {}

    /**
     * Runs the full placement: pod search included.
     *
     * <p>Deliberately not {@code POST /admin/projects/{name}/resume}, which
     * calls {@code bring()} on whichever pod answers and therefore means "start
     * it <em>here</em>". This asks the placement service, so the labels and the
     * load decide.
     */
    public PlacementAttempt place(String tenant, String name) {
        Response response = brainClient.internal(
                "/internal/cluster/place", "POST", json(tenant, name));
        PlacementOutcome outcome = switch (response.statusCode()) {
            case 200 -> PlacementOutcome.PLACED;
            case 409 -> PlacementOutcome.ALREADY_RUNNING;
            case 503 -> PlacementOutcome.UNSCHEDULABLE;
            case 502 -> PlacementOutcome.BRING_FAILED;
            case 404 -> PlacementOutcome.NOT_FOUND;
            default -> PlacementOutcome.ERROR;
        };
        return new PlacementAttempt(outcome, response.statusCode(), response.body());
    }

    // ─── drain ──────────────────────────────────────────────────────────────

    /** Whether the project was on a pod when we looked. */
    public enum Placement {
        /** Nobody held it — there was nothing to hand off. */
        NOT_PLACED,
        /** A pod held it and we reached that pod. */
        PLACED,
        /** We could not find out — unreachable brain, or the lease moved. */
        UNKNOWN
    }

    /**
     * One hand-off attempt, as facts rather than as a message.
     *
     * @param released whether the project is now owned by nobody, as far as this
     *     attempt can tell. {@code false} means the hand-off did not happen —
     *     the caller decides whether that stops it.
     */
    public record DrainOutcome(Placement placement, boolean released, String message) {}

    /**
     * Hands the project off its pod: stop engines, snapshot the workspace, drop
     * the lease.
     *
     * <p>Two steps, and the first one is not ours: the release has to reach the
     * holding pod, because it tears down in-memory state that exists only
     * there.
     */
    public DrainOutcome drain(String tenant, String name) {
        HomeLookup home = home(tenant, name);
        if (home.holder() == Holder.NONE) {
            return new DrainOutcome(Placement.NOT_PLACED, true,
                    "nothing to drain — " + home.detail());
        }
        if (home.holder() == Holder.UNREACHABLE) {
            return new DrainOutcome(Placement.UNKNOWN, false,
                    "(cannot resolve the home pod: " + home.detail() + ")");
        }
        if (home.holder() == Holder.UNREADABLE) {
            return new DrainOutcome(Placement.UNKNOWN, false,
                    "(unreadable home-pod response: " + home.detail() + ")");
        }
        String nodeName = home.nodeName() == null ? String.valueOf(home.endpoint()) : home.nodeName();
        Response released;
        try {
            released = brainClient.internalAt(
                    normaliseBase(String.valueOf(home.endpoint())),
                    "/internal/cluster/release", "POST", json(tenant, name));
        } catch (BrainCallException e) {
            // The holder is unreachable — which is exactly the situation the
            // whole "refuse unless --force" policy exists for, and it used to
            // throw straight past it: a hard-killed pod leaves a live lease, so
            // this is the common case, not an exotic one. Reported as PLACED
            // and not released, so drainBefore blocks and the operator gets the
            // sentence naming --force.
            return new DrainOutcome(Placement.PLACED, false,
                    "(drain failed on '" + nodeName + "': " + e.getMessage() + ")");
        }
        if (released.statusCode() == 409) {
            // The lease moved or expired between the lookup and here. Not a
            // clean hand-off and not a safe "nobody owns it": another pod may
            // have taken it. Reported as unknown so a caller that needs the
            // project quiet stops rather than guessing.
            return new DrainOutcome(Placement.UNKNOWN, false,
                    "pod '" + nodeName + "' does not hold it (any more) — nothing drained,"
                            + " retry to reach the current holder");
        }
        if (!released.isSuccess()) {
            return new DrainOutcome(Placement.PLACED, false,
                    "(drain failed on '" + nodeName + "': HTTP " + released.statusCode()
                            + " " + released.body() + ")");
        }
        return new DrainOutcome(Placement.PLACED, true,
                "drained from '" + nodeName + "' — status unchanged, nobody owns it now");
    }

    // ─── drain as a precondition ────────────────────────────────────────────

    /** What the pre-maintenance hand-off decided. */
    public enum DrainVerdict {
        /** Not attempted, because the caller asked for that. */
        SKIPPED,
        /** Handed off; the operation may proceed. */
        RELEASED,
        /** Failed, but the caller allowed proceeding anyway. */
        FORCED,
        /** Failed; the operation must not proceed. */
        BLOCKED
    }

    /**
     * @param wasPlaced whether a pod held the project — the question that
     *     decides whether a rename places it again afterwards
     * @param outcome the attempt itself, {@code null} when it was skipped
     */
    public record DrainDecision(
            DrainVerdict verdict, boolean wasPlaced, @Nullable DrainOutcome outcome) {

        /** Whether the operation this ran ahead of must stop. */
        public boolean abort() {
            return verdict == DrainVerdict.BLOCKED;
        }
    }

    /**
     * Hands the project off its pod ahead of a delete or rename.
     *
     * <p><b>Why this is the default and not an option.</b> A project on a pod is
     * being worked on: engines running, workspace mounted on that machine,
     * sessions open. Deleting or renaming underneath it does not fail loudly, it
     * leaves a process operating on data that no longer exists. Draining first
     * turns that into an orderly shutdown — and it does two more things worth
     * having:
     *
     * <ul>
     *   <li>The lease is gone afterwards, so the maintenance service's own guard
     *       passes without forcing. Forcing becomes what it should be: the
     *       exception, for a holder that cannot be reached.</li>
     *   <li>The workspace is snapshotted into Mongo <em>by the pod that has
     *       it</em>. That is the only way a rename can carry a work area that
     *       lives on another machine's disk — the snapshot rows travel with the
     *       project, and the next placement recovers the folder under the new
     *       name.</li>
     * </ul>
     *
     * <p>A failed drain blocks unless {@code force}: not knowing whether a pod
     * is still working on the project is exactly the situation where proceeding
     * is unsafe.
     */
    public DrainDecision drainBefore(String tenant, String name, boolean noDrain, boolean force) {
        if (noDrain) {
            return new DrainDecision(DrainVerdict.SKIPPED, false, null);
        }
        DrainOutcome outcome = drain(tenant, name);
        if (outcome.released()) {
            return new DrainDecision(DrainVerdict.RELEASED,
                    outcome.placement() == Placement.PLACED, outcome);
        }
        return new DrainDecision(
                force ? DrainVerdict.FORCED : DrainVerdict.BLOCKED, false, outcome);
    }

    // ─── placement selector ─────────────────────────────────────────────────
    //
    // Over REST, not straight to ProjectService: the brain owns the write, and
    // PodSelector.validate sits on that path. A selector written past it is one
    // no pod label can ever match.
    //
    // Via /internal/**, not the tenant admin route, although both exist and do
    // the same thing. The admin route belongs to a tenant administrator and is
    // reachable from the Web-UI with a user token; this one belongs to the
    // infrastructure actor that also labels the pods, which holds one
    // credential for the whole cluster rather than one per tenant. anus is that
    // actor.

    public record SelectorWrite(boolean success, int statusCode, String detail) {}

    /**
     * Writes the placement selector and/or the resource score.
     *
     * @param selector the whole new selector, or {@code null} to leave it
     *     alone. An empty map clears it.
     * @param score the new {@code homeResourceScore}, or {@code null} to leave
     *     it alone
     */
    public SelectorWrite writePlacement(
            String tenant,
            String name,
            @Nullable Map<String, String> selector,
            @Nullable Integer score) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenant);
        payload.put("projectName", name);
        if (selector != null) {
            payload.put("placementSelector", selector);
        }
        if (score != null) {
            payload.put("homeResourceScore", score);
        }
        Response response = brainClient.internal(
                "/internal/cluster/projects/placement", "POST",
                objectMapper.writeValueAsString(payload));
        return new SelectorWrite(response.isSuccess(), response.statusCode(), response.body());
    }

    // ─── lifecycle override ─────────────────────────────────────────────────

    public record LifecycleWrite(boolean success, int statusCode, String detail) {}

    /**
     * Sets the lifecycle override — {@code AUTO}, {@code EPHEMERAL} or
     * {@code PERMANENT}.
     *
     * <p>The value is passed through as text and validated by the brain, not
     * here: the set of modes is its enum, and a second copy of the list in the
     * shell would be a second thing to forget when it grows. What the shell
     * does check is the <em>option grammar</em> — that a value was given at all.
     *
     * <p>This is an operator knob and lives at {@code /internal/**} for that
     * reason; see the endpoint's own javadoc for why it is not a tenant's
     * decision.
     */
    public LifecycleWrite writeLifecycleType(String tenant, String name, String lifecycleType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenant);
        payload.put("projectName", name);
        payload.put("lifecycleType", lifecycleType);
        Response response = brainClient.internal(
                "/internal/cluster/projects/lifecycle-type", "POST",
                objectMapper.writeValueAsString(payload));
        return new LifecycleWrite(response.isSuccess(), response.statusCode(), response.body());
    }

    /** The selector as a map, never {@code null} — the one place that decides. */
    public static Map<String, String> selectorOf(ProjectDocument project) {
        Map<String, String> selector = project.getPlacementSelector();
        return selector == null ? Map.of() : selector;
    }

    /**
     * The selector with {@code keys} taken out, plus the keys that were not
     * there to begin with.
     *
     * <p>The second half is the reason this is a method: removing a key that is
     * absent reaches the desired state, so it must not fail — but it should be
     * <em>said</em>, or a typo looks like a successful removal.
     */
    public static SelectorRemoval withoutKeys(ProjectDocument project, List<String> keys) {
        Map<String, String> target = new TreeMap<>(selectorOf(project));
        List<String> notFound = new java.util.ArrayList<>();
        for (String key : keys) {
            if (target.remove(key) == null) {
                notFound.add(key);
            }
        }
        return new SelectorRemoval(target, List.copyOf(notFound));
    }

    public record SelectorRemoval(Map<String, String> target, List<String> keysNotFound) {}

    // ─── shared plumbing ────────────────────────────────────────────────────

    private String json(String tenant, String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenant);
        payload.put("projectName", name);
        return objectMapper.writeValueAsString(payload);
    }

    private static String homePath(String tenant, String name) {
        return "/internal/cluster/projects/home?tenantId=" + tenant + "&projectName=" + name;
    }

    /** {@code host:port} from a pod row to an absolute URL. */
    private static String normaliseBase(String endpoint) {
        return endpoint.startsWith("http://") || endpoint.startsWith("https://")
                ? endpoint : "http://" + endpoint;
    }
}
