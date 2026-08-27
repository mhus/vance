package de.mhus.vance.shared.sessiongroup;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Session groups are per-user, per-project UI ordering. They hold the member
 * session ids, so a group whose project is gone points at nothing — there is no
 * variant where one survives its project.
 */
@Component
public class SessionGroupProjectDataHandler extends MappedProjectDataHandler {

    public SessionGroupProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "session-groups";
    }

    @Override
    public int order() {
        return 900;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(SessionGroupDocument.class);
    }
}
