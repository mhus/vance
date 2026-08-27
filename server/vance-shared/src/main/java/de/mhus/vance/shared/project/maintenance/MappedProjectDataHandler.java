package de.mhus.vance.shared.project.maintenance;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The common case: one or more collections whose rows carry the project in a
 * plain field, so counting is a query, deleting is a {@code remove} and
 * renaming is an {@code updateMulti}.
 *
 * <p>Most entities are this case, and writing it out per entity would be the
 * same twenty lines twenty times — with twenty chances to forget the tenant in
 * the predicate. A subclass names its document classes and, where the field
 * names differ from the convention, overrides {@link #tenantField()} /
 * {@link #projectField()}; {@link #collections()} is then derived from the
 * mapping rather than restated, so it cannot drift away from what is actually
 * written to.
 *
 * <p>Entities that need more than that — blobs to release, directories to move,
 * rows reached through a parent — implement {@link ProjectDataHandler} directly.
 *
 * <p>Note that {@link ProjectDataHandler#order()} is <b>not</b> implemented
 * here: every subclass states its own sort index, because inheriting one would
 * hide the single relation that matters (see that method).
 */
public abstract class MappedProjectDataHandler implements ProjectDataHandler {

    protected final MongoTemplate mongoTemplate;

    protected MappedProjectDataHandler(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * The document classes this handler owns. More than one when the entity is
     * genuinely several collections of the same shape — a Magrathea run is its
     * tasks, its journal and its timers, and splitting those into three
     * handlers would only produce three lines in every report.
     */
    protected abstract List<Class<?>> entityTypes();

    /** Field carrying the tenant. {@code workspace_snapshots} says {@code tenant}. */
    protected String tenantField() {
        return "tenantId";
    }

    /** Field carrying the project name. */
    protected String projectField() {
        return "projectId";
    }

    @Override
    public Set<String> collections() {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> type : entityTypes()) {
            names.add(mongoTemplate.getCollectionName(type));
        }
        return names;
    }

    @Override
    public long count(String tenantId, String projectId) {
        long total = 0;
        for (Class<?> type : entityTypes()) {
            total += mongoTemplate.count(scope(tenantId, projectId), type);
        }
        return total;
    }

    @Override
    public long delete(String tenantId, String projectId) {
        long total = 0;
        for (Class<?> type : entityTypes()) {
            total += mongoTemplate.remove(scope(tenantId, projectId), type).getDeletedCount();
        }
        return total;
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        Update update = new Update().set(projectField(), newProjectId);
        long total = 0;
        for (Class<?> type : entityTypes()) {
            total += mongoTemplate.updateMulti(scope(tenantId, projectId), update, type)
                    .getModifiedCount();
        }
        return total;
    }

    /**
     * The predicate every operation shares. Tenant is always part of it: a
     * project name is unique inside a tenant and nowhere else, so a
     * project-only match would reach into a neighbour with the same name.
     */
    protected Query scope(String tenantId, String projectId) {
        return new Query(Criteria.where(tenantField()).is(tenantId)
                .and(projectField()).is(projectId));
    }
}
