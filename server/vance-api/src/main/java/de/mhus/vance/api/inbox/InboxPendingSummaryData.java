package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@code inbox-pending-summary} notification sent
 * once at session welcome / resume time. Lets the client display a
 * counter without an extra round-trip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxPendingSummaryData {

    private int totalPending;

    @Builder.Default
    private Map<Criticality, Integer> byCriticality = new LinkedHashMap<>();

    private @Nullable Instant oldestPendingAt;
}
