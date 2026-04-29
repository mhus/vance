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
