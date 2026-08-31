package de.mhus.vance.shared.integration;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * A token pinned to a project cannot do anything anywhere else — the
 * confinement in {@code PermissionService} denies every resource outside it —
 * so once the project is gone the row is a credential with no reachable target.
 *
 * <p>Deleting it <em>is</em> revoking it: an unknown {@code jti} is treated as
 * revoked, so the token stops working the moment the row goes. Leaving it
 * instead would be the inheritance problem this whole sweep exists for, in its
 * sharpest form — the next project of the same name would be born with somebody
 * else's live credential already pointing at it.
 *
 * <p><b>Rename revokes.</b> A token's pin lives in its signed claims and cannot
 * follow a rename — so after one, the claim names a project that is gone. The
 * inherited field update alone would leave a row that looks live in the owner's
 * list while every call is denied, and worse: create a project with the old name
 * again and the untouched token would confine to <em>that</em> one, which is the
 * inheritance problem this whole sweep exists for. {@code IntegrationTokenService}
 * refuses a claim whose pin no longer matches its row, which already closes the
 * hole; stamping {@code revokedAt} on top is what makes the list say so instead
 * of leaving the owner to work it out.
 *
 * <p>Tokens with no project pin (profiles that declare
 * {@code requiresProject() == false}) carry {@code projectId == null} and are
 * matched by neither operation, which is correct: they never belonged to this
 * project in the first place.
 */
@Component
public class IntegrationTokenProjectDataHandler extends MappedProjectDataHandler {

    public IntegrationTokenProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "integration-tokens";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(IntegrationTokenDocument.class);
    }

    /**
     * Stamp the still-live rows as revoked, then let the inherited update carry
     * the field.
     *
     * <p>In that order, and conditional on {@code revokedAt} being unset: the
     * scope predicate still matches the <em>old</em> name until the rename runs,
     * and a token that was already revoked keeps the date it actually stopped
     * working.
     */
    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        mongoTemplate.updateMulti(
                scope(tenantId, projectId).addCriteria(Criteria.where("revokedAt").is(null)),
                new Update().set("revokedAt", Instant.now()),
                IntegrationTokenDocument.class);
        return super.rename(tenantId, projectId, newProjectId);
    }
}
