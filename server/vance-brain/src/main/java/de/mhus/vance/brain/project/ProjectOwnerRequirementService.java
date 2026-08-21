package de.mhus.vance.brain.project;

import de.mhus.vance.brain.ursahooks.UrsaHookLoader;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.ursascheduler.UrsaSchedulerLoader;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Decides whether a project has to be kept on a live pod, and keeps the
 * derived {@code ProjectDocument.ownerRequired} flag in sync.
 *
 * <h2>Why derive it</h2>
 * A project needs an owner pod exactly when it holds background work that has
 * to keep running — and that is not a preference somebody states, it is a
 * consequence of what the project contains: scheduler entries and hooks. Their
 * configuration <em>is</em> documents, so their presence is the answer.
 *
 * <p>The alternative — an operator flag — was tried and failed silently:
 * {@code lifecycleType=PERMANENT} never got a writer at all, so every project
 * stayed on the default and both recovery paths selected on a value that never
 * existed ({@code planning/project-ownership-lease-design.md} §1.1). A knob you
 * can forget, whose failure mode is a scheduler that quietly stops, is the
 * wrong shape. {@code lifecycleType} survives as an explicit override for the
 * cases derivation cannot know.
 *
 * <p><b>Not the old {@code requiresOwnerPod}.</b> That flag was set by engine
 * lifecycle listeners, which made it circular: it was only set while the
 * project was loaded, so a project had to be running to be recognised as
 * needing to run. Deriving from documents has no loop — a scheduler document
 * exists whether or not its scheduler is registered anywhere.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectOwnerRequirementService {

    /**
     * Path prefixes whose presence means "this project has to keep running".
     * When a new kind of background work appears, it belongs in this list, and
     * that is the whole maintenance burden of the derivation.
     *
     * <p><b>The test is "does something have to be armed and waiting", not
     * "does it hang off {@code ProjectEnginesStartRequested}".</b> Four things
     * boot on that event; only two belong here.
     *
     * <ul>
     *   <li><b>Schedulers</b> — a cron that nobody armed does not fire. Nothing
     *       external arrives to remind the cluster, so the pod is the only
     *       reason it happens at all.</li>
     *   <li><b>Hooks</b> — {@code UrsaHookDispatcher} reads an in-memory
     *       registry, so a hook that is not registered anywhere cannot fire
     *       either.</li>
     *   <li><b>Event triggers</b> — <em>not</em> here: they are reactive. The
     *       call arrives from outside, and a pod that receives one can bring
     *       the project up then ({@code UrsaEventService} does). The cost is a
     *       cold start on the first trigger, which is the right trade against
     *       keeping every webhook-carrying project on a pod forever.</li>
     *   <li><b>Kit provisioning</b> — not here: it runs once when the project
     *       comes up and is then finished. Since kits are the ordinary way to
     *       set a project up, counting it would pin nearly every project in an
     *       installation and make the capacity model meaningless. Its periodic
     *       check covers the projects that are actually running
     *       ({@code KitProvisioningCheckTick}) — an available update for a
     *       project nobody runs is not news.</li>
     * </ul>
     *
     * <p>So the line is: <b>waiting</b> work needs a pod, <b>reactive</b> work
     * brings one when it is needed.
     */
    public static final List<String> ACTIVATION_SOURCE_PREFIXES = List.of(
            UrsaSchedulerLoader.SCHEDULER_PATH_PREFIX,
            UrsaHookLoader.HOOK_PATH_ROOT);

    /**
     * Only the files the loaders actually read count. A folder marker left by
     * a WebDAV {@code MKCOL}, or a {@code README.md} next to the real entries,
     * must not pin a project to a pod.
     */
    public static final String ACTIVATION_SOURCE_SUFFIX = ".yaml";

    private final DocumentService documentService;
    private final ProjectService projectService;

    /** True when {@code path} could change the answer for its project. */
    public static boolean isActivationSourcePath(String path) {
        if (path == null) return false;
        for (String prefix : ACTIVATION_SOURCE_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Recomputes the flag for one project and persists it if it changed.
     * Returns the value now in effect.
     *
     * <p>Podless projects are skipped: they never take a lease, so nothing
     * would read the flag for them, and their background work lives wherever
     * the user's WS lands by design.
     *
     * <p>Cost is one {@code exists} query, plus a conditional update that
     * writes nothing when the answer is unchanged — which is the normal case,
     * because most edits under these prefixes change a document that was
     * already there.
     */
    public boolean recompute(String tenantId, String projectId) {
        if (ProjectService.isPodless(projectId)) return false;
        boolean required = documentService.existsAnyUnderPrefixes(
                tenantId, projectId, ACTIVATION_SOURCE_PREFIXES, ACTIVATION_SOURCE_SUFFIX);
        if (projectService.setOwnerRequired(tenantId, projectId, required)) {
            log.info("Project '{}/{}' ownerRequired → {} (waiting work {})",
                    tenantId, projectId, required,
                    required ? "present" : "gone");
        }
        return required;
    }

    /**
     * Re-tests every project that is currently marked as needing an owner and
     * releases the ones that no longer qualify.
     *
     * <p>Two things make this necessary, and both are permanent rather than
     * one-off:
     * <ul>
     *   <li><b>Changes while the brain was down.</b> The flag is maintained
     *       from document events. Delete the last scheduler of a project with
     *       no pod running, and no event ever fires — the project would stay
     *       pinned forever.</li>
     *   <li><b>Changes to the rule itself.</b> Which paths count is a design
     *       decision that has already moved twice. A stored derivation has to
     *       be re-derivable, or every correction needs a migration to chase
     *       it.</li>
     * </ul>
     *
     * <p>Deliberately one-directional and therefore cheap: the candidate set
     * is the projects that are <em>already</em> pinned, which is small by
     * construction and index-backed. Newly qualifying projects are picked up by
     * the change event that created their first scheduler — and if that event
     * was missed while the brain was down, the project is dormant anyway and
     * gets its flag on the next edit or bring.
     *
     * @return how many projects were released
     */
    public int releaseNoLongerQualifying() {
        int released = 0;
        for (ProjectDocument project : projectService.findOwnerRequired()) {
            if (ProjectService.isPodless(project.getName())) {
                // A podless project can never take a lease, so the flag says
                // nothing true about it. It is inert — HOMELESS is never a
                // recovery candidate — but a field that is permanently wrong
                // is the kind of thing someone later reads and believes.
                if (projectService.setOwnerRequired(
                        project.getTenantId(), project.getName(), false)) {
                    log.info("Project '{}/{}' is podless — clearing ownerRequired",
                            project.getTenantId(), project.getName());
                }
                continue;
            }
            if (!recompute(project.getTenantId(), project.getName())) {
                released++;
            }
        }
        return released;
    }
}
