package de.mhus.vance.brain.tools.budget;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.tools.budget.*} — the tool-surface budget.
 *
 * <p>The limit itself is not here: it belongs to the endpoint and lives
 * as {@code maxTools} in the model catalog. This class only carries how
 * much of it we hold back and the deployment-level priority overrides.
 */
@Data
@ConfigurationProperties(prefix = "vance.tools.budget")
public class ToolBudgetProperties {

    /**
     * Master switch. Off means "send whatever the classification
     * produced" — the pre-budget behaviour, including the provider 400
     * when it overflows. Kept as an escape hatch for debugging, not as a
     * supported mode.
     */
    private boolean enabled = true;

    /**
     * Slots held back for tools the model activates mid-turn through
     * {@code tool_description}. Without headroom a surface filled to
     * exactly {@code maxTools} would blow the limit on the first
     * activation — the same provider 400, only later and harder to read.
     */
    private int activationHeadroom = 8;

    /**
     * Slots held back for schemas appended outside the classification.
     * Today that is the engine's own action tool (Arthur/Eddie append
     * {@code <engine>_action} after {@code primaryAsLc4j()}); one is
     * enough, the setting exists so an engine that appends more can say so.
     */
    private int externalReserve = 1;

    /**
     * Families to treat as "important" regardless of the derived order
     * (see {@link ToolFamily}). Short list by intent — a long one is a
     * sign the derivation is wrong and should be fixed instead.
     */
    private List<String> keepFamilies = List.of();

    /** Families to give up first, before anything else. */
    private List<String> dropFirstFamilies = List.of();

    /**
     * Cap on how many activated deferred tools may occupy the surface at
     * once. Independent of the budget: a long-running process would
     * otherwise accumulate activations until they alone fill the
     * manifest. {@code 0} disables the cap.
     */
    private int maxActivatedTools = 40;
}
