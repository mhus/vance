package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * LLM power state of a tenant, as served by
 * {@code GET /brain/{tenant}/llm-power}: the main switch and one switch per
 * user. While the main switch is off no model call of the tenant goes out,
 * whatever the user switches say.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class LlmPowerStatusDto {

    /** Main switch of the tenant. */
    private boolean enabled;

    /** When the main switch was turned off; {@code null} while it is on. */
    private @Nullable Instant since;

    /** Who turned the main switch off, as recorded; {@code null} while it is on. */
    private @Nullable String note;

    /**
     * Main switch as the answering pod currently enforces it. Differs from
     * {@link #enabled} until that pod's next refresh has picked up a change.
     */
    private boolean effectiveEnabled;

    /** The answering pod's clock at the time of the answer. */
    private @Nullable Instant serverTime;

    /**
     * When a change made now has reached every pod: the next refresh tick
     * (every pod ticks at the same wall-clock second) plus a short grace.
     * Clients wait {@code verifyAt - serverTime} and then read again.
     */
    private @Nullable Instant verifyAt;

    /** Every user of the tenant, plus switched-off logins that no longer exist. */
    @Builder.Default
    private List<LlmPowerUserDto> users = new ArrayList<>();
}
