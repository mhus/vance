package de.mhus.vance.shared.magrathea;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * A Magrathea workflow run is three collections at once — its tasks, its
 * journal and its timers. One handler, because they are one run: a timer left
 * behind after its task is gone would fire into nothing.
 */
@Component
public class MagratheaProjectDataHandler extends MappedProjectDataHandler {

    public MagratheaProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "magrathea-runs";
    }

    @Override
    public int order() {
        return 1300;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(
                MagratheaTaskDocument.class,
                MagratheaJournalEntry.class,
                MagratheaTimerDocument.class);
    }
}
