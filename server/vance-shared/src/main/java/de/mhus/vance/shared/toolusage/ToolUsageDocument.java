package de.mhus.vance.shared.toolusage;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Rolling counters for one tool in one project. Written by
 * {@link ToolUsageService} as {@code $inc} upserts (never read-modify-write),
 * read back aggregated per project to order tools inside a priority class.
 *
 * <p><b>Two counters, on purpose.</b> {@code calls} counts successful
 * invocations — demand that already cleared every hurdle. {@code discoveryHits}
 * counts how often the model asked for the tool's schema through
 * {@code tool_description}, i.e. demand measured <em>before</em> the
 * hurdle. Only counting calls would make the budget self-reinforcing:
 * a demoted tool is harder to reach, so it gets called less, so it stays
 * demoted. The discovery counter is the honest signal.
 *
 * <p>{@code family} is denormalised so an operator can group by it in a
 * Mongo query without re-deriving the name rule.
 */
@Document(collection = "tool_usage_stats")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_project_tool_uidx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'toolName': 1 }",
                unique = true),
        @CompoundIndex(
                name = "tenant_project_family_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'family': 1 }")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolUsageDocument {

    @Id
    private @Nullable String id;

    private String tenantId;

    /** Project the counters belong to. Never null — system scopes use {@code _tenant}. */
    private String projectId;

    private String toolName;

    /** Name-derived family (see {@code ToolFamily}), denormalised for grouping. */
    private @Nullable String family;

    /** Successful invocations. */
    private long calls;

    /** {@code tool_description} lookups — demand before the deferral hurdle. */
    private long discoveryHits;

    private @Nullable Instant lastCallAt;

    private @Nullable Instant lastDiscoveryAt;
}
