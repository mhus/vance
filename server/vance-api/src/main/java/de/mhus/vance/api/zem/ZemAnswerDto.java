package de.mhus.vance.api.zem;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One answer of a decision model. Which value fields are set depends on
 * {@link #type}; {@link ZemAnswerType#REFUSAL} carries none.
 *
 * <p>Probabilities are passed through as the provider reports them
 * (rounded or not); comparisons against thresholds belong to the
 * caller. Thresholds are not portable between models — see
 * {@code planning/zem-engine.md} §6.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("zem")
public class ZemAnswerDto {

    /** Name of the question this answers. */
    private String name;

    private ZemAnswerType type;

    /** {@code p(yes)} — set for {@link ZemAnswerType#NOUL}. */
    private @Nullable Double noul;

    /** Most likely option — set for {@link ZemAnswerType#CHOICE}. */
    private @Nullable String choice;

    /** Expected level {@code Σ i·p_i}, may fall between two levels — set
     *  for {@link ZemAnswerType#SCORE}. */
    private @Nullable Double score;

    /** Distribution. CHOICE: option → p. SCORE: level index as string
     *  ({@code "0"}…) → p. */
    private @Nullable Map<String, Double> probabilities;

    /** SCORE only: level index as string → the level description as
     *  sent (string, map or list). */
    private @Nullable Map<String, Object> legend;

    /** 0..1 — provider-reported, or computed from the distribution when
     *  the provider sends none (NOUL never carries one on the wire). */
    private @Nullable Double confidence;
}
