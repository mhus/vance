package de.mhus.vance.brain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ProcessListResponse;
import de.mhus.vance.api.thinkprocess.ProcessMessagesResponse;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

/**
 * The picker's process preview reads a session nobody is bound to, so the
 * session scope has to hold on the REST path too: only the named session's
 * processes, only for a caller with READ on it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionProcessControllerTest {

    private static final String TENANT = "acme";
    private static final String SESSION = "sess-1";
    private static final String PROJECT = "proj-1";
    private static final String OWNER = "marvin";

    @Mock private SessionService sessionService;
    @Mock private ThinkProcessService thinkProcessService;
    @Mock private ChatMessageService chatMessageService;
    @Mock private RequestAuthority authority;
    @Mock private HttpServletRequest request;

    private SessionProcessController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionProcessController(
                sessionService, thinkProcessService, chatMessageService, authority);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(OWNER);
        when(sessionService.findBySessionId(SESSION))
                .thenReturn(Optional.of(session(OWNER, false)));
    }

    private static SessionDocument session(String owner, boolean shared) {
        return SessionDocument.builder()
                .sessionId(SESSION)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .userId(owner)
                .allowMultipleClients(shared)
                .build();
    }

    @Test
    void list_hidesClosedProcesses_andReportsTheCount() {
        when(thinkProcessService.findBySession(TENANT, SESSION)).thenReturn(List.of(
                process("p1", "worker-a", ThinkProcessStatus.RUNNING),
                process("p2", "worker-b", ThinkProcessStatus.CLOSED),
                process("p3", "worker-c", ThinkProcessStatus.IDLE)));

        ProcessListResponse response = controller.list(TENANT, SESSION, false, request);

        assertThat(response.getProcesses()).extracting("name")
                .containsExactly("worker-a", "worker-c");
        assertThat(response.getHiddenTerminated()).isEqualTo(1);
    }

    @Test
    void list_includeTerminated_keepsClosedAndLeavesTheHintNull() {
        when(thinkProcessService.findBySession(TENANT, SESSION)).thenReturn(List.of(
                process("p1", "worker-a", ThinkProcessStatus.RUNNING),
                process("p2", "worker-b", ThinkProcessStatus.CLOSED)));

        ProcessListResponse response = controller.list(TENANT, SESSION, true, request);

        assertThat(response.getProcesses()).hasSize(2);
        assertThat(response.getHiddenTerminated()).isNull();
    }

    @Test
    void list_enforcesSessionReadOnTheSessionsOwnProject() {
        when(thinkProcessService.findBySession(TENANT, SESSION)).thenReturn(List.of());

        controller.list(TENANT, SESSION, false, request);

        verify(authority).enforce(request,
                new Resource.Session(TENANT, PROJECT, SESSION), Action.READ);
    }

    // ──────────── whose conversation it is ────────────
    //
    // Resource.Session READ resolves to the project role, so it alone let
    // every project READER pull a colleague's transcript here — while
    // GET /sessions/{id}/messages refused them the same rows. The picker
    // this feeds lists own + shared sessions and nothing else.

    @Test
    void list_privateSessionOfAnotherUser_is403() {
        when(sessionService.findBySessionId(SESSION))
                .thenReturn(Optional.of(session("trillian", false)));

        assertThatThrownBy(() -> controller.list(TENANT, SESSION, false, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verify(thinkProcessService, org.mockito.Mockito.never())
                .findBySession(any(), any());
    }

    @Test
    void messages_privateSessionOfAnotherUser_is403() {
        when(sessionService.findBySessionId(SESSION))
                .thenReturn(Optional.of(session("trillian", false)));

        assertThatThrownBy(() -> controller.messages(TENANT, SESSION, "chat", null, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verify(chatMessageService, org.mockito.Mockito.never())
                .activeHistoryWithInterim(any(), any(), any());
    }

    @Test
    void list_sharedSessionOfAnotherUser_isReadable() {
        // allowMultipleClients is the owner's own "anyone may join" — the
        // one thing that opens their session to a colleague.
        when(sessionService.findBySessionId(SESSION))
                .thenReturn(Optional.of(session("trillian", true)));
        when(thinkProcessService.findBySession(TENANT, SESSION)).thenReturn(List.of());

        controller.list(TENANT, SESSION, false, request);

        verify(authority).enforce(request,
                new Resource.Session(TENANT, PROJECT, SESSION), Action.READ);
    }

    @Test
    void list_sessionOfAnotherTenant_is404() {
        when(sessionService.findBySessionId(SESSION)).thenReturn(Optional.of(
                SessionDocument.builder()
                        .sessionId(SESSION)
                        .tenantId("other")
                        .projectId(PROJECT)
                        .build()));

        assertThatThrownBy(() -> controller.list(TENANT, SESSION, false, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(authority, org.mockito.Mockito.never())
                .enforce(any(HttpServletRequest.class), any(), any());
    }

    @Test
    void messages_capsToNewestAndReportsTheCut() {
        when(thinkProcessService.findByName(TENANT, SESSION, "worker-a"))
                .thenReturn(Optional.of(process("p1", "worker-a", ThinkProcessStatus.RUNNING)));
        List<ChatMessageDocument> history = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            history.add(message("m" + i));
        }
        when(chatMessageService.activeHistoryWithInterim(TENANT, SESSION, "p1"))
                .thenReturn(history);

        ProcessMessagesResponse response =
                controller.messages(TENANT, SESSION, "worker-a", 2, request);

        assertThat(response.getMessages()).extracting("messageId").containsExactly("m4", "m5");
        assertThat(response.getOlderTruncated()).isEqualTo(3);
        assertThat(response.getMessages().getFirst().getProcessName()).isEqualTo("worker-a");
    }

    @Test
    void messages_processOfAnotherSession_is404() {
        when(thinkProcessService.findByName(TENANT, SESSION, "stranger"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.messages(TENANT, SESSION, "stranger", null, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    private static ThinkProcessDocument process(String id, String name, ThinkProcessStatus status) {
        return ThinkProcessDocument.builder()
                .id(id)
                .name(name)
                .tenantId(TENANT)
                .sessionId(SESSION)
                .projectId(PROJECT)
                .thinkEngine("frankie")
                .status(status)
                .build();
    }

    private static ChatMessageDocument message(String id) {
        return ChatMessageDocument.builder()
                .id(id)
                .tenantId(TENANT)
                .sessionId(SESSION)
                .thinkProcessId("p1")
                .role(ChatRole.ASSISTANT)
                .content("hello " + id)
                .build();
    }
}
