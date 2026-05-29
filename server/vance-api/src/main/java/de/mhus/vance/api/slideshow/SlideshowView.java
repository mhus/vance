package de.mhus.vance.api.slideshow;

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
 * Full state of a slideshow-app folder — what
 * {@code GET /slideshow/show} returns. The slide list is already
 * in playback order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("slideshow")
public class SlideshowView {

    private String folder;

    private String manifestPath;

    private @Nullable String title;

    private @Nullable String description;

    /** Auto-advance interval in seconds. {@code 0} = manual only. */
    private int autoplaySeconds;

    /** Optional viewport hint, e.g. {@code "16:9"}. */
    private @Nullable String aspectRatio;

    @Builder.Default
    private List<SlideView> slides = new ArrayList<>();
}
