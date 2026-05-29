package de.mhus.vance.api.slideshow;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Result of {@code POST /slideshow/rebuild}. Carries the regenerated
 * {@code _index.yaml} path so the UI can refetch and refresh.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("slideshow")
public class SlideshowRebuildResponse {

    private String folder;

    private @Nullable String indexPath;

    private @Nullable String indexMarkdownLink;

    private int slideCount;
}
