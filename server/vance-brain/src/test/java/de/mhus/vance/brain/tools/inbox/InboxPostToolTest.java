package de.mhus.vance.brain.tools.inbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Security regression (code-review-2 HIGH): inbox_post must authorize delivery to
 * targetUserId before creating an item — otherwise any tenant user could be
 * spammed with unsolicited action-requiring items via a raw LLM param.
 */
class InboxPostToolTest {

    private final MaximegalonService inboxItemService = mock(MaximegalonService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
    private final InboxPostTool tool = new InboxPostTool(
            inboxItemService, documentService, permissionService, contextFactory);

    @Test
    void deliveryToUnauthorizedUser_throws_andNeverCreates() {
        SecurityContext subject = SecurityContext.user("alice", "acme", List.of());
        when(contextFactory.forToolSubject("acme", "alice")).thenReturn(subject);
        doThrow(new RuntimeException("not permitted")).when(permissionService).enforce(
                eq(subject),
                eq(new Resource.InboxItem("acme", null, "bob")),
                eq(Action.WRITE));

        ToolInvocationContext ctx = new ToolInvocationContext("acme", "proj", "sess", "proc", "alice");

        assertThatThrownBy(() -> tool.invoke(
                Map.of("targetUserId", "bob", "type", "INFO", "title", "hi"), ctx))
                .isInstanceOf(RuntimeException.class);
        verify(inboxItemService, never()).create(any());
    }
}
