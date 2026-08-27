package de.mhus.vance.simpleauth;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * The account's grants and grant requests — what it was <em>allowed</em> to do.
 *
 * <p>OWNED, and it is the case the whole three-class distinction exists for. A
 * grant keys on the user <em>name</em>, and a name comes back: humans inherit a
 * predecessor's login, service accounts follow a scheme. Tombstoning here would
 * be silently catastrophic — the reference would survive as
 * {@code _deleted_mhus}, look harmless, and the real hazard is the other
 * direction: leaving {@code mhus} standing hands the next account under that
 * login everything this one was granted. {@code UserLifecycleListener} already
 * names that hazard; this handler is the same answer inside the maintenance
 * run, so the report shows it happened.
 *
 * <p>Belongs to the addon rather than the core because which authorization
 * provider a deployment runs is a choice, and only the provider knows where its
 * grants live. An enterprise governor contributes its own handler, or none,
 * because its grants are outside Vance.
 *
 * <p><b>On rename everything moves.</b> The subject is the same person, so the
 * grant follows the name — the one place a rename is not a milder delete.
 *
 * <p>Only the {@code subjectId} is OWNED. {@code createdBy} — who granted it —
 * is a record and is handled by {@link PermissionAuthorUserDataHandler}.
 */
@Component
public class PermissionSubjectUserDataHandler extends MappedUserDataHandler {

    public PermissionSubjectUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "permission-grants";
    }

    @Override
    public int order() {
        return 1200;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(PermissionGrantDocument.class, PermissionRequestDocument.class);
    }

    @Override
    protected String userField() {
        return "subjectId";
    }

    @Override
    protected UserReference reference() {
        return UserReference.OWNED;
    }

    @Override
    protected Query scope(String tenantId, String userName) {
        // Subject type as well: a grant to a TEAM whose name happens to equal a
        // login is somebody else's authority.
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("subjectType").is(GrantSubjectType.USER)
                .and("subjectId").is(userName));
    }
}
