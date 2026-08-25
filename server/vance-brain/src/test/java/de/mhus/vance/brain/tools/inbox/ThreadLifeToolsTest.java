package de.mhus.vance.brain.tools.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.inbox.InboxAuthz;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonRuleException;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three tools that give a process a say over its own thread life:
 * acknowledging, pulling somebody in, and getting out.
 *
 * <p>What is pinned here is the difference between them. Reacting and leaving
 * act on the caller's own relationship to a thread and gate on seeing it;
 * inviting reaches into <em>somebody else's</em> inbox and gates on WRITE
 * there — the invitee is a raw model parameter, so a missing check would let an
 * agent put a badge on any screen in the tenant. And leaving must not become a
 * way to duck a decision.
 */
class ThreadLifeToolsTest {

    private static final String TENANT = "acme";
    private static final String OWNER = "_trillian-adam-4711";
    private static final String OTHER = "mara";

    private MaximegalonService threads;
    private PermissionService permissionService;
    private InboxToolSupport support;

    private ThreadReactTool react;
    private ThreadInviteTool invite;
    private ThreadLeaveTool leave;

    @BeforeEach
    void setUp() {
        threads = mock(MaximegalonService.class);
        InboxAuthz authz = mock(InboxAuthz.class);
        permissionService = mock(PermissionService.class);
        SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
        when(contextFactory.forToolSubject(any(), any()))
                .thenReturn(SecurityContext.user(OWNER, TENANT, List.of()));
        when(permissionService.check(any(), any(), any())).thenReturn(true);
        support = new InboxToolSupport(threads, permissionService, contextFactory, authz);

        react = new ThreadReactTool(threads, support);
        invite = new ThreadInviteTool(threads, support);
        leave = new ThreadLeaveTool(threads, support);

        when(threads.findById(TENANT, "t1")).thenReturn(Optional.of(doc()));
    }

    private static MaximegalonDocument doc() {
        return MaximegalonDocument.builder()
                .id("t1").tenantId(TENANT)
                .type(MaximegalonType.OUTPUT_TEXT)
                .status(MaximegalonStatus.PENDING)
                .assignedToUserId(OWNER)
                .build();
    }

    private static ToolInvocationContext ctx() {
        ToolInvocationContext c = mock(ToolInvocationContext.class);
        when(c.tenantId()).thenReturn(TENANT);
        when(c.userId()).thenReturn(OWNER);
        return c;
    }

    private static Resource.InboxItem inboxOf(String user) {
        return new Resource.InboxItem(TENANT, "", user);
    }

    // ─────────────────────────── react ───────────────────────────

    @Test
    void react_putsTheKeyOnTheThreadAndSaysNobodyWasTold() {
        when(threads.react(TENANT, "t1", null, "eyes", OWNER, true))
                .thenReturn(Optional.of(doc()));

        Map<String, Object> out = react.invoke(
                Map.of("threadId", "t1", "key", "eyes"), ctx());

        assertThat(out.get("key")).isEqualTo("eyes");
        assertThat(out.get("on")).isEqualTo(true);
        // The caller has to know the signal was quiet, or it reaches for a
        // message to be sure it was heard.
        assertThat(String.valueOf(out.get("note"))).contains("Nobody was notified");
    }

    @Test
    void react_withAKeyOutsideTheVocabulary_isRefused() {
        // The value of a wordless signal is that everyone reads it the same
        // way; a free field lets a model invent a private vocabulary on the
        // one channel that carries no words to explain itself.
        assertThatThrownBy(() -> react.invoke(
                Map.of("threadId", "t1", "key", "party_parrot"), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("thumbsup");

        verify(threads, never()).react(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void react_withAnEmojiCharacterInsteadOfAShortcode_isRefused() {
        // Skin-tone variants are separate codepoints and would file the same
        // reaction twice — the whole reason keys are shortcodes.
        assertThatThrownBy(() -> react.invoke(
                Map.of("threadId", "t1", "key", "👍"), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("shortcodes");
    }

    // ─────────────────────────── invite ──────────────────────────

    @Test
    void invite_authorizesTheInviteesInboxNotTheThread() {
        when(threads.invite(TENANT, "t1", OTHER, OWNER)).thenReturn(Optional.of(doc()));

        invite.invoke(Map.of("threadId", "t1", "userId", OTHER), ctx());

        verify(permissionService).enforce(any(), eq(inboxOf(OTHER)), eq(Action.WRITE));
    }

    @Test
    void invite_withoutWriteOnThatInbox_isRefusedBeforeAnythingIsWritten() {
        doThrow(new PermissionDeniedException(
                        SecurityContext.user(OWNER, TENANT, List.of()),
                        inboxOf(OTHER), Action.WRITE))
                .when(permissionService).enforce(any(), eq(inboxOf(OTHER)), eq(Action.WRITE));

        assertThatThrownBy(() -> invite.invoke(
                Map.of("threadId", "t1", "userId", OTHER, "reason", "look at this"), ctx()))
                .isInstanceOf(PermissionDeniedException.class);

        verify(threads, never()).invite(any(), any(), any(), any());
        // Not even the reason: a refused invitation must leave no trace in a
        // thread the caller was not allowed to pull anyone into.
        verify(threads, never()).postMessage(any(), any(), any(), any(), any());
    }

    @Test
    void invite_postsTheReasonBeforeTheInvitation() {
        // The invitation makes the thread unread for them; opening it should
        // find the explanation already there.
        when(threads.invite(any(), any(), any(), any())).thenReturn(Optional.of(doc()));

        invite.invoke(
                Map.of("threadId", "t1", "userId", OTHER, "reason", "your call"), ctx());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(threads);
        order.verify(threads).postMessage(TENANT, "t1", OWNER, "your call", null);
        order.verify(threads).invite(TENANT, "t1", OTHER, OWNER);
    }

    @Test
    void invite_yourself_isRefused() {
        assertThatThrownBy(() -> invite.invoke(
                Map.of("threadId", "t1", "userId", OWNER), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("already on this thread");

        verify(threads, never()).invite(any(), any(), any(), any());
    }

    // ─────────────────────────── leave ───────────────────────────

    @Test
    void leave_unsubscribesAndSaysTheMatterIsUnchanged() {
        when(threads.setFollowing(TENANT, "t1", OWNER, false))
                .thenReturn(Optional.of(doc()));

        Map<String, Object> out = leave.invoke(Map.of("threadId", "t1"), ctx());

        assertThat(out.get("left")).isEqualTo(true);
        // "I am off it" and "it is handled" are the two things easiest to
        // confuse here, and only one of them is true.
        assertThat(String.valueOf(out.get("note"))).contains("Nothing about the matter changed");
    }

    @Test
    void leave_asTheAssigneeOfAnOpenAsk_isRefusedAndPointsAtDelegate() {
        // Otherwise unsubscribing is a way to duck a decision a process is
        // blocked on.
        when(threads.setFollowing(TENANT, "t1", OWNER, false))
                .thenThrow(new MaximegalonRuleException(
                        MaximegalonRuleException.ASSIGNEE_MUST_STAY, "waiting on you"));

        assertThatThrownBy(() -> leave.invoke(Map.of("threadId", "t1"), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("thread_delegate");
    }

    @Test
    void leave_refusedForTheAssignee_leavesNoFarewellBehind() {
        // The note has to go out before the leave (visibility), so a refusal
        // that arrives afterwards would strand "I am off this, ask somebody
        // else" on a thread the author is still the assignee of — and a
        // message cannot be taken back.
        when(threads.findById(TENANT, "t1")).thenReturn(Optional.of(openAsk()));

        assertThatThrownBy(() ->
                leave.invoke(Map.of("threadId", "t1", "note", "not mine"), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("thread_delegate");

        verify(threads, never()).postMessage(any(), any(), any(), any(), any());
        verify(threads, never()).setFollowing(any(), any(), any(), anyBoolean());
    }

    /** The one shape that holds its assignee: an ask, open, waiting on them. */
    private static MaximegalonDocument openAsk() {
        MaximegalonDocument doc = doc();
        doc.setRequiresAction(true);
        return doc;
    }

    @Test
    void leave_postsItsNoteWhileItStillCanSeeTheThread() {
        when(threads.setFollowing(any(), any(), any(), anyBoolean()))
                .thenReturn(Optional.of(doc()));

        leave.invoke(Map.of("threadId", "t1", "note", "not mine"), ctx());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(threads);
        order.verify(threads).postMessage(TENANT, "t1", OWNER, "not mine", null);
        order.verify(threads).setFollowing(TENANT, "t1", OWNER, false);
    }

    @Test
    void allThree_withoutUserBound_refuseInsteadOfActingAsSystem() {
        ToolInvocationContext headless = mock(ToolInvocationContext.class);
        when(headless.tenantId()).thenReturn(TENANT);
        when(headless.userId()).thenReturn(null);

        assertThatThrownBy(() -> react.invoke(
                Map.of("threadId", "t1", "key", "eyes"), headless))
                .hasMessageContaining("no user bound");
        assertThatThrownBy(() -> invite.invoke(
                Map.of("threadId", "t1", "userId", OTHER), headless))
                .hasMessageContaining("no user bound");
        assertThatThrownBy(() -> leave.invoke(Map.of("threadId", "t1"), headless))
                .hasMessageContaining("no user bound");
    }
}
