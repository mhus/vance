package de.mhus.vance.shared.hotblack;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The audio-generation ledger — one row per Hotblack call, and the input
 * to the per-project audio quota.
 *
 * <p>Same trade as {@code image-calls}, same resolution: the rows are
 * accounting and deleting them loses history, but a project quota keyed
 * on the project name would otherwise hand a fresh project a spent
 * budget.
 */
@Component
public class AudioCallProjectDataHandler extends MappedProjectDataHandler {

    public AudioCallProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "audio-calls";
    }

    @Override
    public int order() {
        return 1910;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(AudioCallRecord.class);
    }
}
