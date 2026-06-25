package de.mhus.vance.brain.lunkwill.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.lunkwill.LunkwillTermination;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CodeReviewDecideToolTest {

    private ChatMessageService chatMessageService;
    private ThinkProcessService thinkProcessService;
    private CodeReviewDecideTool tool;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        chatMessageService = mock(ChatMessageService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        tool = new CodeReviewDecideTool(chatMessageService, thinkProcessService);

        ctx = mock(ToolInvocationContext.class);
        lenient().when(ctx.processId()).thenReturn("reviewer-1");
        lenient().when(ctx.sessionId()).thenReturn("sess-1");
        lenient().when(ctx.tenantId()).thenReturn("tenant-x");

        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setId("reviewer-1");
        process.setTenantId("tenant-x");
        process.setSessionId("sess-1");
        when(thinkProcessService.findById("reviewer-1")).thenReturn(Optional.of(process));
    }

    @Test
    void approve_carriesTerminate_summary_persistsMessage() {
        Map<String, Object> out = tool.invoke(
                Map.of("outcome", "approve", "summary", "Clean and idiomatic."),
                ctx);

        assertThat(out).containsEntry(LunkwillTermination.RESULT_TERMINATE_KEY, true);
        assertThat(out).containsEntry("outcome", "approve");
        assertThat(out).containsEntry("summary", "Clean and idiomatic.");
        assertThat(out).doesNotContainKeys("reason", "followUp");

        ArgumentCaptor<ChatMessageDocument> msg = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService).append(msg.capture());
        assertThat(msg.getValue().getContent()).startsWith("Review APPROVED.");
        assertThat(msg.getValue().getContent()).contains("Clean and idiomatic.");
    }

    @Test
    void reopen_withReasonAndFollowUp_carriesAllFields() {
        Map<String, Object> out = tool.invoke(
                Map.of(
                        "outcome", "reopen",
                        "reason", "NullPointer risk in UserService.validate",
                        "followUp", "Add a null-check before the trim()"),
                ctx);

        assertThat(out).containsEntry(LunkwillTermination.RESULT_TERMINATE_KEY, true);
        assertThat(out).containsEntry("outcome", "reopen");
        assertThat(out).containsEntry("reason", "NullPointer risk in UserService.validate");
        assertThat(out).containsEntry("followUp", "Add a null-check before the trim()");

        ArgumentCaptor<ChatMessageDocument> msg = ArgumentCaptor.forClass(ChatMessageDocument.class);
        verify(chatMessageService).append(msg.capture());
        assertThat(msg.getValue().getContent()).contains("REOPENED");
        assertThat(msg.getValue().getContent()).contains("NullPointer risk");
        assertThat(msg.getValue().getContent()).contains("Follow-up:");
    }

    @Test
    void approveWithoutSummary_rejects() {
        assertThatThrownBy(() -> tool.invoke(
                Map.of("outcome", "approve"),
                ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("summary");
        verify(chatMessageService, never()).append(any());
    }

    @Test
    void reopenWithoutReason_rejects() {
        assertThatThrownBy(() -> tool.invoke(
                Map.of("outcome", "reopen"),
                ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void unknownOutcome_rejects() {
        assertThatThrownBy(() -> tool.invoke(
                Map.of("outcome", "maybe", "summary", "x"),
                ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("approve");
    }

    @Test
    void missingOutcome_rejects() {
        assertThatThrownBy(() -> tool.invoke(
                Map.of("summary", "x"),
                ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("outcome");
    }

    @Test
    void caseInsensitiveOutcome_accepted() {
        Map<String, Object> out = tool.invoke(
                Map.of("outcome", "APPROVE", "summary", "ok"),
                ctx);
        assertThat(out).containsEntry("outcome", "approve");
    }
}
