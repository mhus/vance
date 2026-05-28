package de.mhus.vance.brain.settingform;

import de.mhus.vance.api.settings.SettingType;
import org.jspecify.annotations.Nullable;

/**
 * One concrete action the Setting-Form apply pipeline would (or did)
 * perform against the {@code settings} collection. Carries everything
 * {@link de.mhus.vance.shared.settings.SettingService} needs:
 * reference (type + id), key, action verb, and — for writes — the
 * already-coerced plaintext value plus the persisted
 * {@link SettingType}.
 *
 * <p>The action verb mirrors the wire-format {@code AppliedSettingDto}:
 * {@code WRITE} (upsert), {@code DELETE} (drop the row if present),
 * {@code SKIP} (no-op, kept in the plan for audit/UX). A {@code SKIP}
 * is emitted for password fields submitted as empty (spec §6.4) and
 * for {@code writeIf=false} entries that have nothing to delete.
 *
 * <p>{@link #wireScope} is the human-readable scope label
 * ({@code project}, {@code user}, {@code tenant}) preserved from the
 * form definition — used to build the {@code AppliedSettingDto.scope}
 * field. The persistence layer talks
 * {@code referenceType} + {@code referenceId} instead.
 */
public record PlannedSettingAction(
        String key,
        String wireScope,
        String referenceType,
        String referenceId,
        Action action,
        @Nullable String value,
        @Nullable SettingType settingType,
        boolean masked,
        String sourceLabel) {

    public enum Action {
        WRITE,
        DELETE,
        SKIP
    }
}
