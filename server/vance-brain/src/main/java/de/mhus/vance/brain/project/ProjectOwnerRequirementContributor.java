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

    /** Whether the project holds waiting work of this contributor's kind. */
    boolean requiresOwner(String tenantId, String projectId);
}
