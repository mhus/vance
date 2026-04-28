package de.mhus.vance.api.insights;

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
 * Read-only view of one Marvin task-tree node for the insights
 * inspector. {@link #parentId} ties siblings together; the client
 * sorts by {@link #position} and renders a tree.
 *
 * <p>{@link #taskKind} and {@link #status} are stringified marvin
 * enum values — wire-stable without pulling
 * {@code de.mhus.vance.api.marvin.*} into the TS-generated bundle.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class MarvinNodeInsightsDto {

    private String id;

    private @Nullable String parentId;

    private int position;

    private String goal;

    private String taskKind;

    private String status;

    @Builder.Default
    private Map<String, Object> taskSpec = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> artifacts = new LinkedHashMap<>();

    private @Nullable String failureReason;

    private @Nullable String spawnedProcessId;

    private @Nullable String inboxItemId;

    private @Nullable Instant createdAt;

    private @Nullable Instant startedAt;

    private @Nullable Instant completedAt;
}
