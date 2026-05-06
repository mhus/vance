package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@code plan-proposed} server notification. Sent
 * whenever Arthur emits a {@code PROPOSE_PLAN} action.
 *
 * <p>Sent <em>in addition</em> to the regular ChatMessage stream so
 * UIs can highlight the plan-text visually (box, color, "Plan vN"
 * header). The full plan markdown lives in the corresponding
 * ChatMessage; this notification only carries metadata.
 *
 * <p>{@link #planVersion} counts how many times PROPOSE_PLAN has
 * been emitted in this process — useful for "Plan v2", "Plan v3"
 * UI labels when the user iterates on edits.
 *
 * <p>See {@code readme/arthur-plan-mode.md} §9.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class PlanProposedNotification {

    private String processId = "";

    private String processName = "";

    private String sessionId = "";

    private @Nullable String summary;

    /** 1 = first plan, 2 = first edit, etc. */
    private int planVersion = 1;
}
