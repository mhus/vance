package de.mhus.vance.api.slartibartfast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Audit record of one LLM round-trip during a Slartibartfast run.
 * Captured into {@link ArchitectState#getLlmCallRecords()} so
 * post-hoc analysis (or escalation review) can reconstruct exactly
 * which prompt produced which output and how the validator reacted.
 *
 * <p>Capture is governed by the recipe-level engineParam
 * {@code auditLlmCalls} (default {@code true} for Slartibartfast,
 * disable for cost-sensitive bulk runs).
 *
 * <p>The full prompt is intentionally <em>not</em> stored — only
 * a hash and a preview — to bound persisted state size and avoid
 * leaking arbitrary user content into engineParams. The
 * {@link #response} is stored fully because that's the planning
 * artifact under review.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmCallRecord {

    /** Stable id within the run. Conventional shape:
     *  {@code "llm1"}, {@code "llm2"}, … referenced from
     *  {@link PhaseIteration#getLlmCallRecordId()}. */
    private String id = "";

    /** Phase that issued the call. */
    @Builder.Default
    private ArchitectStatus phase = ArchitectStatus.FRAMING;

    /** {@link PhaseIteration#getIteration()} this call belongs to.
     *  Combined with {@link #phase} pins the call to one row in
     *  the iteration history. */
    private int iteration;

    /** SHA-256 hex of the full prompt, lowercase. Lets two runs
     *  with the same input be correlated without storing the
     *  prompt. */
    private String promptHash = "";

    /** First ~500 chars of the prompt — enough to recognise
     *  what was asked when reading the audit. Truncated with
     *  "..." suffix when the original exceeds the cap. */
    private String promptPreview = "";

    /** Verbatim model response. Subject of the validator's checks. */
    private String response = "";

    /** Resolved model alias (e.g. {@code "gemini:gemini-2.5-flash"}).
     *  Different from the recipe-level alias which may resolve
     *  through a setting cascade. */
    private String modelAlias = "";

    /** Wall-clock duration including streaming. */
    private long durationMs;

    /** Set when the call failed before producing usable output —
     *  e.g. provider timeout, schema-validation hard-fail past
     *  retry budget. {@code null} on success. */
    private @Nullable String failureReason;
}
