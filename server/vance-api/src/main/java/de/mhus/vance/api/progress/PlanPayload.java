package de.mhus.vance.api.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot of a plan-driven engine's current execution tree. Emitted on
 * every plan mutation (node added, status changed, node finished). The
 * full tree flies through — no diff protocol in v1.
 *
 * <p>The plan itself is persisted at the engine document; this payload is
 * just the live-push hint that the snapshot has changed. Late-joining
 * clients fetch the current state via REST.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("progress")
public class PlanPayload {

    private PlanNode rootNode;
}
