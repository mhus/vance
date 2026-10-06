package de.mhus.vance.brain.ai;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;

/**
 * Raw wire transcript of one provider response that arrived with neither
 * text nor a tool call — the post-mortem evidence the HTTP layer captures
 * for {@link EmptyResponseDiagnosticService}.
 *
 * <p>The empty-response diagnosis has an evidence gap: the resilience layer
 * sees the <em>aggregated</em> result (an empty {@code AiMessage}) and the
 * request that produced it, but never the bytes the endpoint actually sent.
 * Whether the gateway emitted a single empty token, mangled tool-call
 * arguments mid-stream, answered from a stub, or folded a content filter
 * into {@code finish_reason:"stop"} is only visible in the raw frames —
 * and an empty response is tiny by definition, so keeping them costs
 * almost nothing. Captures register only when the wire itself looked
 * empty (no content delta, no tool call); healthy responses leave no
 * trace at all.
 *
 * <p>Frames are stored verbatim: one entry per SSE {@code data:} payload
 * for a streamed capture, or the complete response body for a sync one.
 * Nothing is parsed beyond the empty-detection heuristic at capture time —
 * interpretation belongs to whoever reads the transcript, because the
 * whole point is to see exactly what the endpoint said, not what a
 * parser made of it.
 *
 * @param capturedAt wall-clock instant of stream close / response arrival
 * @param kind       transport the capture came from
 * @param baseUrl    endpoint the request went to — routing-relevant for
 *                   gateway diagnoses (provider vs. gateway vs. cache stub)
 * @param modelName  model name as configured on the request
 * @param statusCode HTTP status of the response
 * @param headers    response headers, credential-bearing ones redacted —
 *                   request-id and rate-limit headers are exactly what a
 *                   provider support ticket asks for
 * @param frames     raw SSE payloads ({@code data:} content without the
 *                   prefix) or the sync body, in arrival order
 * @param truncated  true when the capture hit a size cap and stopped
 *                   recording early (see the HTTP recorder)
 */
@NullMarked
public record EmptyResponseEvidence(
        Instant capturedAt,
        Kind kind,
        String baseUrl,
        String modelName,
        int statusCode,
        List<String> headers,
        List<String> frames,
        boolean truncated) {

    /** Transport the capture came from — a streamed SSE response or one sync response. */
    public enum Kind {
        STREAM,
        SYNC
    }

    /**
     * True when this capture plausibly belongs to the chain-entry label.
     * Labels are {@code "instance:modelName"} ({@code AiChatConfig#fullName()}),
     * so a capture matches whenever the label ends in the captured model
     * name — instance prefixes differ per chain entry and never appear on
     * the wire request.
     */
    public boolean matchesModelLabel(String modelLabel) {
        return modelLabel.endsWith(":" + modelName);
    }

    /**
     * Human-readable transcript for the WARN log and the Fook report:
     * capture header, response headers, one line per frame. Bounded by
     * construction — the recorder caps what it stores.
     */
    public String transcript() {
        StringBuilder out = new StringBuilder();
        out.append('[')
                .append(kind().name().toLowerCase(Locale.ROOT))
                .append(' ')
                .append(modelName())
                .append(" @ ")
                .append(baseUrl())
                .append(" status=")
                .append(statusCode())
                .append(" captured=")
                .append(capturedAt())
                .append(']');
        for (String header : headers()) {
            out.append("\n  ").append(header);
        }
        if (frames().isEmpty()) {
            out.append("\n  (stream closed without a single data event)");
        }
        for (int i = 0; i < frames().size(); i++) {
            out.append("\n  frame ").append(i + 1).append(": ").append(frames().get(i));
        }
        if (truncated()) {
            out.append("\n  (capture truncated at the recorder's size cap)");
        }
        return out.toString();
    }
}
