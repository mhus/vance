package de.mhus.vance.api.insights;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code PUT /brain/{tenant}/llm-power} and
 * {@code PUT /brain/{tenant}/llm-power/users/{userId}}: the new position of
 * the switch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("insights")
public class LlmPowerUpdateRequest {

    private boolean enabled;
}
