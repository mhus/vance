package de.mhus.vance.shared.fenchurch;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The image-generation ledger — one row per Fenchurch call, and the input to
 * the per-project image quota.
 *
 * <p>Same trade as {@code llm-usage}, same resolution: the rows are accounting
 * and deleting them loses history, but a project quota keyed on the project
 * name would otherwise hand a fresh project a spent budget.
 */
@Component
public class ImageCallProjectDataHandler extends MappedProjectDataHandler {

    public ImageCallProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "image-calls";
    }

    @Override
    public int order() {
        return 1900;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(ImageCallRecord.class);
    }
}
