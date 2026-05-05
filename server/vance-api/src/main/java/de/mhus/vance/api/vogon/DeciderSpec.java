package de.mhus.vance.api.vogon;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Decider block on a worker phase — binary or categorical
 * classification. The engine matches the worker reply (case-insensitive,
 * first occurrence) against {@link #options}, persists the chosen token
 * to {@code flags[<storeAs>] = <token>}, then runs the first matching
 * {@link DeciderCase}.
 *
 * <p>{@code options} defaults to {@code [yes, no]} when empty — the
 * common "LLM, yes or no?" form.
 *
 * <p>{@link ScorerSpec} and {@code decider} are mutually exclusive on
 * the same phase.
 *
 * <p>See {@code specification/vogon-engine.md} §2.6.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeciderSpec {

    /** Allowed reply tokens. Empty list defaults to {@code [yes, no]}. */
    @Builder.Default
    private List<String> options = new ArrayList<>();

    /** Flag key for the chosen token. */
    private String storeAs = "";

    /** Match list — case-insensitive string-equality against the reply. */
    @Builder.Default
    private List<DeciderCase> cases = new ArrayList<>();

    /** Re-prompt cap when the reply doesn't contain any allowed token. */
    @Builder.Default
    private @Nullable Integer maxCorrections = 2;
}
