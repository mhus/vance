package de.mhus.vance.shared.thinkprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.shared.enginemessage.EngineMessageService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The status-filtered lookups the self-waking engines run every round: they
 * must ask Mongo for the living only, scoped to what the caller holds — never
 * load the closed rows or the whole cluster to filter them in memory.
 */
class ThinkProcessServiceLiveQueriesTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final ThinkProcessService service = new ThinkProcessService(
            mock(ThinkProcessRepository.class),
            mongoTemplate,
            mock(ApplicationEventPublisher.class),
            mock(EngineMessageService.class));

    @Test
    void liveByEngine_isScopedToTheGivenProjects_perTenant() {
        service.findLiveByEngineInProjects("trillian-user", Map.of("acme", List.of("_user__trillian-a", "proj")));

        String query = capturedQuery();
        assertThat(query)
                .contains("thinkEngine=trillian-user")
                .contains("$ne=CLOSED")
                .contains("tenantId=acme")
                .contains("$in=[_user__trillian-a, proj]");
    }

    @Test
    void liveByEngine_withNoProjects_asksNothing() {
        assertThat(service.findLiveByEngineInProjects("trillian-user", Map.of()))
                .isEmpty();
        assertThat(service.findLiveByEngineInProjects("trillian-user", Map.of("acme", List.of())))
                .isEmpty();

        verify(mongoTemplate, never()).find(any(Query.class), eq(ThinkProcessDocument.class));
    }

    @Test
    void liveBySession_filtersClosedInTheQuery() {
        service.findLiveBySession("acme", "session-1");

        assertThat(capturedQuery())
                .contains("tenantId=acme")
                .contains("sessionId=session-1")
                .contains("$ne=CLOSED");
    }

    private String capturedQuery() {
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(ThinkProcessDocument.class));
        // toString, not toJson: the status is an enum the default JSON codec
        // cannot encode — the mapping to Mongo happens later, in the template.
        return query.getValue().getQueryObject().toString();
    }
}
