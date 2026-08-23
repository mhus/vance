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
     * How many activated deferred tools may hold a top-class slot when the
     * surface has to be cut. Past this count the oldest activations lose
     * their {@code TIER_ACTIVATED} standing and compete in the lowest
     * class instead, so a long-running process cannot fill the whole
     * manifest with what it once looked at. {@code 0} disables it.
     *
     * <p>This is a <em>ranking</em> cap, not a hard one: it is read inside
     * {@link ToolTriage#apply} and therefore only matters when a limit is
     * known and the surface exceeds it. Where no endpoint states a
     * {@code maxTools} (Anthropic today), nothing is cut at all and this
     * value is never consulted — there the bound on accumulated
     * activations is the activation TTL, not this number.
     */
    private int maxActivatedTools = 40;
}
