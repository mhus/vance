package de.mhus.vance.shared.integration;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
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
 * <p>Rename is the inherited field update, and it is load-bearing rather than
 * cosmetic: the pin is compared by name on every request, so a rename that did
 * not carry it would silently turn every token of that project into one that
 * authenticates and then fails every call.
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
}
