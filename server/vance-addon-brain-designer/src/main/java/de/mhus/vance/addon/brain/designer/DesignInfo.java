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
 * One design inside a designer app — a subfolder of plain web files
 * with an {@code index.html} entry. {@code files} are the document
 * paths relative to the design folder, sorted, entry file included.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("designer")
public class DesignInfo {

    /** Design folder name — the single path segment under the app folder. */
    private String name;

    /** Display name from {@code design.yaml}, else the folder name. */
    private @Nullable String title;

    /** One-line description from {@code design.yaml}. */
    private @Nullable String description;

    /** File paths relative to the design folder, sorted. */
    @Builder.Default
    private List<String> files = new ArrayList<>();

    /** File count (files minus the metadata file, for a quick card metric). */
    private int fileCount;
}
