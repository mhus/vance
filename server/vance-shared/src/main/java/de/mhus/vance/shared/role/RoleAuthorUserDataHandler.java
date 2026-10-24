package de.mhus.vance.shared.role;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Who assigned a role.
 *
 * <p>Separate from {@link RoleSubjectUserDataHandler} although it is the same
 * collection, because the fields are of different classes: the <em>subject</em>
 * of an assignment is authority and is removed, the <em>author</em> of it is a
 * record and is tombstoned. A role assigned by someone who has since left is
 * still a role, and who assigned it is exactly what an audit of it wants to
 * know.
 *
 * <p>Runs after the subject handler, so the assignments that were about to go
 * are gone and this only touches the ones that stay.
 */
@Component
public class RoleAuthorUserDataHandler extends MappedUserDataHandler {

    public RoleAuthorUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "role-assignment-authors";
    }

    @Override
    public int order() {
        return 1800;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(RoleAssignmentDocument.class);
    }

    @Override
    protected String userField() {
        return "createdBy";
    }

    @Override
    protected UserReference reference() {
        return UserReference.RECORD;
    }
}
