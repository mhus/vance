package de.mhus.vance.shared.project.maintenance;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One entity's answer to the three questions a project-wide service task asks:
 * <em>how much of mine belongs to this project</em>, <em>remove it</em>, and
 * <em>the project is called something else now</em>.
 *
 * <p>Adding a project-scoped collection to the system means adding a handler;
 * no central list has to be edited, which is the whole point of the seam.
 *
 * <p><b>This is a deliberate exception to the data-sovereignty rule.</b> The
 * rule is that only the owning service talks to Mongo, and a handler talks to
 * Mongo directly. Two things keep the exception narrow: a handler lives in the
 * <em>same package</em> as the document, repository and service it speaks for,
 * so nobody reaches into foreign data — and it answers only the three
 * maintenance questions, not domain questions. The alternative was a
 * {@code deleteByProject} / {@code renameProject} pair on twenty services that
 * nothing else would ever call. (The admin shell is an exception at a larger
 * scale for the same reason: an admin tool is allowed to go at the database
 * directly, see {@code CLAUDE.md} → Datenhoheit.)
 *
 * <p><b>Silence must never mean "nothing there".</b> This is the same hazard
 * that {@link de.mhus.vance.shared.storage.StorageReferenceSource} guards
 * against from the other direction: a missing handler does not announce itself,
 * it just leaves rows behind that outlive the project and are inherited by the
 * next one created under the same name. {@link ProjectMaintenanceService}
 * therefore probes the database for collections no handler claims and reports
 * them; {@link #collections()} is what makes that probe possible.
 *
 * <p><b>Idempotent, always.</b> A delete that half-ran is re-run by the
 * operator, and a rename that failed in the middle is repeated with the same
 * arguments. Neither may fail because the work was already done.
 */
public interface ProjectDataHandler {

    /**
     * Stable name for reports and logs — kebab-case, singular or plural as the
     * entity reads best ({@code documents}, {@code think-processes}).
     */
    String id();

    /**
     * Which Mongo collections this handler answers for. Read by the coverage
     * probe, which subtracts the union of all handlers from what the database
     * actually holds — so an incomplete answer here re-opens exactly the hole
     * the probe exists to close.
     */
    Set<String> collections();

    /**
     * Sort index within a run, ascending.
     *
     * <p><b>Equal values are allowed</b> and mean the order between those two
     * handlers does not matter — which is the normal case. Only one relation is
     * load-bearing: a handler whose rows are found <em>through</em> another
     * entity must sort before it. Chat messages are reached via the project's
     * sessions, so the chat handler runs first; the other way round the sessions
     * are gone, the messages are unreachable, and <em>nothing reports a
     * problem</em>. That silent failure is why this has an explicit number
     * rather than a default.
     *
     * <p>The block of ordinary handlers is numbered from {@value #FIRST_ORDER}
     * in steps of {@value #ORDER_STEP} — see {@code project handlers} for the
     * current list. Two ranges are kept free on purpose:
     *
     * <ul>
     *   <li><b>Below {@value #FIRST_ORDER}</b> for anything that has to run
     *       before <em>every</em> entity, not just before one parent. The
     *       Trillian handler sits at 50 and is the case the range exists for:
     *       the name of the service account it deletes is recorded on the
     *       project's process rows and nowhere else.</li>
     *   <li><b>The gaps between the steps</b> for slotting a new handler
     *       between two existing ones without renumbering anything.</li>
     * </ul>
     *
     * <p>No default on purpose. An inherited number would put a new handler
     * somewhere in the middle by accident, and the one case that matters —
     * being a cascade — is exactly the one where the accident is invisible.
     *
     * <p>The project document itself is not a handler and carries no index:
     * {@link ProjectMaintenanceService} removes it last, and only once every
     * handler has succeeded.
     */
    int order();

    /** Where the block of ordinary handlers starts; below is the free range. */
    int FIRST_ORDER = 100;

    /** Distance between two handlers, so a new one fits between them. */
    int ORDER_STEP = 100;

    /** How many rows this handler holds for {@code projectId}. */
    long count(String tenantId, String projectId);

    /**
     * Remove everything this handler holds for {@code projectId} and return
     * how many rows went. Irreversible — the caller has already gated this.
     */
    long delete(String tenantId, String projectId);

    /**
     * Rewrite this handler's project references from {@code projectId} to
     * {@code newProjectId} and return how many rows changed.
     *
     * <p>Default is "nothing to carry": an entity whose rows are reached
     * through a parent (chat messages via their session) holds no project
     * reference of its own, so a rename passes it by. That is a real answer,
     * not a gap — which is why it is the default rather than an exception.
     */
    default long rename(String tenantId, String projectId, String newProjectId) {
        return 0;
    }

    /**
     * Something about the delete the row count cannot say, or {@code null}.
     *
     * <p>Exists for the handlers whose honest answer to "delete" is <em>I am
     * deliberately leaving this</em>. An inbox thread outlives the project it
     * was about, so its reference is not removed — and a report line reading
     * {@code 0} would be indistinguishable from "there was nothing". Called
     * <em>before</em> the delete, so it can still count what it is about to
     * leave behind.
     */
    default @Nullable String deleteNote(String tenantId, String projectId) {
        return null;
    }

    /**
     * Why this handler cannot carry the rename, or {@code null} when it can.
     *
     * <p>Asked for every handler <em>before</em> the first one writes, so a
     * rename that cannot complete does not start. The workspace handler uses it
     * for the case that decides the matter on disk: a directory already sitting
     * under the new name.
     */
    default @Nullable String renameBlocker(
            String tenantId, String projectId, String newProjectId) {
        return null;
    }
}
