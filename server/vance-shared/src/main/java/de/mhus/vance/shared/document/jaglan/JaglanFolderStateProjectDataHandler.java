package de.mhus.vance.shared.document.jaglan;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Freshness bookkeeping for mounted folders under {@code _ext}.
 *
 * <p>The mounted <em>content</em> lives outside Vance and is not ours to
 * delete; what is ours is the record of when we last listed it. The mounted
 * rows themselves are ordinary documents and go with the documents handler.
 */
@Component
public class JaglanFolderStateProjectDataHandler extends MappedProjectDataHandler {

    public JaglanFolderStateProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "jaglan-folder-state";
    }

    @Override
    public int order() {
        return 700;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(JaglanFolderState.class);
    }
}
