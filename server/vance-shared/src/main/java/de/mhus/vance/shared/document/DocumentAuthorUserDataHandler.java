package de.mhus.vance.shared.document;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Who created a document, live and archived.
 *
 * <p><b>The documents themselves stay.</b> A document belongs to its project,
 * not to whoever happened to write it — deleting an account must not take the
 * team's specification with it. Only the authorship reference moves, to the
 * tombstone.
 *
 * <p>Both collections in one handler because {@code createdBy} means exactly
 * the same thing in each, and an archive whose author reads differently from
 * the live document it came from would be a puzzle nobody can resolve.
 */
@Component
public class DocumentAuthorUserDataHandler extends MappedUserDataHandler {

    public DocumentAuthorUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "document-authors";
    }

    @Override
    public int order() {
        return 1300;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(DocumentDocument.class, DocumentArchiveDocument.class);
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
