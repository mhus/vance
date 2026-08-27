package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.user.maintenance.UserDataHandler;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * <b>The drift guard for the user side.</b> Every persisted entity that names a
 * user must have a {@link UserDataHandler} behind it, or that reference
 * outlives the account — and the next login of that name inherits it.
 *
 * <p>Runs in the admin shell's context, the process with the narrowest
 * classpath: a handler that exists only in {@code vance-brain} is not available
 * to the operator running {@code user delete}.
 *
 * <p>Two layers, neither replacing the other: this asks the code at build time
 * and sees only mapped classes; {@code UserMaintenanceService}'s probe asks the
 * live database and catches collections written without a document class.
 *
 * <h2>If this test fails</h2>
 *
 * <p>Add a handler next to the entity, and decide its class while you do —
 * owned data goes, a record is tombstoned, authority is removed (see
 * {@code UserDataHandler}). If the field genuinely does not name an account,
 * add it to {@link #NOT_A_USER_REFERENCE} with the reason.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "de.flapdoodle.mongodb.embedded.version=7.0.12",
                "spring.mongodb.uri=",
                "spring.mongodb.database=vance-user-coverage",
                "spring.data.mongodb.auto-index-creation=false",
                "spring.shell.interactive.enabled=false",
                "vance.encryption.password=coverage-test",
                "vance.redis.enabled=false",
        })
class UserDataCoverageTest {

    private static final String SCAN_ROOT = "de.mhus.vance";

    /**
     * Fields that look like a user reference and are not. Each line is a
     * decision, not an exemption to be added lightly.
     */
    private static final TreeMap<String, String> NOT_A_USER_REFERENCE = new TreeMap<>();

    static {
        NOT_A_USER_REFERENCE.put("DatabaseIdentityDocument.owner",
                "names the application that claimed the database, not a person");
        NOT_A_USER_REFERENCE.put("SchemaMigrationLockDocument.owner",
                "names the pod holding the migration lease");
        NOT_A_USER_REFERENCE.put("LlmUsageDailyDocument.caller",
                "an engine or subsystem name, never a login");
        NOT_A_USER_REFERENCE.put("ImageCallRecord.caller",
                "an engine or subsystem name, never a login");
        NOT_A_USER_REFERENCE.put("StoreUserDocument.userId",
                "the marketplace's own account, not a Vance login");
        NOT_A_USER_REFERENCE.put("StoreSessionDocument.userId",
                "the marketplace's own account, not a Vance login");
        NOT_A_USER_REFERENCE.put("StoreLinkDocument.userId",
                "the marketplace's own account, not a Vance login");
        NOT_A_USER_REFERENCE.put("OrderDocument.userId",
                "the marketplace's own account, not a Vance login");
    }

    /** Field names that do refer to a Vance login. */
    private static final Set<String> USER_FIELDS = Set.of(
            "userId", "createdBy", "actor", "accountId", "senderUserId", "fromUser",
            "assignedToUserId", "originatorUserId", "requestedBy", "peerUserId");

    /** A scan that resolves nothing would report perfect coverage. */
    private static final int MINIMUM_SCANNED_ENTITIES = 8;

    @Autowired
    List<UserDataHandler> handlers;

    @Autowired
    MongoTemplate mongoTemplate;

    @Test
    void theScanItself_findsTheEntities() {
        assertThat(userReferencingEntities())
                .hasSizeGreaterThanOrEqualTo(MINIMUM_SCANNED_ENTITIES);
    }

    @Test
    void everyEntityNamingAUser_hasAHandler() {
        Set<String> claimed = new HashSet<>();
        for (UserDataHandler handler : handlers) {
            claimed.addAll(handler.collections());
        }
        claimed.add("users");

        List<String> uncovered = new ArrayList<>();
        for (Class<?> entity : userReferencingEntities()) {
            String collection = mongoTemplate.getCollectionName(entity);
            if (claimed.contains(collection)) {
                continue;
            }
            uncovered.add(entity.getName() + " (" + collection + ")");
        }

        assertThat(uncovered)
                .as("entities naming a user with no UserDataHandler — that reference would"
                        + " outlive the account and be inherited by the next login of that name")
                .isEmpty();
    }

    @Test
    void handlerIds_areUnique() {
        assertThat(handlers.stream().map(UserDataHandler::id).toList()).doesNotHaveDuplicates();
    }

    @Test
    void theHubProject_goesBeforeEverythingElse() {
        // It is the biggest piece, and taking it out first means every later
        // handler only deals with what the account left in other people's
        // projects — less work and a truer count.
        int hub = orderOf("hub-project");
        assertThat(handlers)
                .filteredOn(h -> !Set.of("hub-project", "trillian-account-guard").contains(h.id()))
                .allSatisfy(h -> assertThat(h.order())
                        .as("'%s' must not run before the hub project", h.id())
                        .isGreaterThan(hub));
    }

    @Test
    void cascadeHandlers_runBeforeTheSessionsTheyAreReachedThrough() {
        int sessions = orderOf("sessions");
        for (String cascade : List.of("chat-messages-of-sessions", "engine-messages-of-sessions",
                "marvin-nodes-of-sessions", "think-processes-of-sessions")) {
            assertThat(orderOf(cascade))
                    .as("%s is found through the account's sessions", cascade)
                    .isLessThan(sessions);
        }
    }

    @Test
    void grantsAreRemovedBeforeAuthorshipIsTombstoned() {
        // Two handlers on one collection with different classes: the subject of
        // a grant is authority and goes, its author is a record and stays.
        assertThat(orderOf("permission-grants"))
                .isLessThan(orderOf("permission-grant-authors"));
    }

    private int orderOf(String handlerId) {
        return handlers.stream()
                .filter(h -> h.id().equals(handlerId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no handler with id '" + handlerId + "'"))
                .order();
    }

    /** Mapped documents on this classpath that declare a field naming a user. */
    private static List<Class<?>> userReferencingEntities() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));
        List<Class<?>> found = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(SCAN_ROOT)) {
            String className = candidate.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException e) {
                continue;
            }
            if (namesAUser(type)) {
                found.add(type);
            }
        }
        return found;
    }

    private static boolean namesAUser(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!USER_FIELDS.contains(field.getName())) {
                    continue;
                }
                if (NOT_A_USER_REFERENCE.containsKey(
                        type.getSimpleName() + "." + field.getName())) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }
}
