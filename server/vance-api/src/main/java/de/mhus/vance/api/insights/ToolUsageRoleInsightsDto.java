package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Measured tool demand of one <b>role</b> in a project. The role is the
 * recipe a process ran under, falling back to the engine name when it
 * carries none; {@code _unknown} collects what cannot be attributed.
 *
 * <p>Why grouped by role rather than by project: a coding worker calling
 * {@code file_read} 153 times says nothing about what the chat
 * orchestrator in the same project needs. The tool-surface budget uses
 * these counters to order candidates <em>inside</em> a priority class,
 * and a shared pool would let the busiest worker train everyone else's
 * ranking. See {@code specification/public/server-tools.md} §14.4.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class ToolUsageRoleInsightsDto {

    /** Recipe name, engine name as fallback, or {@code _unknown}. */
    private String role;

    /** Number of distinct tools this role has touched. */
    private int toolCount;

    private long calls;

    private long discoveryHits;

    /** {@code calls + discoveryHits} over all tools of this role. */
    private long demand;

    /** Most recent call or discovery in this role, whichever is newer. */
    private @Nullable Instant lastActivityAt;

    /** Tools of this role, most-demanded first. */
    private List<ToolUsageEntryInsightsDto> tools = List.of();
}
