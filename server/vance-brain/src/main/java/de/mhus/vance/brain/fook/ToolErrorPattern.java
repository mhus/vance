package de.mhus.vance.brain.fook;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import java.time.Duration;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One parsed pattern rule. Built by {@link ToolErrorPatternResolver}
 * from the merged document cascade.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolErrorPattern {

    public enum HealthAction { NONE, MARK_UNAVAILABLE, MARK_DEGRADED }

    /**
     * Special string marker on {@link #cooldown} that means "read the
     * {@code retry-after} response header and use that value".
     */
    public static final Duration COOLDOWN_FROM_RETRY_AFTER = Duration.ofSeconds(-1);

    private String id = "";

    private @Nullable Integer httpStatus;
    private @Nullable int[] httpStatusRange;          // [lo, hi] inclusive
    private @Nullable List<String> exceptionTypes;
    private @Nullable List<String> bodyContains;
    private @Nullable List<String> errorCodes;

    @Default
    private String signature = "";

    @Default
    private ToolHealthClassification classification = ToolHealthClassification.UNCLEAR;

    /**
     * {@code null} → no explicit cooldown, fall back to the
     * classification's default. {@link #COOLDOWN_FROM_RETRY_AFTER} →
     * sentinel meaning "header:retry-after".
     */
    private @Nullable Duration cooldown;

    @Default
    private HealthAction healthAction = HealthAction.NONE;

    private boolean locked;

    private @Nullable String note;
}
