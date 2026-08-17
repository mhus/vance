package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Content of {@code _vance/config/kit-sources.yaml} in the
 * {@code _tenant} project: where this tenant may get kits from.
 *
 * <p>The document is optional and purely <b>additive</b>. Absent, kits
 * behave exactly as before — every url is loaded by guessing git or
 * folder from its shape, and nothing is verified. It adds sources; it
 * does not take any away.
 *
 * <p>Not to be confused with {@code project-kits.yaml}, which is a
 * curated list of <i>suggestions</i> shown at project creation. That
 * one answers "which kits might I want", this one "where may kits come
 * from at all".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitSourcesDto {

    @Builder.Default
    private List<KitSourceDto> sources = new ArrayList<>();
}
