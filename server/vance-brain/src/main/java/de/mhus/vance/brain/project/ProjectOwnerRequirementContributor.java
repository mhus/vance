package de.mhus.vance.brain.project;

/**
 * Waiting work that is not a document. {@link ProjectOwnerRequirementService}
 * derives {@code ownerRequired} from scheduler and hook documents; a subsystem
 * whose waiting work lives elsewhere — a self-waking process, say — answers
 * here instead, and asks for a {@link ProjectOwnerRequirementService#recompute}
 * whenever its answer may have changed.
 *
 * <p>The same test as for documents applies: something has to be armed and
 * waiting, with nothing external arriving to remind the cluster. Reactive
 * work does not count.
 */
public interface ProjectOwnerRequirementContributor {

    /** A project, addressed by tenant and name. */
    record ProjectRef(String tenantId, String projectId) {}

    /** Whether the project holds waiting work of this contributor's kind. */
    boolean requiresOwner(String tenantId, String projectId);

    /**
     * Every project that currently holds waiting work of this contributor's
     * kind — for the boot re-check that <em>pins</em>
     * ({@link ProjectOwnerRequirementService#pinContributedWork}). Document
     * work needs no such list: its first document fires a change event. Work
     * that is not a document may have existed before the contributor did, and
     * then no event ever asks for the flag.
     *
     * <p>Default: none — a contributor whose answer is always reached through
     * a recompute it triggers itself does not have to enumerate.
     */
    default java.util.List<ProjectRef> projectsWithWork() {
        return java.util.List.of();
    }
}
