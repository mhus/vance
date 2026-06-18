package de.mhus.vance.api.zaphod;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Mutable runtime state of a Zaphod process. Persisted on
 * {@code ThinkProcessDocument.engineParams.zaphodState}; restored
 * verbatim on resume so a Brain restart picks up the next pending
 * head without losing the already-collected replies.
 *
 * <p>See {@code specification/zaphod-engine.md} §3.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZaphodState {

    @Builder.Default
    private ZaphodPattern pattern = ZaphodPattern.COUNCIL;

    @Builder.Default
    private List<ZaphodHead> heads = new ArrayList<>();

    /** Sequential cursor — index of the next pending head to drive
     *  within the current round. Reset to 0 at the start of every
     *  round. */
    private int currentHeadIndex;

    /** Zero-based round counter. {@code council} always stays at 0
     *  (single-shot). {@code debate} increments after each completed
     *  round + consensus-check that did not reach consensus, up to
     *  {@link #maxRounds} - 1. */
    private int currentRound;

    /** Round budget. {@code council} is hard-set to 1 by the engine;
     *  {@code debate} reads from recipe params (default 3, hard-cap
     *  10 — see spec §13). */
    @Builder.Default
    private int maxRounds = 1;

    /** Set to {@code true} once the consensus-check LightLlm call
     *  returns {@code consensus: true}. Always {@code false} for
     *  {@code council}. */
    private boolean consensusReached;

    /** One-sentence reason from the consensus-check LLM, audit/debug
     *  + surfaced to the synthesizer so the final answer can
     *  acknowledge whether consensus was reached or maxRounds
     *  exhausted. {@code null} until at least one check ran. */
    private @Nullable String consensusReason;

    /** Optional recipe-supplied prefix for the synthesizer prompt
     *  (becomes the leading paragraph of the synthesizer's user
     *  message). {@code null} = use only the engine default. */
    private @Nullable String synthesizerPrompt;

    /** Final synthesized result — the full markdown body produced
     *  by the synthesizer turn. The synthesizer LLM emits a
     *  structured JSON object; this field carries the
     *  {@code synthesisMarkdown} member. The engine persists it as
     *  a project document at {@link #synthesisDocumentPath}
     *  immediately after parsing (Worker generates content,
     *  engine writes the file — see
     *  {@code instructions/general/engines.md} §"Tool usage").
     *  {@code null} until the synthesizer LLM call succeeds. */
    private @Nullable String synthesis;

    /** Short title (5-10 words) the synthesizer emitted alongside
     *  the markdown body. Used as the document title at
     *  persistence time and shown in the parent's DONE-payload. */
    private @Nullable String synthesisTitle;

    /** 1-2 sentence executive summary the synthesizer emitted
     *  alongside the markdown body. Surfaces as Arthur's relayed
     *  ASSISTANT reply — the chat user sees this directly without
     *  having to open the persisted document. */
    private @Nullable String synthesisSummary;

    /** Project-relative path where the engine persisted
     *  {@link #synthesis} as a markdown document. Default:
     *  {@code councils/<recipeName>/<runId>.md}; recipes can
     *  override via {@code params.outputPathTemplate}. */
    private @Nullable String synthesisDocumentPath;

    @Builder.Default
    private ZaphodStatus status = ZaphodStatus.SPAWNING;

    /** Set when {@link #status} = {@link ZaphodStatus#FAILED}. */
    private @Nullable String failureReason;

    /**
     * Idempotency guard for the REPLY-channel emit-path
     * ({@code ZaphodEngine.emitFinalReply}). The lane scheduler can
     * queue a follow-up {@code runTurn} task after
     * {@code closeProcess}; without this flag the parent would
     * receive the synthesis twice. Set to {@code true} once the
     * REPLY has been pushed onto the parent's inbox.
     */
    private boolean replyEmitted;
}
