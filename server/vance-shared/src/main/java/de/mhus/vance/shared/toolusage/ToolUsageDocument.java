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
 * <p><b>Keyed per role.</b> {@code recipeName} is part of the key because
 * demand is role-specific: a coding worker hammering {@code file_read} says
 * nothing about what the chat orchestrator in the same project needs. Without
 * it the busiest worker would train everyone else's ranking.
 *
 * <p>{@code family} is denormalised so an operator can group by it in a
 * Mongo query without re-deriving the name rule.
 *
 * <p><b>No retention, deliberately.</b> Nothing deletes a row — not even
 * for a {@code _user_<login>} project whose user is gone. Cardinality is
 * (tenant × project × recipe × tool) and both repository queries are
 * covered by the unique index, so an old row costs storage, not query
 * time; and a row that is never written again simply stops influencing
 * the ranking, because ordering is comparative. There is no project-delete
 * path in the tree either, so a TTL here would be the only cleanup in a
 * system that has none. When one arrives, pruning this collection belongs
 * in it.
 */
@Document(collection = "tool_usage_stats")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_project_recipe_tool_uidx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'recipeName': 1, 'toolName': 1 }",
                unique = true),
        @CompoundIndex(
                name = "tenant_project_recipe_family_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'recipeName': 1, 'family': 1 }")
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

    /**
     * Role the counters belong to: the process's recipe name, falling back
     * to the engine name when a process carries no recipe. Never null —
     * unattributable writes use {@link ToolUsageService#ROLE_UNKNOWN}.
     */
    private String recipeName;

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
