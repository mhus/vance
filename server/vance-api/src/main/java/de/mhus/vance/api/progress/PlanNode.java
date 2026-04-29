package de.mhus.vance.api.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One node in an engine-emitted plan snapshot. Recursive — {@link #children}
 * holds further nodes for engines whose plans are trees (Marvin) or nested
 * lists (Vogon phases with sub-checkpoints).
 *
 * <p>{@code kind} and {@code status} are stringified engine-specific values
 * (e.g. Marvin task-kinds: "plan" / "worker" / "user_input" / "aggregate";
 * statuses: "pending" / "running" / "done" / "failed" / "blocked"). Wire-stable
 * without coupling to engine-internal enums.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("progress")
public class PlanNode {

    private String id;

    private String kind;

    private String title;

    private String status;

    private @Nullable List<PlanNode> children;

    @Builder.Default
    private Map<String, Object> meta = new LinkedHashMap<>();
}
