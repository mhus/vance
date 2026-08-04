package de.mhus.vance.foot.ui;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.markdown.MarkdownRenderState;
import de.mhus.vance.foot.session.SessionService;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StreamingDisplay#flushBuffered} — the mid-turn
 * flush that interleaves buffered assistant narration with the tool
 * lines that follow it (markdown mode buffers per-turn, so without this
 * the whole turn's prose piles up in one block at commit).
 */
class StreamingDisplayTest {

    private final ChatTerminal terminal = mock(ChatTerminal.class);
    private final PromptGate promptGate = mock(PromptGate.class);
    private final SessionService sessions = mock(SessionService.class);
    private final MarkdownRenderState markdown = mock(MarkdownRenderState.class);
    private final FootConfig config = mock(FootConfig.class);

    private StreamingDisplay newDisplay() {
        // Non-exclusive → onChunk buffers instead of streaming inline.
        when(promptGate.isExclusive()).thenReturn(false);
        when(sessions.activeProcess()).thenReturn("chat");
        return new StreamingDisplay(terminal, promptGate, sessions, markdown, config,
                mock(ThinkingVisibility.class), mock(ColorResolver.class));
    }

    @Test
    void flushBuffered_rendersBufferedNarration_thenClears() {
        StreamingDisplay d = newDisplay();
        d.onChunk("p1", "chat", ChatRole.ASSISTANT, "Let me check ");
        d.onChunk("p1", "chat", ChatRole.ASSISTANT, "the specs.");

        d.flushBuffered("p1");

        verify(terminal).chatMarkdown(eq("[chat · assistant] "), eq("Let me check the specs."));

        // Buffer is now empty — a second flush renders nothing more.
        d.flushBuffered("p1");
        verify(terminal, times(1)).chatMarkdown(any(), any());
    }

    @Test
    void flushBuffered_unknownProcess_isNoop() {
        StreamingDisplay d = newDisplay();
        d.flushBuffered("nope");
        verify(terminal, never()).chatMarkdown(any(), any());
        verify(terminal, never()).worker(any());
    }
}
