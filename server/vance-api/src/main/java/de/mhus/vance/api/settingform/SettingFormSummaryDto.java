package de.mhus.vance.api.settingform;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Listing entry returned by {@code GET /brain/{tenant}/setting-forms}.
 * Carries only the metadata the UI needs to render a card or tab.
 *
 * <p>{@link #title} / {@link #description} are pre-resolved to the
 * tenant's default language. {@link #source} is one of
 * {@code PROJECT} / {@code USER} / {@code VANCE} / {@code RESOURCE}.
 *
 * <p>{@link #clearable} echoes the form's top-level {@code clearable}
 * flag — when {@code false}, the UI should hide the Reset button.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("settingform")
public class SettingFormSummaryDto {

    private String name;

    private String title;

    private String description;

    private @Nullable String icon;

    private @Nullable String category;

    /** {@code PROJECT} | {@code USER} | {@code VANCE} | {@code RESOURCE}. */
    private String source;

    /** Whether the form supports the Reset action (see spec §6.3). */
    private boolean clearable;
}
