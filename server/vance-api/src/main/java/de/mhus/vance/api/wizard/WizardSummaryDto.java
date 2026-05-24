package de.mhus.vance.api.wizard;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Listing entry returned by {@code GET /brain/{tenant}/wizards}.
 * Carries the metadata the Web-UI needs to render a wizard tile
 * (title, description, icon, category) without exposing the full
 * field schema or the Pebble {@code promptTemplate}.
 *
 * <p>{@link #title} and {@link #description} are pre-resolved against
 * the tenant's default language — the Web-UI sees plain strings, not
 * the localized map.
 *
 * <p>{@link #source} is the innermost cascade layer that produced
 * this wizard: one of {@code PROJECT}, {@code USER},
 * {@code VANCE} (tenant-wide {@code _vance/}), or {@code RESOURCE}
 * (bundled classpath default). Wizards show up exactly once even when
 * shadowed by inner layers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("wizard")
public class WizardSummaryDto {

    private String name;

    private String title;

    private String description;

    private @Nullable String icon;

    private @Nullable String category;

    /** {@code PROJECT} | {@code USER} | {@code VANCE} | {@code RESOURCE}. */
    private String source;
}
