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

    /** Sequential cursor — index of the next pending head to drive. */
    private int currentHeadIndex;

    /** Optional recipe-supplied prefix for the synthesizer prompt
     *  (becomes the leading paragraph of the synthesizer's user
     *  message). {@code null} = use only the engine default. */
    private @Nullable String synthesizerPrompt;

    /** Final synthesized result. Set after the synthesizer LLM-call
     *  succeeds; {@code null} until then. */
    private @Nullable String synthesis;

    @Builder.Default
    private ZaphodStatus status = ZaphodStatus.SPAWNING;

    /** Set when {@link #status} = {@link ZaphodStatus#FAILED}. */
    private @Nullable String failureReason;
}
