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
     * Extra slots held back on top of {@link #externalReserve}.
     *
     * <p>Default 0, and that is not an oversight: every path that grows
     * the manifest after the classification runs through the triage again
     * — a {@code tool_description} activation because {@code tools()}
     * re-classifies, and {@code withAdditional} (skill tools) because it
     * re-fits explicitly. A standing cushion would therefore only park
     * capability in the discovery block for nothing.
     *
     * <p>Raise it when a deployment adds a manifest path that the budget
     * does not see — the symptom would be a provider 400 despite a
     * configured {@code maxTools}.
     */
    private int activationHeadroom = 0;

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
