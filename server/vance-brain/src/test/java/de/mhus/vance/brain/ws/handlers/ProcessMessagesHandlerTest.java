package de.mhus.vance.brain.ws.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ProcessMessagesRequest;
import de.mhus.vance.api.thinkprocess.ProcessMessagesResponse;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * The session-scope guarantee of the process detail view: this handler exists
 * precisely because the REST twin is project-scoped, so the tests that matter
 * are the ones proving a process outside the bound session is unreachable
 * (planning/process-visibility.md §5.1) — plus the newest-N cut.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessMessagesHandlerTest {

    private static final String TENANT = "acme";
    private static final String SESSION = "sess-1";
    private static final String OTHER_SESSION = "sess-2";
    private static final String PROC_ID = "proc-1";
    private static final String PROC_NAME = "worker-a";

    @Mock private WebSocketSender sender;
    @Mock private ThinkProcessService thinkProcessService;
    @Mock private ChatMessageService chatMessageService;
    @Mock private RequestAuthority authority;

    private ProcessMessagesHandler handler;
    private WebSocketSession wsSession;

    @BeforeEach
    void setUp() {
        handler = new ProcessMessagesHandler(
                sender, thinkProcessService, chatMessageService,
                JsonMapper.builder().build(), authority);
        wsSession = mock(WebSocketSession.class);
    }

    @Test
    void byName_returnsTheProcessConversation() throws Exception {
        when(thinkProcessService.findByName(TENANT, SESSION, PROC_NAME))
                .thenReturn(Optional.of(process(SESSION)));
        when(chatMessageService.activeHistoryWithInterim(TENANT, SESSION, PROC_ID))
                .thenReturn(List.of(msg("m1", ChatRole.USER), msg("m2", ChatRole.ASSISTANT)));

        handler.handle(boundContext(), wsSession, envelope(byName(PROC_NAME, null)));

        ProcessMessagesResponse out = captureReply();
        assertThat(out.getName()).isEqualTo(PROC_NAME);
        assertThat(out.getStatus()).isEqualTo(ThinkProcessStatus.RUNNING);
        assertThat(out.getMessages()).extracting("messageId").containsExactly("m1", "m2");
        // Every row is tagged with the producing process — that's what makes
        // the client render it as a worker note.
        assertThat(out.getMessages()).allSatisfy(
                m -> assertThat(m.getProcessName()).isEqualTo(PROC_NAME));
        assertThat(out.getOlderTruncated()).isNull();
    }

    @Test
    void byId_ofAnotherSession_is404() throws Exception {
        when(thinkProcessService.findById(PROC_ID))
                .thenReturn(Optional.of(process(OTHER_SESSION)));

        handler.handle(boundContext(), wsSession, envelope(byId(PROC_ID)));

        verify(sender).sendError(eq(wsSession), any(), eq(404), anyString());
        verify(chatMessageService, never()).activeHistoryWithInterim(
                anyString(), anyString(), anyString());
    }

    @Test
    void byId_ofTheBoundSession_isServed() throws Exception {
        when(thinkProcessService.findById(PROC_ID))
                .thenReturn(Optional.of(process(SESSION)));
        when(chatMessageService.activeHistoryWithInterim(TENANT, SESSION, PROC_ID))
                .thenReturn(List.of(msg("m1", ChatRole.ASSISTANT)));

        handler.handle(boundContext(), wsSession, envelope(byId(PROC_ID)));

        assertThat(captureReply().getMessages()).hasSize(1);
    }

    @Test
    void unknownName_is404() throws Exception {
        when(thinkProcessService.findByName(TENANT, SESSION, "ghost"))
                .thenReturn(Optional.empty());

        handler.handle(boundContext(), wsSession, envelope(byName("ghost", null)));

        verify(sender).sendError(eq(wsSession), any(), eq(404), anyString());
    }

    @Test
    void neitherNameNorId_is400() throws Exception {
        handler.handle(boundContext(), wsSession,
                envelope(ProcessMessagesRequest.builder().build()));

        verify(sender).sendError(eq(wsSession), any(), eq(400), anyString());
        verify(thinkProcessService, never()).findByName(anyString(), anyString(), anyString());
    }

    @Test
    void limit_keepsNewestAndReportsWhatWasCut() throws Exception {
        List<ChatMessageDocument> many = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            many.add(msg("m" + i, ChatRole.ASSISTANT));
        }
        when(thinkProcessService.findByName(TENANT, SESSION, PROC_NAME))
                .thenReturn(Optional.of(process(SESSION)));
        when(chatMessageService.activeHistoryWithInterim(TENANT, SESSION, PROC_ID))
                .thenReturn(many);

        handler.handle(boundContext(), wsSession, envelope(byName(PROC_NAME, 2)));

        ProcessMessagesResponse out = captureReply();
        assertThat(out.getMessages()).extracting("messageId").containsExactly("m4", "m5");
        assertThat(out.getOlderTruncated()).isEqualTo(3);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private ProcessMessagesResponse captureReply() throws Exception {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendReply(eq(wsSession), any(),
                eq(MessageType.PROCESS_MESSAGES), payload.capture());
        return (ProcessMessagesResponse) payload.getValue();
    }

    private ConnectionContext boundContext() {
        ConnectionContext ctx = new ConnectionContext(
                TENANT, "wile.coyote", "Wile", "foot", "1.0", "cli", "ed-1", "10.0.0.1");
        SessionDocument session = new SessionDocument();
        session.setSessionId(SESSION);
        session.setTenantId(TENANT);
        session.setProjectId("proj");
        ctx.bindSession(session);
        return ctx;
    }

    private static WebSocketEnvelope envelope(Object data) {
        return WebSocketEnvelope.request("req-1", MessageType.PROCESS_MESSAGES, data);
    }

    private static ProcessMessagesRequest byName(String name, Integer limit) {
        return ProcessMessagesRequest.builder().name(name).limit(limit).build();
    }

    private static ProcessMessagesRequest byId(String processId) {
        return ProcessMessagesRequest.builder().processId(processId).build();
    }

    private static ThinkProcessDocument process(String sessionId) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId(PROC_ID);
        doc.setName(PROC_NAME);
        doc.setTenantId(TENANT);
        doc.setProjectId("proj");
        doc.setSessionId(sessionId);
        doc.setThinkEngine("frankie");
        doc.setStatus(ThinkProcessStatus.RUNNING);
        return doc;
    }

    private static ChatMessageDocument msg(String id, ChatRole role) {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .role(role).content("c").thinkProcessId(PROC_ID).build();
        doc.setId(id);
        return doc;
    }
}
