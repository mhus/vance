package de.mhus.vance.shared.oauthserver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Rename and delete revoke through the grant service — audited and announced —
 * before the rows are moved or removed.
 */
class OAuthServerGrantProjectDataHandlerTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class, RETURNS_DEEP_STUBS);
    private final OAuthServerGrantService grants = mock(OAuthServerGrantService.class);
    private final OAuthServerGrantProjectDataHandler handler =
            new OAuthServerGrantProjectDataHandler(mongoTemplate, grants);

    @Test
    void rename_revokesThePinnedGrants_thenMovesTheRows() {
        handler.rename("acme", "docs", "docs-new");

        InOrder order = inOrder(grants, mongoTemplate);
        order.verify(grants).revokeAllForProject("acme", "docs", "project-renamed");
        order.verify(mongoTemplate)
                .updateMulti(any(Query.class), any(Update.class), eq(OAuthServerGrantDocument.class));
    }

    @Test
    void delete_revokesThePinnedGrants_thenRemovesTheRows() {
        handler.delete("acme", "docs");

        InOrder order = inOrder(grants, mongoTemplate);
        order.verify(grants).revokeAllForProject("acme", "docs", "project-deleted");
        order.verify(mongoTemplate).remove(any(Query.class), eq(OAuthServerGrantDocument.class));
    }
}
