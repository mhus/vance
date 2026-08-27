package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
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
 * <b>The drift guard.</b> Every persisted entity that carries a
 * {@code projectId} must have a {@link ProjectDataHandler} behind it, or a
 * project delete leaves its rows behind — and the next project created under
 * that name inherits them.
 *
 * <p>It runs here, in the admin shell's context, because this is the process
 * with the narrowest classpath: {@code vance-shared} plus addons, no brain. A
 * handler that exists only in {@code vance-brain} is not available to the
 * operator running {@code project delete}, so "covered" has to mean covered
 * <em>here</em>.
 *
 * <p>Two layers, and neither replaces the other: this test asks the code at
 * build time and can only see mapped classes, while
 * {@code ProjectMaintenanceService}'s probe asks the live database and catches
 * collections written without a document class at all.
 *
 * <h2>If this test fails</h2>
 *
 * <p>Add a {@code ProjectDataHandler} next to the entity — usually four lines
 * extending {@code MappedProjectDataHandler}. If the entity genuinely is not
 * project data despite the field, add it to {@link #NOT_PROJECT_DATA} with the
 * reason; an unexplained entry there is the failure this test exists to
 * prevent.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "de.flapdoodle.mongodb.embedded.version=7.0.12",
                "spring.mongodb.uri=",
                "spring.mongodb.database=vance-coverage",
                "spring.data.mongodb.auto-index-creation=false",
                "spring.shell.interactive.enabled=false",
                "vance.encryption.password=coverage-test",
                "vance.redis.enabled=false",
        })
class ProjectDataCoverageTest {

    /** Where mapped documents live. */
    private static final String SCAN_ROOT = "de.mhus.vance";

    /**
     * Entities with a {@code projectId} that are deliberately not handled, and
     * why. Each line is a decision, not an exemption to be added lightly.
     */
    private static final TreeMap<String, String> NOT_PROJECT_DATA = new TreeMap<>();

    static {
        // Nothing so far — every projectId-carrying entity has a handler.
    }

    @Autowired
    List<ProjectDataHandler> handlers;

    @Autowired
    MongoTemplate mongoTemplate;

    /**
     * Floor on what the scan must find. Without it this test passes loudest
     * when it is broken: a scanner that resolves nothing reports full coverage.
     */
    private static final int MINIMUM_SCANNED_ENTITIES = 15;

    @Test
    void theScanItself_findsTheEntities() {
        assertThat(projectScopedEntities())
                .as("classpath scan for projectId-carrying @Document classes — a scan that finds"
                        + " nothing would report perfect coverage")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_SCANNED_ENTITIES);
    }

    @Test
    void everyProjectScopedEntity_hasAHandler() {
        Set<String> claimed = new HashSet<>();
        for (ProjectDataHandler handler : handlers) {
            claimed.addAll(handler.collections());
        }

        List<String> uncovered = new ArrayList<>();
        for (Class<?> entity : projectScopedEntities()) {
            String collection = mongoTemplate.getCollectionName(entity);
            if (claimed.contains(collection)
                    || NOT_PROJECT_DATA.containsKey(entity.getSimpleName())) {
                continue;
            }
            uncovered.add(entity.getName() + " (" + collection + ")");
        }

        assertThat(uncovered)
                .as("entities carrying projectId with no ProjectDataHandler — their rows would"
                        + " outlive the project and be inherited by the next one of that name")
                .isEmpty();
    }

    @Test
    void trillianAccounts_sortBeforeEveryOtherHandler() {
        // The name of the service account a Trillian minted is recorded on the
        // project's process rows and nowhere else — there is no back-reference
        // from the user to the project. Any handler running first can take that
        // name away, and the account is then unreachable with nothing reporting
        // a problem.
        int trillian = orderOf("trillian-accounts");
        assertThat(handlers)
                .filteredOn(h -> !h.id().equals("trillian-accounts"))
                .allSatisfy(h -> assertThat(h.order())
                        .as("'%s' must not run before the Trillian account release", h.id())
                        .isGreaterThan(trillian));
    }

    @Test
    void handlerIds_areUnique() {
        // The id is what a report line is read by; two handlers sharing one
        // would make an operator's "did it run?" unanswerable.
        List<String> ids = handlers.stream().map(ProjectDataHandler::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void cascadeHandlers_runBeforeTheEntityTheyAreReachedThrough() {
        int sessions = orderOf("sessions");
        int thinkProcesses = orderOf("think-processes");
        for (String cascade : List.of("chat-messages")) {
            assertThat(orderOf(cascade))
                    .as("%s is found through the project's sessions", cascade)
                    .isLessThan(sessions);
        }
        for (String cascade :
                List.of("engine-messages", "marvin-nodes", "settings-process-scope")) {
            assertThat(orderOf(cascade))
                    .as("%s is found through the project's think processes", cascade)
                    .isLessThan(thinkProcesses);
        }
    }

    private int orderOf(String handlerId) {
        return handlers.stream()
                .filter(h -> h.id().equals(handlerId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no handler with id '" + handlerId + "'"))
                .order();
    }

    /** Mapped documents on this classpath that declare a {@code projectId}. */
    private static List<Class<?>> projectScopedEntities() {
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
            if (hasProjectIdField(type)) {
                found.add(type);
            }
        }
        return found;
    }

    private static boolean hasProjectIdField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if ("projectId".equals(field.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
