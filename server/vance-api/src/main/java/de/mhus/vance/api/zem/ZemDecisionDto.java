package de.mhus.vance.api.zem;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Result of one decision: all answers for one state. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("zem")
public class ZemDecisionDto {

    /** Model id as the provider reported it — versioned where the
     *  provider versions (e.g. {@code typesafe/jev-1.13-20260917}). Log
     *  it next to thresholds: a new version can shift the calibration. */
    private String model;

    /** Answers in question order. */
    private List<ZemAnswerDto> answers;

    private @Nullable Long inputTokens;

    /** Vendor-reported cost in USD, when the provider sends one
     *  (OpenRouter does, TypeSafe direct and Ollama do not). */
    private @Nullable Double costUsd;

    /** Wall-clock time of the provider call(s). */
    private long latencyMs;
}
