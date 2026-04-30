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
 * Per-inherit ownership inside a {@link KitManifestDto}. Lists every
 * artefact path/key/name whose currently-installed version was
 * contributed by this inherit layer (after last-writer-wins resolution
 * across the whole inherit chain). A path appears in exactly one
 * layer's lists — either {@code documents/settings/tools} of the
 * top-layer or one of the {@code inheritArtefacts} entries — never in
 * both.
 *
 * <p>The data is what drives prune-on-update: when an inherit removes
 * a file in its new version, the old manifest tells us we owned it
 * and the new build tree tells us nobody does anymore — so it's safe
 * to delete.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class InheritArtefactsDto {

    /** Inherit kit name (matches a {@link KitManifestDto#getResolvedInherits()} entry). */
    private String name;

    /** Document paths (relative to the project document root) owned by this inherit. */
    @Builder.Default
    private List<String> documents = new ArrayList<>();

    /** Setting keys owned by this inherit. */
    @Builder.Default
    private List<String> settings = new ArrayList<>();

    /** Server-tool names owned by this inherit. */
    @Builder.Default
    private List<String> tools = new ArrayList<>();
}
