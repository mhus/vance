package de.mhus.vance.brain.ai.attachment;

import de.mhus.vance.api.attachment.AttachmentRef;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects attachments that tool calls produced during a turn, so the
 * engine can put them in front of the model on its next LLM call.
 *
 * <p>Why a sink and not a return value: the image is discovered deep in
 * the dispatch path ({@code ContextToolsApi.invoke}), but it can only be
 * delivered where the message list is assembled — in the engine loop.
 * The same shape {@code HistoryTagSink} uses for the same reason.
 *
 * <p>Lifecycle is per turn and single-threaded: tool calls in a batch run
 * sequentially on the lane thread, and {@link #drain()} is called between
 * batches. Nothing here needs to be thread-safe, and pretending otherwise
 * would suggest a concurrency the lane deliberately does not have.
 */
public class ToolAttachmentSink {

    private final List<AttachmentRef> pending = new ArrayList<>();

    /** Sink that discards everything — for engines that don't show images. */
    public static final ToolAttachmentSink NOOP = new ToolAttachmentSink() {
        @Override public void emit(List<AttachmentRef> refs) { }
        @Override public List<AttachmentRef> drain() { return List.of(); }
    };

    public void emit(List<AttachmentRef> refs) {
        if (refs != null && !refs.isEmpty()) {
            pending.addAll(refs);
        }
    }

    /** Whether anything is waiting — cheap check for the engine loop. */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * Takes everything collected so far and empties the sink. Draining is
     * what guarantees an image is shown once: a second LLM call in the
     * same turn must not re-send the picture it already saw.
     */
    public List<AttachmentRef> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<AttachmentRef> out = List.copyOf(pending);
        pending.clear();
        return out;
    }
}
