package de.mhus.vance.api.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Listing entry returned by {@code GET /brain/{tenant}/templates}.
 * Carries the metadata the Web-UI needs to render a template tile
 * (title, description, icon, tags) plus {@link #nameMode} so the picker
 * can lock/prefill the filename field without loading the full
 * definition.
 *
 * <p>{@link #title} and {@link #description} are pre-resolved against
 * the tenant's default language — the Web-UI sees plain strings.
 *
 * <p>{@link #source} is the innermost cascade layer that produced this
 * template: {@code PROJECT}, {@code VANCE} (tenant-wide) or
 * {@code RESOURCE} (bundled classpath default).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("template")
public class TemplateSummaryDto {

    private String name;

    private String title;

    private String description;

    private @Nullable String icon;

    /** Free-form tags for picker filtering (e.g. {@code note}, {@code app}). */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** {@code free} | {@code fixed}. */
    private String nameMode;

    /** {@code PROJECT} | {@code VANCE} | {@code RESOURCE}. */
    private String source;
}
