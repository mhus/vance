package de.mhus.vance.api.vogon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Scorer block on a worker phase. After the worker reaches DONE the
 * engine extracts the last JSON object from its reply, validates the
 * mandatory {@code score: 0..1} field plus everything declared in
 * {@link #schema}, persists every top-level field to
 * {@code flags[<storeAs>_<field>]} (and the full object to
 * {@code phaseArtifacts[<phase>].scorerOutput}), then runs the first
 * matching case from {@link #cases}.
 *
 * <p>{@code scorer} and {@link DeciderSpec} are mutually exclusive on
 * the same phase — strategy-load validation rejects mixing them.
 *
 * <p>See {@code specification/vogon-engine.md} §2.5.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScorerSpec {

    /**
     * Optional schema reminder — engine only uses it to surface
     * "missing field X" in the re-prompt loop. The actual contract
     * is enforced by the worker's recipe-prompt; the schema lives
     * there too. Keys are field names, values are type hints
     * ({@code "float"}, {@code "string"}, {@code "list"}).
     */
    @Builder.Default
    private Map<String, Object> schema = new LinkedHashMap<>();

    /**
     * Flag-key prefix for persisted fields. {@code storeAs="lector"}
     * with reply {@code {score: 0.8, summary: "ok"}} produces
     * {@code flags.lector_score = 0.8}, {@code flags.lector_summary = "ok"},
     * and {@code flags.lector = <full object>}.
     */
    private String storeAs = "";

    /**
     * Switch list. Evaluated in declared order against the
     * {@code score} float. First match wins, executes its
     * {@link ScorerCase#getDoActions()}. Without a {@code defaultMatch}
     * case and no other match, no action fires.
     */
    @Builder.Default
    private List<ScorerCase> cases = new ArrayList<>();

    /**
     * Maximum number of format-correction re-prompts when the worker
     * reply doesn't parse / fails the schema check. 0 disables the
     * loop (fail-fast on first invalid reply). Default: 2 (matches
     * Marvin's validator-loop cap).
     */
    @Builder.Default
    private @Nullable Integer maxCorrections = 2;
}
