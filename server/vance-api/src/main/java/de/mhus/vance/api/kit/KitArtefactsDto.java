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
 * Everything one installed kit owns, split by namespace.
 *
 * <p>Replaces the manifest's four parallel lists
 * ({@code documents}/{@code settings}/{@code tools} plus
 * {@code inheritArtefacts}): ownership per inherit layer now rides on
 * each entry as {@link KitArtefactDto#getLayer()}, which carries the same
 * information with one structure instead of two.
 *
 * <p>There is no {@code tools} list — server-tool configs are ordinary
 * documents under {@code server-tools/<name>.yaml} and are tracked as
 * such.
 *
 * <p>Invariant: within one record, every id appears exactly once.
 * Across records it may repeat — two kits may ship the same path, and
 * which one is live follows the layer order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitArtefactsDto {

    @Builder.Default
    private List<KitArtefactDto> documents = new ArrayList<>();

    @Builder.Default
    private List<KitArtefactDto> settings = new ArrayList<>();
}
