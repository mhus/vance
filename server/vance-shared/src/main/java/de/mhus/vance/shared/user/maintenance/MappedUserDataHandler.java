package de.mhus.vance.shared.user.maintenance;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The common case: collections whose rows name the user in a plain field.
 *
 * <p>A subclass says which document classes, which field, and — the part that
 * matters — which {@link UserReference} the field is. Everything else follows:
 * {@code OWNED} deletes the rows, {@code RECORD} rewrites them to the
 * tombstone, and a rename is the same {@code updateMulti} in both cases.
 *
 * <p>That one declaration is what a reviewer should be able to read off a
 * handler in a second, which is why it is a method and not a comment.
 *
 * <p>{@link UserDataHandler#order()} is not implemented here: every subclass
 * states its own sort index.
 */
public abstract class MappedUserDataHandler implements UserDataHandler {

    protected final MongoTemplate mongoTemplate;

    protected MappedUserDataHandler(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** The document classes this handler owns. */
    protected abstract List<Class<?>> entityTypes();

    /** The field naming the user — {@code userId}, {@code createdBy}, … */
    protected abstract String userField();

    /** What that field means. Decides what {@link #delete} does. */
    protected abstract UserReference reference();

    /** Field carrying the tenant. */
    protected String tenantField() {
        return "tenantId";
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
    public long count(String tenantId, String userName) {
        long total = 0;
        for (Class<?> type : entityTypes()) {
            total += mongoTemplate.count(scope(tenantId, userName), type);
        }
        return total;
    }

    @Override
    public long delete(String tenantId, String userName) {
        return switch (reference()) {
            case OWNED -> removeRows(tenantId, userName);
            case RECORD -> rename(tenantId, userName, UserTombstone.of(userName));
        };
    }

    @Override
    public long rename(String tenantId, String userName, String newUserName) {
        Update update = new Update().set(userField(), newUserName);
        long total = 0;
        for (Class<?> type : entityTypes()) {
            total += mongoTemplate.updateMulti(scope(tenantId, userName), update, type)
                    .getModifiedCount();
        }
        return total;
    }

    private long removeRows(String tenantId, String userName) {
        long total = 0;
        for (Class<?> type : entityTypes()) {
            total += mongoTemplate.remove(scope(tenantId, userName), type).getDeletedCount();
        }
        return total;
    }

    /**
     * The predicate every operation shares. Tenant is always part of it: a user
     * name is unique inside a tenant and nowhere else.
     */
    protected Query scope(String tenantId, String userName) {
        return new Query(Criteria.where(tenantField()).is(tenantId)
                .and(userField()).is(userName));
    }
}
