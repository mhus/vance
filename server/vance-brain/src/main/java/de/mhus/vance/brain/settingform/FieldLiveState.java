package de.mhus.vance.brain.settingform;

import org.jspecify.annotations.Nullable;

/**
 * Pre-submit snapshot of one direct-mapped field's effective state, as
 * seen by {@code SettingFormService} (which owns the cascade reads) and
 * consumed by {@link SettingFormPlanBuilder} (which owns the plan).
 *
 * <p>Two independent facts, both needed to turn a submitted value into
 * an action:
 *
 * <ul>
 *   <li>{@link #unchanged()} — the submitted value already equals the
 *       effective cascade value, so writing it again would at best be a
 *       no-op and at worst pin an inherited value into the scope being
 *       edited.</li>
 *   <li>{@link #liveReferenceId()} — the reference-id of the cascade
 *       layer that currently holds the effective value, or {@code null}
 *       when no layer does. This is what disambiguates an
 *       <em>empty</em> submission: clearing a value the edited scope
 *       owns means "drop my override", clearing an inherited one means
 *       "be empty here despite the outer layer". See
 *       {@link SettingFormPlanBuilder#buildApplyPlan}.</li>
 * </ul>
 */
public record FieldLiveState(boolean unchanged, @Nullable String liveReferenceId) {

    /** No live value in any layer — nothing submitted can be "unchanged". */
    public static final FieldLiveState ABSENT = new FieldLiveState(false, null);

    /** Whether some cascade layer currently holds an effective value. */
    public boolean hasLiveValue() {
        return liveReferenceId != null;
    }

    /**
     * Whether the effective value is owned by {@code referenceId} — i.e.
     * the scope being edited is the one that holds it, so a DELETE there
     * actually removes something.
     */
    public boolean isOwnedBy(String referenceId) {
        return referenceId.equals(liveReferenceId);
    }
}
