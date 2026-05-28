package de.mhus.vance.api.settingform;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry in the {@code applied} response list of
 * {@code /apply} / {@code /validate} / {@code /reset}. Tells the UI
 * exactly which setting key on which scope was written or deleted —
 * the truthful record of what the form did (or, for {@code /validate},
 * <em>would</em> do).
 *
 * <p>{@link #action} is one of {@code write} / {@code delete} /
 * {@code skip}. {@code skip} appears for {@code password}-typed
 * fields submitted as empty (see spec §6.4) and for entries blocked
 * by a falsy {@code writeIf} that have nothing existing to delete.
 *
 * <p>{@link #settingType} echoes the persisted type for {@code write}
 * actions. {@code null} for {@code delete} / {@code skip}.
 *
 * <p>{@link #valueMasked} is {@code true} for any {@code PASSWORD}
 * write — the UI should display "***" instead of the value.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("settingform")
public class AppliedSettingDto {

    /** Setting key (dot notation). */
    private String key;

    /** Resolved {@code referenceType:referenceId}, e.g. {@code project:my-proj} or {@code project:_tenant}. */
    private String scope;

    /** {@code write} | {@code delete} | {@code skip}. */
    private String action;

    /** {@link de.mhus.vance.api.settings.SettingType} name. {@code null} for delete/skip. */
    private @Nullable String settingType;

    /** Whether the value is a password and was therefore not echoed back. */
    private boolean valueMasked;
}
