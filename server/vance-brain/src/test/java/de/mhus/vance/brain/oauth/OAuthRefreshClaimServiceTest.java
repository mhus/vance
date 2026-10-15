package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** The cluster-wide claim that keeps one OAuth refresh on one pod at a time. */
class OAuthRefreshClaimServiceTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final OAuthRefreshClaimService service = new OAuthRefreshClaimService(mongoTemplate);

    @Test
    void key_namesTenantStorageAndProvider() {
        assertThat(OAuthRefreshClaimService.key("acme", OAuthTokenOwner.tenant(), "mcp-x"))
                .isEqualTo("refresh/acme/_tenant/mcp-x");
    }

    @Test
    void freeClaim_isTakenWithHolderToken() {
        String holder = service.tryClaim("k", Duration.ofSeconds(30));

        ArgumentCaptor<OAuthRefreshClaimDocument> doc = ArgumentCaptor.forClass(OAuthRefreshClaimDocument.class);
        verify(mongoTemplate).insert(doc.capture());
        assertThat(holder).isNotBlank().isEqualTo(doc.getValue().getHolder());
    }

    @Test
    void freshClaimOfAnotherPod_isRefused() {
        when(mongoTemplate.insert(any(OAuthRefreshClaimDocument.class))).thenThrow(new DuplicateKeyException("x"));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(OAuthRefreshClaimDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(service.tryClaim("k", Duration.ofSeconds(30))).isNull();
    }

    @Test
    void staleClaim_isTakenOver() {
        when(mongoTemplate.insert(any(OAuthRefreshClaimDocument.class))).thenThrow(new DuplicateKeyException("x"));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(OAuthRefreshClaimDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertThat(service.tryClaim("k", Duration.ofSeconds(30))).isNotBlank();
    }

    @Test
    void release_isHolderChecked() {
        when(mongoTemplate.remove(any(Query.class), eq(OAuthRefreshClaimDocument.class)))
                .thenReturn(DeleteResult.acknowledged(1));

        service.release("k", "h-1");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(query.capture(), eq(OAuthRefreshClaimDocument.class));
        assertThat(query.getValue().getQueryObject().get("_id")).isEqualTo("k");
        assertThat(query.getValue().getQueryObject().get("holder")).isEqualTo("h-1");
    }
}
