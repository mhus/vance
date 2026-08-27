package de.mhus.vance.shared.megadodo;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The project's activity feed.
 *
 * <p>Rows about a project's <em>lifecycle</em> — created, renamed, deleted —
 * are filed against the tenant with a {@code null} {@code projectId} precisely
 * so this handler does not take them: the record that a project was deleted
 * must outlive the delete.
 */
@Component
public class MegadodoProjectDataHandler extends MappedProjectDataHandler {

    public MegadodoProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "activity-feed";
    }

    @Override
    public int order() {
        return 1400;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(MegadodoEventDocument.class);
    }
}
