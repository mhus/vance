package de.mhus.vance.api.marvin;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Output of a worker's VALIDATE-phase LLM call. Bounded loop
 * (max 2 iterations); see {@code specification/marvin-engine.md}
 * §4.5 for verdict routing.
 */
public record ValidateOutput(
        ValidateVerdict verdict,
        @Nullable List<String> issues,
        @Nullable String hint,
        @Nullable String reason) {}
