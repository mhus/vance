package de.mhus.vance.brain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.shared.chat.ChatMessageService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Buffer behaviour: tags accumulate, flush writes once and clears,
 * second flush is a no-op, sink swallows downstream failures so the
 * tool path stays unaffected.
 */
class BufferingHistoryTagSinkTest {

    @Test
    void emit_accumulatesTagsInOrder() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();

        sink.emit(Set.of("TOOL_CALL:a"));
        sink.emit(Set.of("FILE_EDIT", "TOOL_CALL:b"));

        assertThat(sink.peek()).contains("TOOL_CALL:a", "FILE_EDIT", "TOOL_CALL:b");
    }

    @Test
    void emit_dedups() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();

        sink.emit(Set.of("TOOL_CALL:a"));
        sink.emit(Set.of("TOOL_CALL:a", "FILE_EDIT"));

        assertThat(sink.peek()).hasSize(2)
                .contains("TOOL_CALL:a", "FILE_EDIT");
    }

    @Test
    void flushTo_writesOnceAndClears() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();
        ChatMessageService service = mock(ChatMessageService.class);
        sink.emit(Set.of("FILE_EDIT", "TOOL_CALL:edit"));

        sink.flushTo("m-1", service);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> cap = ArgumentCaptor.forClass(Set.class);
        verify(service).tag(eq("m-1"), cap.capture());
        assertThat(cap.getValue()).contains("FILE_EDIT", "TOOL_CALL:edit");
        assertThat(sink.peek()).isEmpty();
    }

    @Test
    void secondFlush_isNoOp() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();
        ChatMessageService service = mock(ChatMessageService.class);
        sink.emit(Set.of("X"));

        sink.flushTo("m-1", service);
        sink.flushTo("m-1", service);

        verify(service, org.mockito.Mockito.times(1)).tag(any(), any());
    }

    @Test
    void flushTo_blankMessageId_skipsAndKeepsBuffer() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();
        ChatMessageService service = mock(ChatMessageService.class);
        sink.emit(Set.of("X"));

        sink.flushTo("  ", service);

        verify(service, never()).tag(any(), any());
        assertThat(sink.peek()).contains("X");
    }

    @Test
    void flushTo_emptyBuffer_skipsCall() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();
        ChatMessageService service = mock(ChatMessageService.class);

        sink.flushTo("m-1", service);

        verify(service, never()).tag(any(), any());
    }

    @Test
    void flushTo_serviceThrows_swallowsAndStillClears() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();
        ChatMessageService service = mock(ChatMessageService.class);
        doThrow(new RuntimeException("mongo down")).when(service).tag(any(), any());
        sink.emit(Set.of("X"));

        // Must not throw.
        sink.flushTo("m-1", service);

        assertThat(sink.peek()).isEmpty();
    }

    @Test
    void discard_clearsWithoutWriting() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();
        ChatMessageService service = mock(ChatMessageService.class);
        sink.emit(Set.of("X", "Y"));

        sink.discard();

        verify(service, never()).tag(any(), any());
        assertThat(sink.peek()).isEmpty();
    }
}
