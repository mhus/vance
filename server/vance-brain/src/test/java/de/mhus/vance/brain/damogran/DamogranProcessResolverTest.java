package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class DamogranProcessResolverTest {

    private SessionService sessionService;
    private ThinkProcessService thinkProcessService;
    private ChatMessageService chatMessageService;
    private ActionExecutorRegistry actionRegistry;
    private DamogranProcessResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionService = mock(SessionService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        chatMessageService = mock(ChatMessageService.class);
        actionRegistry = mock(ActionExecutorRegistry.class);
        ObjectProvider<ActionExecutorRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(actionRegistry);
        resolver = new DamogranProcessResolver(
                sessionService, thinkProcessService, chatMessageService, provider);
    }

    private static SessionDocument session(String sessionId, String owner) {
        SessionDocument s = new SessionDocument();
        s.setSessionId(sessionId);
        s.setUserId(owner);
        s.setSystem(true);
        return s;
    }

    @Test
    void reuses_existing_process_without_creating() {
        SessionDocument session = session("sys-1", "alice");
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getId()).thenReturn("proc-1");
        when(sessionService.findSystemSession("t", "p", "_damogran")).thenReturn(Optional.of(session));
        when(thinkProcessService.findByName("t", "sys-1", "_damogran")).thenReturn(Optional.of(process));

        assertThat(resolver.resolveComposeSession("t", "p", "alice", null, null, false))
                .isEqualTo("proc-1");

        verify(sessionService, never())
                .create(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(thinkProcessService, never()).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void creates_process_with_worktarget_override_when_absent() {
        when(sessionService.findSystemSession("t", "p", "_damogran")).thenReturn(Optional.empty());
        SessionDocument created = new SessionDocument();
        created.setSessionId("sys-9");
        when(sessionService.create(eq("t"), eq("alice"), eq("p"), eq("_damogran"),
                any(), any(), any(), eq(true))).thenReturn(created);
        when(thinkProcessService.findByName("t", "sys-9", "_damogran")).thenReturn(Optional.empty());
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getId()).thenReturn("proc-9");
        when(thinkProcessService.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(process);

        assertThat(resolver.resolveComposeSession("t", "p", "alice", null, null, false))
                .isEqualTo("proc-9");

        verify(sessionService).markBootstrapped("sys-9");
        ArgumentCaptor<Set<String>> override = ArgumentCaptor.forClass(Set.class);
        verify(thinkProcessService).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                override.capture());
        assertThat(override.getValue()).contains("file_read", "file_write");
    }

    @Test
    void name_scopesProcess_sanitizedName() {
        SessionDocument session = session("sys-app", "alice");
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getId()).thenReturn("proc-app");
        // "app:notes/build" → sanitized "_damogran_app_notes_build"
        when(sessionService.findSystemSession("t", "p", "_damogran_app_notes_build"))
                .thenReturn(Optional.of(session));
        when(thinkProcessService.findByName("t", "sys-app", "_damogran_app_notes_build"))
                .thenReturn(Optional.of(process));

        assertThat(resolver.resolveComposeSession("t", "p", "alice", "app:notes/build", null, false))
                .isEqualTo("proc-app");
    }

    @Test
    void clean_dropsExistingProcessAndMessagesBeforeReuse() {
        SessionDocument session = session("sys-1", "alice");
        ThinkProcessDocument stale = mock(ThinkProcessDocument.class);
        when(stale.getId()).thenReturn("old-proc");
        ThinkProcessDocument fresh = mock(ThinkProcessDocument.class);
        when(fresh.getId()).thenReturn("new-proc");
        when(sessionService.findSystemSession("t", "p", "_damogran")).thenReturn(Optional.of(session));
        // First lookup (reset) sees the stale process; second (reuse-or-create) sees the fresh one.
        when(thinkProcessService.findByName("t", "sys-1", "_damogran"))
                .thenReturn(Optional.of(stale))
                .thenReturn(Optional.of(fresh));

        assertThat(resolver.resolveComposeSession("t", "p", "alice", null, null, true))
                .isEqualTo("new-proc");

        verify(chatMessageService).deleteByProcess("t", "sys-1", "old-proc");
        verify(thinkProcessService).delete("old-proc");
    }

    @Test
    void recipe_createsAgentViaActionRegistry_returningSpawnedId() {
        SessionDocument session = session("sys-1", "alice");
        when(sessionService.findSystemSession("t", "p", "_damogran")).thenReturn(Optional.of(session));
        when(thinkProcessService.findByName("t", "sys-1", "_damogran")).thenReturn(Optional.empty());
        when(actionRegistry.execute(any(), any(), eq(TriggerKind.TOOL)))
                .thenReturn(ActionResult.scheduled("agent-42"));

        assertThat(resolver.resolveComposeSession("t", "p", "alice", null, "arthur", false))
                .isEqualTo("agent-42");

        // Recipe path spawns via the registry — no plain eddie holder created.
        verify(thinkProcessService, never()).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void otherOwner_closesSessionInsteadOfLendingItsGrants() {
        SessionDocument alices = session("sys-alice", "alice");
        when(sessionService.findSystemSession("t", "p", "_damogran_name_build"))
                .thenReturn(Optional.of(alices));
        SessionDocument bobs = session("sys-bob", "bob");
        when(sessionService.create(eq("t"), eq("bob"), eq("p"), eq("_damogran_name_build"),
                any(), any(), any(), eq(true))).thenReturn(bobs);
        when(thinkProcessService.findByName("t", "sys-bob", "_damogran_name_build"))
                .thenReturn(Optional.empty());
        when(actionRegistry.execute(any(), any(), eq(TriggerKind.TOOL)))
                .thenReturn(ActionResult.scheduled("agent-bob"));

        assertThat(resolver.resolveComposeSession(
                "t", "p", "bob", "name:build", "arthur", false)).isEqualTo("agent-bob");

        verify(sessionService).close("sys-alice");
    }

    @Test
    void agentWithoutUser_isRejected() {
        // A free-prompted process must not hold the system trust boundary.
        assertThatThrownBy(() -> resolver.resolveComposeSession(
                "t", "p", SessionService.SYSTEM_OWNER, null, "arthur", false))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("cannot run without a user");
        verify(sessionService, never())
                .create(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void carrierMayRunAsSystemOwner() {
        // The inert WorkTarget holder never runs a turn — no user is honest here.
        when(sessionService.findSystemSession("t", "p", "_damogran")).thenReturn(Optional.empty());
        SessionDocument created = session("sys-sys", SessionService.SYSTEM_OWNER);
        when(sessionService.create(eq("t"), eq(SessionService.SYSTEM_OWNER), eq("p"), eq("_damogran"),
                any(), any(), any(), eq(true))).thenReturn(created);
        when(thinkProcessService.findByName("t", "sys-sys", "_damogran")).thenReturn(Optional.empty());
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getId()).thenReturn("carrier-1");
        when(thinkProcessService.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(process);

        assertThat(resolver.resolveComposeSession(
                "t", "p", SessionService.SYSTEM_OWNER, null, null, false)).isEqualTo("carrier-1");
    }

    @Test
    void blankRunAs_isRejected() {
        assertThatThrownBy(() -> resolver.resolveComposeSession("t", "p", "", null, null, false))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("runAs");
    }
}
