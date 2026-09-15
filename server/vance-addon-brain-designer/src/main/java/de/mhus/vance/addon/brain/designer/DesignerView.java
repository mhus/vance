package de.mhus.vance.addon.brain.designer;

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
 * Full state of a designer-app folder — what
 * {@code GET /addon/designer/view} returns. The design list is
 * scanned live from the document tree; there is no registry document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("designer")
public class DesignerView {

    /** Normalised app folder (no leading/trailing slash). */
    private String folder;

    /** Path of the {@code _app.yaml} manifest. */
    private String manifestPath;

    private @Nullable String title;

    private @Nullable String description;

    /** The designs found under the folder, sorted by name. */
    @Builder.Default
    private List<DesignInfo> designs = new ArrayList<>();
}
