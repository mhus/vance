package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One {@code Zarniwoop} provider instance as the insights tab renders
 * it: identity + modalities the project can use, the current
 * availability verdict, an optional free-text status (Serper's "1552
 * credits left", Wikipedia's "no quota meter", an Agrajag cooldown
 * note …), and the per-pod usage counter so the operator can spot a
 * runaway query loop or a cold endpoint.
 *
 * <p>{@code usage} counts are best-effort and pod-local — they reset
 * on pod restart and on project suspend. The audit-log
 * ({@code _vance/logs/research/…}) is the persistent source of truth.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class ZarniwoopInsightsDto {

    /** Endpoint id from {@code research.endpoint.<id>}. */
    private String id;

    /** Human-readable name from the instance. */
    private String displayName;

    /** Protocol id (the wire-format adapter behind the endpoint). */
    private String protocol;

    /** Modalities this instance can serve (UPPER_CASE). */
    private List<String> modalities;

    /** Subject-area hints from the instance (UPPER_CASE). */
    private List<String> domains;

    /** Tiers this instance can serve (NORMAL / EXPERT). */
    private List<String> tiers;

    /**
     * Current availability: READY / NO_CREDENTIALS / QUOTA_EXHAUSTED /
     * COOLDOWN / DISABLED. Includes COOLDOWN even when
     * {@code availability()} reports READY, because the dispatcher
     * filters by cooldown separately — the insights view merges both
     * gates so the operator sees the effective state.
     */
    private String availability;

    /**
     * Free-text status string. Whatever the protocol wants the
     * operator to see at a glance: "1552 credits remaining" for Serper,
     * a Retry-After timestamp for a rate-limited endpoint, or null
     * when the protocol has nothing to add.
     */
    private @Nullable String statusText;

    /** Total invocations of this instance since the pod started. */
    private long callCount;

    /** Successful (non-error) invocations. */
    private long okCount;

    /** Hard-failure invocations (the dispatcher routed them to Agrajag). */
    private long errorCount;

    /** ISO-8601 timestamp of the most recent successful call, if any. */
    private @Nullable String lastUsedAt;

    /** ISO-8601 timestamp of the most recent hard failure, if any. */
    private @Nullable String lastErrorAt;

    /** Short message from the most recent hard failure. */
    private @Nullable String lastErrorMessage;

    /** Active cooldown signature from {@code ToolHealthService}, if any. */
    private @Nullable String activeCooldownSignature;

    /** ISO-8601 timestamp at which the active cooldown lifts. */
    private @Nullable String activeCooldownUntil;

    /**
     * Settings-default enable flag — driven by
     * {@code research.endpoint.<id>.enabled} (default {@code true}).
     * Persistent across pod restarts.
     */
    private boolean defaultEnabled;

    /**
     * Pod-local manual override set from the Insights tab.
     * {@code "ENABLED"} / {@code "DISABLED"} when set, {@code null}
     * when the instance follows the settings default. Lost on pod
     * restart and on project suspend.
     */
    private @Nullable String manualOverride;

    /**
     * Effective state the dispatcher uses — override wins over the
     * settings default. {@code false} hides the instance from
     * {@code research_search} / {@code research_rich} dispatch.
     */
    private boolean effectivelyEnabled;
}

