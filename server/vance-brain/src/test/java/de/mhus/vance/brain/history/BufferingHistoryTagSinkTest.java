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
        sink.emitFailure("file_edit", "gone");

        sink.discard();

        verify(service, never()).tag(any(), any());
        verify(service, never()).recordToolFailures(any(), any());
        assertThat(sink.peek()).isEmpty();
        assertThat(sink.peekFailures()).isEmpty();
    }

    // ──────────── failed tool calls (META_TOOL_FAILURES) ────────────

    @Test
    void emitFailure_recordsToolAndReason() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();

        sink.emitFailure("file_edit", "No such file: /tmp/x.vue");

        assertThat(sink.peekFailures()).containsExactly("file_edit → No such file: /tmp/x.vue");
    }

    @Test
    void emitFailure_dedupsIdenticalFailureWithinTurn() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();

        sink.emitFailure("file_edit", "gone");
        sink.emitFailure("file_edit", "gone");
        sink.emitFailure("file_edit", "other reason");

        assertThat(sink.peekFailures())
                .containsExactly("file_edit → gone", "file_edit → other reason");
    }

    @Test
    void emitFailure_capsTheNumberOfEntries() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();

        for (int i = 0; i < BufferingHistoryTagSink.MAX_FAILURES + 4; i++) {
            sink.emitFailure("tool" + i, "boom");
        }

        assertThat(sink.peekFailures()).hasSize(BufferingHistoryTagSink.MAX_FAILURES);
    }

    @Test
    void emitFailure_clipsOverlongReason() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();

        sink.emitFailure("file_edit", "x".repeat(BufferingHistoryTagSink.MAX_FAILURE_CHARS + 50));

        String entry = sink.peekFailures().get(0);
        assertThat(entry).endsWith("…");
        assertThat(entry.length())
                .isLessThan(BufferingHistoryTagSink.MAX_FAILURE_CHARS + 40);
    }

    @Test
    void emitFailure_withoutMessage_stillNamesTheFailingTool() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();

        sink.emitFailure("file_edit", null);

        assertThat(sink.peekFailures()).containsExactly("file_edit → no reason reported");
    }

    @Test
    void emitFailure_blankToolName_isIgnored() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();

        sink.emitFailure("  ", "boom");

        assertThat(sink.peekFailures()).isEmpty();
    }

    @Test
    void flushTo_writesFailuresAndClearsThem() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();
        ChatMessageService service = mock(ChatMessageService.class);
        sink.emit(Set.of("ERROR"));
        sink.emitFailure("file_edit", "gone");

        sink.flushTo("m-1", service);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<String>> cap =
                ArgumentCaptor.forClass(java.util.List.class);
        verify(service).recordToolFailures(eq("m-1"), cap.capture());
        assertThat(cap.getValue()).containsExactly("file_edit → gone");
        assertThat(sink.peekFailures()).isEmpty();
    }

    @Test
    void flushTo_failuresOnly_withoutAnyTags_stillWrites() {
        BufferingHistoryTagSink sink = new BufferingHistoryTagSink();
        ChatMessageService service = mock(ChatMessageService.class);
        sink.emitFailure("file_edit", "gone");

        sink.flushTo("m-1", service);

        verify(service).recordToolFailures(eq("m-1"), any());
    }
}
