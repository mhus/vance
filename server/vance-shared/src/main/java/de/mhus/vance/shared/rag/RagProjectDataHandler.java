package de.mhus.vance.shared.rag;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The RAG index: catalogue entries and the chunks they were split into.
 *
 * <p>Both collections in one handler because they are one thing — a catalogue
 * entry without its chunks is not a smaller index, it is a broken one, and a
 * report that listed them separately would only invite deleting half.
 */
@Component
public class RagProjectDataHandler extends MappedProjectDataHandler {

    public RagProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "rag-index";
    }

    @Override
    public int order() {
        return 1200;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(RagDocument.class, RagChunkDocument.class);
    }
}
