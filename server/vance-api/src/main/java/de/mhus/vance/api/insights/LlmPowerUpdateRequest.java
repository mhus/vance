package de.mhus.vance.api.insights;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code PUT /brain/{tenant}/llm-power} and
 * {@code PUT /brain/{tenant}/llm-power/users/{userId}}: the new position of
 * the switch.
 *
 * <p>{@code enabled} is required: a boxed {@link Boolean} with {@link NotNull}
 * rather than a primitive, so that a body without it ({@code {}}) is rejected
 * with 400 instead of silently reading as {@code false} — which would switch
 * the tenant or user off.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("insights")
public class LlmPowerUpdateRequest {

    @NotNull
    private Boolean enabled;
}
