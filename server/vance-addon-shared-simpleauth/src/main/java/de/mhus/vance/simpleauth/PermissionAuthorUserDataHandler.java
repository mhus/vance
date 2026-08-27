package de.mhus.vance.simpleauth;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Who granted a permission, and who asked for one.
 *
 * <p>Separate from {@link PermissionSubjectUserDataHandler} although it is the
 * same two collections, because the fields are of different classes: the
 * <em>subject</em> of a grant is authority and is removed, the <em>author</em>
 * of it is a record and is tombstoned. A grant issued by someone who has since
 * left is still a grant, and who issued it is exactly what an audit of it
 * wants to know.
 *
 * <p>Runs after the subject handler, so the grants that were about to go are
 * gone and this only touches the ones that stay.
 */
@Component
public class PermissionAuthorUserDataHandler extends MappedUserDataHandler {

    public PermissionAuthorUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "permission-grant-authors";
    }

    @Override
    public int order() {
        return 1800;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(PermissionGrantDocument.class);
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
