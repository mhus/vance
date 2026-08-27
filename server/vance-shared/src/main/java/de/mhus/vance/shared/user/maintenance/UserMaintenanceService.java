package de.mhus.vance.shared.user.maintenance;

import de.mhus.vance.shared.maintenance.MaintenanceReport;
import de.mhus.vance.shared.maintenance.MaintenanceReport.EntityResult;
import de.mhus.vance.shared.maintenance.MaintenanceReport.Operation;
import de.mhus.vance.shared.maintenance.MaintenanceReport.UnaccountedCollection;
import de.mhus.vance.shared.user.UserService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The one place that runs a service task across everything an account touched:
 * inspect, delete, rename.
 *
 * <p>Counterpart to {@code ProjectMaintenanceService}, same structure — one
 * {@link UserDataHandler} per entity, ascending {@link UserDataHandler#order()},
 * the same failure policy, the same coverage probe. What differs is what
 * "delete" means per entity, which is the handler's decision and is described
 * on {@link UserDataHandler}.
 *
 * <h2>Failure policy</h2>
 *
 * <p>A handler that throws does not stop the run, but the account document is
 * only removed when <em>every</em> handler succeeded. Same invariant as on the
 * project side, and here it is sharper: the account name is how the leftovers
 * are found, and it is also what a future account could reclaim. Leaving the
 * document in place keeps the name taken until the cleanup is finished.
 *
 * <p>Spec: {@code specification/public/user-maintenance.md}.
 */
@Service
@Slf4j
public class UserMaintenanceService {

    /** Sampled documents per unclaimed collection — see the project probe. */
    private static final int PROBE_SAMPLE_SIZE = 20;

    /**
     * Field names the coverage probe looks for, because a user name has no
     * single conventional home the way {@code projectId} does.
     *
     * <p>Which makes the probe weaker here than on the project side, and the
     * weakness is worth naming: a collection that stores a user under some
     * other field name escapes it. The build-time drift test is the other half
     * — it scans the mapped document classes and does not depend on this list.
     */
    private static final List<String> USER_FIELDS = List.of(
            "userId", "createdBy", "actor", "accountId", "senderUserId", "fromUser",
            "assignedToUserId", "originatorUserId", "subjectId", "requestedBy",
            "authorUserId", "peerUserId");

    private final List<UserDataHandler> handlers;
    private final UserService userService;
    private final MongoTemplate mongoTemplate;

    public UserMaintenanceService(
            List<UserDataHandler> handlers,
            UserService userService,
            MongoTemplate mongoTemplate) {
        this.handlers = handlers.stream()
                .sorted(Comparator.comparingInt(UserDataHandler::order)
                        .thenComparing(UserDataHandler::id))
                .toList();
        this.userService = userService;
        this.mongoTemplate = mongoTemplate;
    }

    /** The registered handlers, in execution order. */
    public List<UserDataHandler> handlers() {
        return handlers;
    }

    // ─── Inspect ───────────────────────────────────────────────────────────

    /** Counts what the account touched, writing nothing. */
    public MaintenanceReport inspect(String tenantId, String userName) {
        requireExists(tenantId, userName);
        List<EntityResult> entities = new ArrayList<>();
        for (UserDataHandler handler : handlers) {
            entities.add(run(handler, () -> handler.count(tenantId, userName)));
        }
        return new MaintenanceReport(tenantId, userName, Operation.INSPECT,
                entities, unaccountedCollections(tenantId, userName));
    }

    // ─── Delete ────────────────────────────────────────────────────────────

    /**
     * Removes the account: its own data goes, its authority goes, and what it
     * did is tombstoned. Irreversible.
     *
     * @param force skip the handlers' {@link UserDataHandler#deleteBlocker}s —
     *     for a blocker whose subject is known to be gone
     * @throws UserInUseException if a handler blocks and {@code force} is false
     * @throws UserService.UserNotFoundException if the account does not exist
     */
    public MaintenanceReport delete(String tenantId, String userName, boolean force) {
        requireExists(tenantId, userName);
        if (!force) {
            List<String> blockers = blockers(tenantId, userName);
            if (!blockers.isEmpty()) {
                throw new UserInUseException(blockers);
            }
        }

        List<UnaccountedCollection> unaccounted = unaccountedCollections(tenantId, userName);
        List<EntityResult> entities = new ArrayList<>();
        boolean allSucceeded = true;
        for (UserDataHandler handler : handlers) {
            String note = deleteNote(handler, tenantId, userName);
            EntityResult result = run(handler, () -> handler.delete(tenantId, userName));
            boolean succeeded = result.note() == null;
            allSucceeded &= succeeded;
            entities.add(succeeded && note != null
                    ? new EntityResult(result.handlerId(), result.collections(),
                            result.affected(), note)
                    : result);
        }
        if (allSucceeded) {
            // Fires UserLifecycleListener, which is how a permission provider
            // clears whatever its own handler did not reach.
            userService.delete(tenantId, userName);
            entities.add(EntityResult.of("user", Set.of("users"), 1));
        } else {
            entities.add(new EntityResult("user", Set.of("users"), 0,
                    "kept — an entity failed, re-run the delete to finish"));
            log.warn("User '{}/{}' delete incomplete — account document kept",
                    tenantId, userName);
        }
        return new MaintenanceReport(tenantId, userName, Operation.DELETE,
                entities, unaccounted);
    }

    // ─── Rename ────────────────────────────────────────────────────────────

    /**
     * Carries the login to {@code newUserName}. The person is the same, so
     * <em>everything</em> moves — including authority: a grant follows its
     * subject rather than being revoked, which is the one place a rename is not
     * simply a milder delete.
     *
     * @throws RenameBlockedException if any handler cannot carry the rename
     */
    public MaintenanceReport rename(String tenantId, String userName, String newUserName) {
        requireExists(tenantId, userName);
        if (userName.equals(newUserName)) {
            throw new IllegalArgumentException("User is already called '" + newUserName + "'");
        }
        if (userService.existsByTenantAndName(tenantId, newUserName)) {
            throw new UserService.UserAlreadyExistsException(
                    "User '" + newUserName + "' already exists in tenant '" + tenantId + "'");
        }
        if (UserTombstone.isTombstone(newUserName)) {
            // The marker means "this name belonged to somebody who is gone".
            // Handing it to a live account would make every future reader of
            // those fields wrong about which is which.
            throw new UserService.ReservedNameException(
                    "User name '" + newUserName + "' starts with the tombstone prefix '"
                            + UserTombstone.PREFIX + "' — that marker belongs to deleted"
                            + " accounts");
        }

        List<String> blockers = new ArrayList<>();
        for (UserDataHandler handler : handlers) {
            String blocker;
            try {
                blocker = handler.renameBlocker(tenantId, userName, newUserName);
            } catch (RuntimeException e) {
                blocker = "could not be asked: " + e;
            }
            if (blocker != null) {
                blockers.add(handler.id() + ": " + blocker);
            }
        }
        if (!blockers.isEmpty()) {
            throw new RenameBlockedException(blockers);
        }

        List<EntityResult> entities = new ArrayList<>();
        for (UserDataHandler handler : handlers) {
            entities.add(run(handler, () -> handler.rename(tenantId, userName, newUserName)));
        }
        // Last: while the document still says the old name, a half-finished
        // rename is at least addressable under it.
        userService.rename(tenantId, userName, newUserName);
        entities.add(EntityResult.of("user", Set.of("users"), 1));

        return new MaintenanceReport(tenantId, userName, Operation.RENAME,
                entities, unaccountedCollections(tenantId, newUserName));
    }

    // ─── Coverage probe ────────────────────────────────────────────────────

    /** Collections holding rows for this user that no handler claims. */
    public List<UnaccountedCollection> unaccountedCollections(String tenantId, String userName) {
        Set<String> claimed = new HashSet<>();
        for (UserDataHandler handler : handlers) {
            claimed.addAll(handler.collections());
        }
        claimed.add("users");

        List<UnaccountedCollection> found = new ArrayList<>();
        for (String collection : mongoTemplate.getCollectionNames()) {
            if (claimed.contains(collection)) {
                continue;
            }
            List<String> present = userFieldsPresentIn(collection);
            if (present.isEmpty()) {
                continue;
            }
            Criteria[] any = present.stream()
                    .map(field -> Criteria.where(field).is(userName))
                    .toArray(Criteria[]::new);
            long count = mongoTemplate.count(
                    new Query(new Criteria().orOperator(any)), collection);
            if (count > 0) {
                found.add(new UnaccountedCollection(collection, count));
                log.warn("Collection '{}' holds {} row(s) naming user '{}/{}' but no"
                                + " UserDataHandler claims it — add one, or that reference"
                                + " outlives the account",
                        collection, count, tenantId, userName);
            }
        }
        return found;
    }

    /** Which of {@link #USER_FIELDS} a sample of the collection actually uses. */
    private List<String> userFieldsPresentIn(String collection) {
        List<Document> sample = mongoTemplate.find(
                new Query().limit(PROBE_SAMPLE_SIZE), Document.class, collection);
        return USER_FIELDS.stream()
                .filter(field -> sample.stream().anyMatch(doc -> doc.containsKey(field)))
                .toList();
    }

    // ─── Guards ────────────────────────────────────────────────────────────

    private void requireExists(String tenantId, String userName) {
        if (userService.findByTenantAndName(tenantId, userName).isEmpty()) {
            throw new UserService.UserNotFoundException(
                    "User '" + userName + "' not found in tenant '" + tenantId + "'");
        }
    }

    private List<String> blockers(String tenantId, String userName) {
        List<String> blockers = new ArrayList<>();
        for (UserDataHandler handler : handlers) {
            String blocker;
            try {
                blocker = handler.deleteBlocker(tenantId, userName);
            } catch (RuntimeException e) {
                blocker = "could not be asked: " + e;
            }
            if (blocker != null) {
                blockers.add(handler.id() + ": " + blocker);
            }
        }
        return blockers;
    }

    private @Nullable String deleteNote(
            UserDataHandler handler, String tenantId, String userName) {
        try {
            return handler.deleteNote(tenantId, userName);
        } catch (RuntimeException e) {
            log.warn("Delete note from handler '{}' failed: {}", handler.id(), e.toString());
            return null;
        }
    }

    private EntityResult run(UserDataHandler handler, HandlerCall call) {
        try {
            return EntityResult.of(handler.id(), handler.collections(), call.execute());
        } catch (RuntimeException e) {
            log.error("User maintenance handler '{}' failed", handler.id(), e);
            return new EntityResult(handler.id(), handler.collections(), 0,
                    "FAILED: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface HandlerCall {
        long execute();
    }

    /** Something is still running as this account. */
    public static class UserInUseException extends RuntimeException {
        private final List<String> blockers;

        public UserInUseException(List<String> blockers) {
            super("Account is in use: " + String.join("; ", blockers));
            this.blockers = List.copyOf(blockers);
        }

        public List<String> blockers() {
            return blockers;
        }
    }

    /** At least one entity cannot carry the rename; nothing was written. */
    public static class RenameBlockedException extends RuntimeException {
        private final List<String> blockers;

        public RenameBlockedException(List<String> blockers) {
            super("Rename blocked: " + String.join("; ", blockers));
            this.blockers = List.copyOf(blockers);
        }

        public List<String> blockers() {
            return blockers;
        }
    }
}
