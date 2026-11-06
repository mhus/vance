package de.mhus.vance.brain.servicecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CatalogPollServiceTest {

    private static final String CATALOG = """
            match: [slack]
            highlights: []
            triggers:
              - name: message
                mode: poll
                poll:
                  tool: slack__history
                  schedule: "0 0/5 * * * *"
                  params: { channel: C1, oldest: "${cursor}" }
                  itemsFrom: json.messages
                  idFrom: ts
                  cursorFrom: json.latest
              - name: backfill
                mode: poll
                poll: { tool: slack__history, schedule: "0 0 * * * *", itemsFrom: json.messages, idFrom: ts, initial: all }
            """;

    private final DocumentService documents = mock(DocumentService.class);
    private final ToolDispatcher tools = mock(ToolDispatcher.class);
    private CatalogPollService polls;

    @BeforeEach
    void setUp() {
        ServiceCatalogLoader catalog = mock(ServiceCatalogLoader.class);
        var parsed = ServiceCatalogLoader.parse("slack", CATALOG);
        when(catalog.list("acme", "p")).thenReturn(List.of(parsed));
        when(catalog.locate(eq("acme"), eq("p"), any())).thenAnswer(inv -> {
            String from = inv.getArgument(2);
            return parsed.triggers().stream()
                    .filter(t -> from.equals("slack/" + t.name()))
                    .map(t -> new ServiceCatalogLoader.Located(parsed, t))
                    .findFirst();
        });
        when(documents.findByPath(eq("acme"), eq("p"), any())).thenReturn(Optional.empty());
        polls = new CatalogPollService(catalog, documents, tools, new CatalogToolResolver(tools));
    }

    private static Map<String, Object> answer(String latest, String... timestamps) {
        List<Object> messages = new ArrayList<>();
        for (String ts : timestamps) messages.add(Map.of("ts", ts, "text", "t" + ts));
        return Map.of("ok", true, "status", 200, "json", Map.of("messages", messages, "latest", latest));
    }

    private void stateIs(String yaml) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(documents.findByPath("acme", "p", "_vance/trigger-state/inbox.yaml"))
                .thenReturn(Optional.of(doc));
        when(documents.readContent(doc)).thenReturn(yaml);
    }

    @Test
    void theFirstPollOnlyRemembers_itDoesNotReplayTheHistory() {
        when(tools.invoke(eq("slack__history"), any(), any(ToolInvocationContext.class)))
                .thenReturn(answer("3", "1", "2", "3"));

        var polled = polls.poll("acme", "p", "mike", "inbox", "slack/message", Map.of());

        assertThat(polled.items()).isEmpty();
        assertThat(polled.pending().ids()).containsExactly("1", "2", "3");
        assertThat(polled.pending().cursor()).isEqualTo("3");
    }

    @Test
    void laterPollsDeliverOnlyWhatWasNotSeen_andPassTheCursorOn() {
        stateIs("cursor: '3'\nseen: ['1', '2', '3']\n");
        when(tools.invoke(eq("slack__history"), any(), any(ToolInvocationContext.class)))
                .thenReturn(answer("5", "5", "4", "3"));

        var polled = polls.poll("acme", "p", "mike", "inbox", "slack/message", Map.of());

        assertThat(polled.items())
                .extracting(i -> String.valueOf(((Map<?, ?>) i).get("ts")))
                .containsExactly("5", "4");
        assertThat(polled.pending().ids()).containsExactly("1", "2", "3", "5", "4");
        assertThat(polled.pending().cursor()).isEqualTo("5");
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(tools).invoke(eq("slack__history"), params.capture(), any(ToolInvocationContext.class));
        assertThat(params.getValue()).containsEntry("oldest", "3").containsEntry("channel", "C1");
    }

    @Test
    void aTriggerThatAsksForItDeliversTheFirstPollsEntriesToo() {
        when(tools.invoke(eq("slack__history"), any(), any(ToolInvocationContext.class)))
                .thenReturn(answer("2", "1", "2"));

        assertThat(polls.poll("acme", "p", "mike", "inbox", "slack/backfill", Map.of())
                        .items())
                .hasSize(2);
    }

    @Test
    void anErrorAnswerOfTheServiceIsHandedOnAsItIsSoTheWorkflowCanSortIt() {
        Map<String, Object> rateLimited = Map.of("ok", false, "status", 429, "headers", Map.of("Retry-After", "30"));
        when(tools.invoke(eq("slack__history"), any(), any(ToolInvocationContext.class)))
                .thenReturn(rateLimited);

        var polled = polls.poll("acme", "p", "mike", "inbox", "slack/message", Map.of());

        assertThat(polled.failure()).isSameAs(rateLimited);
        assertThat(polled.items()).isEmpty();
    }

    @Test
    void anAnswerWithoutAListOrAnEntryWithoutAnIdIsAnError() {
        when(tools.invoke(eq("slack__history"), any(), any(ToolInvocationContext.class)))
                .thenReturn(Map.of("ok", true, "status", 200, "json", Map.of("other", 1)));
        assertThatThrownBy(() -> polls.poll("acme", "p", "mike", "inbox", "slack/message", Map.of()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("json.messages");

        when(tools.invoke(eq("slack__history"), any(), any(ToolInvocationContext.class)))
                .thenReturn(
                        Map.of("ok", true, "status", 200, "json", Map.of("messages", List.of(Map.of("text", "x")))));
        assertThatThrownBy(() -> polls.poll("acme", "p", "mike", "inbox", "slack/message", Map.of()))
                .hasMessageContaining("no id");
    }

    @Test
    void aTriggerThatDoesNotPollOrIsNotThereIsRefused() {
        assertThatThrownBy(() -> polls.poll("acme", "p", "mike", "inbox", "slack/nope", Map.of()))
                .hasMessageContaining("not a polling trigger");
        assertThatThrownBy(() -> polls.poll("acme", "p", "mike", "inbox", "slack", Map.of()))
                .hasMessageContaining("<service>/<trigger>");
    }

    @Test
    void onlyTheMostRecentIdsAreRemembered() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < CatalogPollService.MAX_SEEN + 5; i++) many.add(String.valueOf(i));
        stateIs("seen: " + many.subList(0, CatalogPollService.MAX_SEEN) + "\n");
        when(tools.invoke(eq("slack__history"), any(), any(ToolInvocationContext.class)))
                .thenReturn(answer(
                        "x",
                        many.subList(CatalogPollService.MAX_SEEN, many.size()).toArray(new String[0])));

        var polled = polls.poll("acme", "p", "mike", "inbox", "slack/message", Map.of());

        assertThat(polled.pending().ids()).hasSize(CatalogPollService.MAX_SEEN);
        assertThat(polled.pending().ids().get(CatalogPollService.MAX_SEEN - 1)).isEqualTo(many.get(many.size() - 1));
        assertThat(polled.pending().ids()).doesNotContain("0");
    }

    @Test
    void commitWritesTheStateAsAnOrdinaryDocument() {
        polls.commit("acme", "p", "inbox", new CatalogPollService.Pending(List.of("1", "2"), "2"));

        ArgumentCaptor<String> yaml = ArgumentCaptor.forClass(String.class);
        verify(documents)
                .upsertText(
                        eq("acme"),
                        eq("p"),
                        eq("_vance/trigger-state/inbox.yaml"),
                        any(),
                        any(),
                        yaml.capture(),
                        any(),
                        any());
        assertThat(yaml.getValue()).contains("cursor: '2'").contains("seen:").contains("- '1'");
    }

    @Test
    void theInputsAreAvailableToThePollAsInputPlaceholders() {
        when(tools.invoke(eq("slack__history"), any(), any(ToolInvocationContext.class)))
                .thenReturn(answer("1", "1"));
        var catalog = mock(ServiceCatalogLoader.class);
        var parsed = ServiceCatalogLoader.parse("slack", """
                match: [slack]
                triggers:
                  - name: message
                    mode: poll
                    inputs: [ { name: channel } ]
                    poll: { tool: slack__history, schedule: "0 0 * * * *", params: { channel: "${input.channel}" }, itemsFrom: json.messages, idFrom: ts }
                """);
        when(catalog.locate(eq("acme"), eq("p"), any()))
                .thenReturn(Optional.of(new ServiceCatalogLoader.Located(
                        parsed, parsed.triggers().get(0))));

        new CatalogPollService(catalog, documents, tools, new CatalogToolResolver(tools))
                .poll("acme", "p", "mike", "inbox", "slack/message", Map.of("channel", "C42"));

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(tools).invoke(eq("slack__history"), params.capture(), any(ToolInvocationContext.class));
        assertThat(params.getValue()).containsEntry("channel", "C42");
    }

    @Test
    void aShortOperationNameIsResolvedAgainstThePacksTheEntryDescribes() {
        var entry = ServiceCatalogLoader.parse("slack", "match: [slack]\n");
        List<String> names = List.of(
                "jira_rest__conversations_history",
                "slack_rest__chat_postMessage",
                "slack_rest__conversations_history");

        assertThat(CatalogToolResolver.pick(entry, "conversations_history", names.subList(1, 3)))
                .isEqualTo("slack_rest__conversations_history");
        assertThat(CatalogToolResolver.pick(entry, "Conversations_History", names.subList(1, 3)))
                .isEqualTo("slack_rest__conversations_history");
        assertThatThrownBy(() -> CatalogToolResolver.pick(entry, "nope", names.subList(1, 3)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("pack installed");
    }
}
