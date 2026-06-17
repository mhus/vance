package de.mhus.vance.api.toolhealth;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * One active cooldown entry on a Tool-Health record. Carries enough
 * context for the Insights UI to render a row plus a clear-button:
 * which error signature triggered it, when it expires, and (when
 * scoped to a single user) which user is affected.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("toolhealth")
public class ToolHealthCooldownDto {

    /** Stable key identifying the failure class — used by the clear API. */
    String errorSignature;

    /** When the cooldown expires (ISO-8601 instant). */
    String nextSpawnAllowedAt;

    /** Total invocations that hit this signature while the cooldown was active. */
    int hits;

    /** Last classification Agrajag assigned to this signature. */
    @Nullable ToolHealthClassification lastClassification;

    /** Last fire time of this signature (ISO-8601 instant). */
    @Nullable String lastTriggeredAt;

    /** Short audit string from Agrajag / pattern. */
    @Nullable String note;

    /** Set when the cooldown is scoped to a single user (USER_PERMISSION etc.). */
    @Nullable String userId;
}
