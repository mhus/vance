package de.mhus.vance.shared.session;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Sessions belong to a project by {@code projectId}.
 *
 * <p>Everything hanging off a session — chat messages, engine messages — is
 * reached through it and therefore carries a lower order, so those handlers run
 * while the sessions are still there to be found.
 */
@Component
public class SessionProjectDataHandler extends MappedProjectDataHandler {

    public SessionProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "sessions";
    }

    @Override
    public int order() {
        return 800;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(SessionDocument.class);
    }
}
