package de.mhus.vance.brain.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.trillian.exposure.TrillianConversationService;
import de.mhus.vance.brain.trillian.exposure.TrillianExposureConfig;
import de.mhus.vance.brain.trillian.exposure.TrillianExposureService;
import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * The MCP shell of the agents door: the fixed inventory over the wire, and
 * every gate refusal as protocol content ({@code isError}), never as an
 * HTTP failure — an external agent must be able to read a refusal.
 */
@ExtendWith(MockitoExtension.class)
class TrillianAgentsServiceTest {

    private static final String TENANT = "acme";
    private static final String CALLER = "road.runner";
    private static final String COLIN = "_trillian-colin-5127";

    @Mock
    private TrillianExposureService exposureService;

    @Mock
    private TrillianConversationService conversations;

    private TrillianAgentsService service;

    @BeforeEach
    void setUp() {
        service = new TrillianAgentsService(
                new TrillianAgentsToolCatalog(exposureService, conversations), new ObjectMapper());
    }

    @Test
    void toolsListCarriesTheFixedInventory() {
        Map<String, Object> result = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools =
                (List<Map<String, Object>>) result(result).get("tools");
        assertThat(tools)
                .extracting(t -> t.get("name"))
                .containsExactly("agent_list", "agent_send", "agent_read", "agent_close");
    }

    @Test
    void agentListAnswersTheDirectory() {
        TrillianExposureConfig colin = new TrillianExposureConfig(
                "colin",
                "Colin, delighted to be talked to",
                List.of("road.runner"),
                0,
                0,
                new TrillianExposureConfig.Doors(true, false));
        when(exposureService.directory(TENANT)).thenReturn(List.of(new TrillianExposureService.Exposed(COLIN, colin)));

        Map<String, Object> result = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_list","arguments":{}}}
                """);

        String text = content(result);
        assertThat(text).contains("colin").contains("Colin, delighted to be talked to");
        assertThat(isError(result)).isFalse();
    }

    @Test
    void agentListShowsOnlyAgentsTheCallerMayTalkTo() {
        TrillianExposureConfig colin = new TrillianExposureConfig(
                "colin", "Colin", List.of("road.runner"), 0, 0, new TrillianExposureConfig.Doors(true, false));
        TrillianExposureConfig refuses = new TrillianExposureConfig(
                "marvin", "Marvin", List.of("wile.coyote"), 0, 0, new TrillianExposureConfig.Doors(true, false));
        TrillianExposureConfig doorClosed = new TrillianExposureConfig(
                "eddie", "Eddie", List.of("road.runner"), 0, 0, new TrillianExposureConfig.Doors(false, false));
        when(exposureService.directory(TENANT))
                .thenReturn(List.of(
                        new TrillianExposureService.Exposed(COLIN, colin),
                        new TrillianExposureService.Exposed("_trillian-marvin-1", refuses),
                        new TrillianExposureService.Exposed("_trillian-eddie-1", doorClosed)));

        String text = content(call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_list","arguments":{}}}
                """));

        assertThat(text).contains("colin").doesNotContain("marvin").doesNotContain("eddie");
    }

    @Test
    void agentListEmpty_carriesAHint_withoutNamingRefusingAgents() {
        TrillianExposureConfig refuses = new TrillianExposureConfig(
                "colin", "Colin", List.of("wile.coyote"), 0, 0, new TrillianExposureConfig.Doors(true, false));
        when(exposureService.directory(TENANT))
                .thenReturn(List.of(new TrillianExposureService.Exposed(COLIN, refuses)));

        String text = content(call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_list","arguments":{}}}
                """));

        assertThat(text).contains("No exposed agent").doesNotContain("colin");
    }

    @Test
    void agentListWithAgents_hasNoHint() {
        TrillianExposureConfig colin = new TrillianExposureConfig(
                "colin", "Colin", List.of("road.runner"), 0, 0, new TrillianExposureConfig.Doors(true, false));
        when(exposureService.directory(TENANT)).thenReturn(List.of(new TrillianExposureService.Exposed(COLIN, colin)));

        String text = content(call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_list","arguments":{}}}
                """));

        assertThat(text).contains("colin").doesNotContain("hint");
    }

    @Test
    void aGateRefusalIsErrorContent_notAProtocolFailure() {
        when(conversations.open(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new ToolException("agent not exposed: 'colin'"));

        Map<String, Object> result = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_send","arguments":{"name":"colin","text":"Hi"}}}
                """);

        assertThat(isError(result)).isTrue();
        assertThat(content(result)).contains("agent not exposed");
    }

    @Test
    void anUnknownToolIsErrorContent() {
        Map<String, Object> result = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"doc_read","arguments":{}}}
                """);

        assertThat(isError(result)).isTrue();
        assertThat(content(result)).contains("Unknown tool");
    }

    @Test
    void agentSendOpensWaitsAndReturnsTheHeadAnswer() {
        when(conversations.open(TENANT, CALLER, "colin", null, "Hello Colin!"))
                .thenReturn(new TrillianConversationService.Opened("thread-1"));
        when(conversations.read(TENANT, CALLER, "thread-1", null, TrillianConversationService.DEFAULT_WAIT_MS))
                .thenReturn(new TrillianConversationService.Reply(
                        "thread-1",
                        List.of(new TrillianConversationService.Contribution("m-1", COLIN, "Hello yourself!", null)),
                        true,
                        false));

        Map<String, Object> result = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_send","arguments":{"name":"colin","text":"Hello Colin!"}}}
                """);

        assertThat(isError(result)).isFalse();
        assertThat(content(result))
                .contains("thread-1")
                .contains("Hello yourself!")
                .contains("\"arrived\":true");
    }

    @Test
    void agentSendContributesToAnExistingConversation() {
        when(conversations.send(TENANT, CALLER, "thread-1", "and then?"))
                .thenReturn(new TrillianConversationService.Contribution("m-2", CALLER, "and then?", null));
        when(conversations.read(TENANT, CALLER, "thread-1", "m-2", 5_000))
                .thenReturn(new TrillianConversationService.Reply(
                        "thread-1",
                        List.of(new TrillianConversationService.Contribution("m-3", COLIN, "the answer", null)),
                        true,
                        false));

        Map<String, Object> result = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_send","arguments":{"conversationId":"thread-1","text":"and then?","waitMs":5000}}}
                """);

        assertThat(isError(result)).isFalse();
        assertThat(content(result)).contains("the answer");
    }

    @Test
    void agentReadTakesACursor() {
        when(conversations.read(TENANT, CALLER, "thread-1", "m-1", TrillianConversationService.DEFAULT_WAIT_MS))
                .thenReturn(new TrillianConversationService.Reply(
                        "thread-1",
                        List.of(new TrillianConversationService.Contribution("m-2", COLIN, "new stuff", null)),
                        true,
                        false));

        Map<String, Object> result = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_read","arguments":{"conversationId":"thread-1","after":"m-1"}}}
                """);

        assertThat(isError(result)).isFalse();
        assertThat(content(result)).contains("new stuff");
    }

    @Test
    void agentReadReportsAnEndedConversation() {
        when(conversations.read(TENANT, CALLER, "thread-1", null, TrillianConversationService.DEFAULT_WAIT_MS))
                .thenReturn(new TrillianConversationService.Reply("thread-1", List.of(), false, true));

        String text = content(call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_read","arguments":{"conversationId":"thread-1"}}}
                """));

        assertThat(text).contains("\"closed\":true").contains("\"arrived\":false");
    }

    @Test
    void agentCloseEndsTheConversation() {
        Map<String, Object> result = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_close","arguments":{"conversationId":"thread-1"}}}
                """);

        assertThat(isError(result)).isFalse();
        assertThat(content(result)).contains("\"closed\":true");
        verify(conversations).close(TENANT, CALLER, "thread-1");
    }

    @Test
    void agentCloseWithoutAConversationIdIsRefused() {
        Map<String, Object> result = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_close","arguments":{}}}
                """);

        assertThat(isError(result)).isTrue();
        assertThat(content(result)).contains("'conversationId' is required");
    }

    @Test
    void waitMsIsClamped_negativeToZero_largeToTheMaximum_otherwiseTheDefault() {
        TrillianConversationService.Reply empty =
                new TrillianConversationService.Reply("thread-1", List.of(), false, false);
        when(conversations.read(anyString(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(empty);

        call(readFrame("-5"));
        verify(conversations).read(TENANT, CALLER, "thread-1", null, 0);
        call(readFrame("999999"));
        verify(conversations).read(TENANT, CALLER, "thread-1", null, TrillianConversationService.MAX_WAIT_MS);
        call(readFrame("\"soon\""));
        verify(conversations).read(TENANT, CALLER, "thread-1", null, TrillianConversationService.DEFAULT_WAIT_MS);
    }

    @Test
    void anUnexpectedFailure_isGenericErrorContent_withoutInternals() {
        when(conversations.read(anyString(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenThrow(new IllegalStateException("E11000 duplicate key in collection maximegalon_items"));

        Map<String, Object> result = call(readFrame("0"));

        assertThat(isError(result)).isTrue();
        assertThat(content(result))
                .contains("internal error, reference")
                .doesNotContain("E11000")
                .doesNotContain("maximegalon_items");
    }

    // ──────────────────── helpers ────────────────────

    private static String readFrame(String waitMs) {
        return """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"agent_read","arguments":{"conversationId":"thread-1","waitMs":%s}}}
                """.formatted(waitMs);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String frame) {
        McpProtocol.Outcome outcome = service.handle(frame, TENANT, CALLER);
        assertThat(outcome.noContent()).isFalse();
        return (Map<String, Object>) outcome.body().get("result");
    }

    private static Map<String, Object> result(Map<String, Object> callResult) {
        return callResult;
    }

    private static boolean isError(Map<String, Object> callResult) {
        return Boolean.TRUE.equals(((List<Map<String, Object>>) callResult.get("content"))
                        .get(0)
                        .get("isError"))
                || Boolean.TRUE.equals(callResult.get("isError"));
    }

    private static String content(Map<String, Object> callResult) {
        return String.valueOf(
                ((List<Map<String, Object>>) callResult.get("content")).get(0).get("text"));
    }
}
