package de.mhus.vance.api.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Free-form status ping for tool boundaries, web-search queries, file IO
 * and other engine asides. Soft 120-character recommendation on
 * {@link #text} — never enforced server-side; clients may abbreviate or
 * line-wrap as they see fit.
 *
 * <p>Pure side-channel: status pings do not enter conversation history,
 * are not persisted, and do not flow back into the LLM context on the
 * next round-trip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("progress")
public class StatusPayload {

    private StatusTag tag;

    private String text;

    private @Nullable String detail;

    /**
     * Bare tool name on tool-boundary pings ({@link StatusTag#TOOL_START},
     * {@link StatusTag#TOOL_END}) — e.g. {@code "doc_read"}. Structured
     * alongside the prose {@link #text} so clients can render their own
     * compact, localised line ("doc_read · 4s") instead of parsing the
     * English sentence out of {@code text}. {@code null} on every ping
     * that isn't a tool boundary.
     */
    private @Nullable String tool;

    /**
     * Short human-readable teaser of the call, distilled from the tool's
     * parameters — e.g. {@code "src/Main.java, 42 lines, 1234 chars"} with
     * a capped preview block of the written lines. On close-pings
     * ({@link StatusTag#TOOL_END}) the same field carries the outcome
     * teaser ("Wrote 1234 chars").
     *
     * <p>Produced by {@code ToolTeasers} (vance-toolpack), which caps it
     * at a few lines and never echoes raw parameters or file contents.
     * This is a teaser on the wire, not the payload: clients render it
     * as-is and must not parse it. {@code null} for tools without a
     * meaningful subject and for non-tool pings.
     */
    private @Nullable String teaser;

    /**
     * {@code true} on a close-ping whose operation ended in an error.
     * Deliberately three-state: {@code null} means "not applicable" (open
     * pings, one-shot pings), so a client cannot mistake the absence of a
     * failure flag on an open ping for a successful outcome. The human-
     * readable cause is in {@link #detail}.
     */
    private @Nullable Boolean failed;

    /**
     * Correlation key linking an open ({@link StatusTag#TOOL_START},
     * {@link StatusTag#DELEGATING}) ping with its close ({@link StatusTag#TOOL_END},
     * {@link StatusTag#NODE_DONE}, {@link StatusTag#PHASE_DONE}). Lets the
     * client measure wall-clock per operation and dispatch concurrent
     * operations without mixing them up. {@code null} for one-shot pings
     * ({@link StatusTag#SEARCH}, {@link StatusTag#FETCH}, {@link StatusTag#FILE_READ},
     * {@link StatusTag#FILE_WRITE}, {@link StatusTag#WAITING}, {@link StatusTag#INFO}).
     */
    private @Nullable String operationId;

    /**
     * Cost of the operation just completed. Only populated on close-pings
     * (TOOL_END / NODE_DONE / PHASE_DONE); always {@code null} on open-
     * and one-shot pings.
     */
    private @Nullable UsageDelta usage;
}
