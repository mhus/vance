package de.mhus.vance.shared.user.maintenance;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One entity's answer to what happens to it when a user is deleted or renamed.
 *
 * <p>The counterpart of {@code ProjectDataHandler}, and deliberately the same
 * shape — one mental model, one report, one drift test. It talks to Mongo
 * directly and is the same bounded exception to the data-sovereignty rule: it
 * lives in the package of the entity it speaks for, and it answers only the
 * maintenance questions ({@code CLAUDE.md} → Datenhoheit). The single
 * exception is the hub-project handler, which lives with the collector it
 * needs and says so.
 *
 * <h2>A user reference is not one kind of thing</h2>
 *
 * <p>This is where the user side genuinely differs from the project side. A
 * project name appears in fields that all mean "belongs to". A <em>user</em>
 * name appears in three kinds of field, and one policy for all three would be
 * wrong in two of them:
 *
 * <ul>
 *   <li>{@link UserReference#OWNED} — rows that exist <em>because of</em> that
 *       user and mean nothing without them (their sessions, their settings,
 *       their hub project). <b>Deleted.</b></li>
 *   <li>{@link UserReference#RECORD} — the record of something they did
 *       (authored a message, created a document, acted in the feed).
 *       <b>Tombstoned</b> to {@code _deleted_<name>} — see
 *       {@link UserTombstone} for why that beats both leaving it and blanking
 *       it.</li>
 *   <li><b>Authority and addressability</b> — a grant, an open decision
 *       assigned to them, an unread marker. <b>Removed or reassigned, never
 *       renamed:</b> a name comes back, and a leftover grant is inherited by
 *       the next account minted under it (the hazard
 *       {@code UserLifecycleListener} already names). These handlers are custom
 *       rather than field-mapped, because taking authority away is not a field
 *       rewrite.</li>
 * </ul>
 *
 * <p>The class is stated per handler, not centrally: only the owner of an
 * entity knows which of its fields is which, and several entities have fields
 * of two classes at once.
 *
 * <p><b>Idempotent, always.</b> A delete that half-ran is re-run by the
 * operator; neither operation may fail because the work was already done.
 *
 * <p>Spec: {@code specification/public/user-maintenance.md}.
 */
public interface UserDataHandler {

    /** Stable name for reports and logs — kebab-case. */
    String id();

    /**
     * Which Mongo collections this handler answers for. Read by the coverage
     * probe, which subtracts the union of all handlers from what the database
     * actually holds.
     */
    Set<String> collections();

    /**
     * Sort index within a run, ascending; equal values mean "order between
     * these two does not matter".
     *
     * <p>No default, for the same reason as on the project side: an inherited
     * number puts a new handler somewhere in the middle by accident, and the
     * case where that is fatal — a cascade that must precede its parent — is
     * exactly the one where the accident is invisible.
     *
     * <p>The block of ordinary handlers is numbered from {@value #FIRST_ORDER}
     * in steps of {@value #ORDER_STEP}; below {@value #FIRST_ORDER} is the free
     * range for handlers that must run before everything.
     */
    int order();

    /** Where the block of ordinary handlers starts; below is the free range. */
    int FIRST_ORDER = 100;

    /** Distance between two handlers, so a new one fits between them. */
    int ORDER_STEP = 100;

    /** How many rows this handler holds for {@code userName}. */
    long count(String tenantId, String userName);

    /**
     * Apply this handler's deletion policy for {@code userName} and return how
     * many rows changed — removed, tombstoned or stripped, whichever this
     * entity's class calls for.
     */
    long delete(String tenantId, String userName);

    /**
     * Carry this handler's references from {@code userName} to
     * {@code newUserName} — a real rename, where the person is the same and
     * only the login changed.
     *
     * <p>Default is "nothing to carry", for an entity reached through a parent.
     * Note that a rename touches <em>authority</em> too, unlike a delete: the
     * subject of a grant is still the same person, so the grant moves with the
     * name instead of being revoked.
     */
    default long rename(String tenantId, String userName, String newUserName) {
        return 0;
    }

    /**
     * Something about the delete the row count cannot say, or {@code null}.
     * Called <em>before</em> the delete, so it can still count what it is about
     * to leave behind or hand over.
     */
    default @Nullable String deleteNote(String tenantId, String userName) {
        return null;
    }

    /**
     * Why this user must not be deleted, or {@code null}.
     *
     * <p>Asked for every handler before the first one writes. The project SPI
     * has no counterpart on purpose: there, "is anyone still working with this"
     * is one question about the pod lease and is answered centrally. A user can
     * be in use in ways only the using subsystem knows — a running Trillian
     * authenticates as its service account, and deleting that account leaves an
     * agent whose every tool call is denied in a way that reads like a
     * permission bug.
     */
    default @Nullable String deleteBlocker(String tenantId, String userName) {
        return null;
    }

    /** Why this handler cannot carry the rename, or {@code null}. */
    default @Nullable String renameBlocker(
            String tenantId, String userName, String newUserName) {
        return null;
    }
}
