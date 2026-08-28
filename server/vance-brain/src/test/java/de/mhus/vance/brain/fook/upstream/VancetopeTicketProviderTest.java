package de.mhus.vance.brain.fook.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.shared.settings.SettingService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * How one collector answer becomes an update. The HTTP half is not
 * exercised — same as the GitHub adapter next to it — but this is where
 * the decisions are, and each of them is one somebody's inbox notices.
 */
class VancetopeTicketProviderTest {

    private static final Instant SINCE = Instant.parse("2026-08-28T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();

    private VancetopeTicketProvider provider;
    private ProviderTicketRef ref;

    @BeforeEach
    void setUp() {
        provider = new VancetopeTicketProvider(mock(SettingService.class));
        ref = ProviderTicketRef.builder()
                .provider(VancetopeTicketProvider.NAME)
                .externalId("SECRET-HANDLE")
                .displayId("FT-1")
                .url("https://issues.vancetope.com/t#h=SECRET-HANDLE")
                .build();
    }

    @Test
    void ownContributionsAreNotFannedBackToTheReporter() {
        JsonNode answer = json("""
                {
                  "state": "OPEN",
                  "updatedAt": "2026-08-28T13:00:00Z",
                  "comments": [
                    {"id": "c1", "author": "reporter", "origin": "BRIDGE",
                     "body": "here is the log", "createdAt": "2026-08-28T12:30:00Z"},
                    {"id": "c2", "author": "reporter", "origin": "WEB",
                     "body": "and a screenshot", "createdAt": "2026-08-28T12:40:00Z"}
                  ]
                }
                """);

        ProviderTicketUpdate update = provider.readUpdate(ref, answer, SINCE);

        // Both came from the reporter. Handing them to the inbox would tell
        // somebody about their own message as if it were an answer.
        assertThat(update).isNotNull();
        assertThat(update.getNewComments()).isEmpty();
    }

    @Test
    void maintainerCommentsAfterTheAnchorAreDelivered() {
        JsonNode answer = json("""
                {
                  "state": "OPEN",
                  "updatedAt": "2026-08-28T13:00:00Z",
                  "comments": [
                    {"id": "c1", "author": "ford", "origin": "MAINTAINER",
                     "body": "Can you reproduce on 1.4-rc1?",
                     "createdAt": "2026-08-28T12:30:00Z"}
                  ]
                }
                """);

        ProviderTicketUpdate update = provider.readUpdate(ref, answer, SINCE);

        assertThat(update).isNotNull();
        assertThat(update.getNewComments()).hasSize(1);
        assertThat(update.getNewComments().getFirst().getAuthor()).isEqualTo("ford");
        assertThat(update.getNewComments().getFirst().getExternalId()).isEqualTo("c1");
    }

    @Test
    void commentsFromBeforeTheAnchorAreNotRedelivered() {
        JsonNode answer = json("""
                {
                  "state": "OPEN",
                  "updatedAt": "2026-08-28T11:00:00Z",
                  "comments": [
                    {"id": "c0", "author": "ford", "origin": "MAINTAINER",
                     "body": "old news", "createdAt": "2026-08-28T09:00:00Z"}
                  ]
                }
                """);

        // Nothing new and nothing touched since: no update at all, rather
        // than an empty one that would still cost an inbox round.
        assertThat(provider.readUpdate(ref, answer, SINCE)).isNull();
    }

    @Test
    void aStateChangeAloneStillProducesAnUpdate() {
        JsonNode answer = json("""
                {"state": "CLOSED", "updatedAt": "2026-08-28T13:00:00Z", "comments": []}
                """);

        ProviderTicketUpdate update = provider.readUpdate(ref, answer, SINCE);

        assertThat(update).isNotNull();
        assertThat(update.getNewComments()).isEmpty();
        // Lower-cased: the collector says CLOSED, the rest of Fook stores
        // and compares "closed" — GitHub's spelling is the one already in
        // the database.
        assertThat(update.getState()).isEqualTo("closed");
    }

    @Test
    void anUnparseableTimestampDoesNotDropTheComment() {
        JsonNode answer = json("""
                {
                  "state": "OPEN",
                  "updatedAt": "2026-08-28T13:00:00Z",
                  "comments": [
                    {"id": "c1", "author": "ford", "origin": "MAINTAINER",
                     "body": "no timestamp here", "createdAt": "not-a-date"}
                  ]
                }
                """);

        ProviderTicketUpdate update = provider.readUpdate(ref, answer, SINCE);

        // Delivering something with a wrong timestamp beats silently
        // swallowing a maintainer's question.
        assertThat(update).isNotNull();
        assertThat(update.getNewComments()).hasSize(1);
    }

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }
}
