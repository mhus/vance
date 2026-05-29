package de.mhus.vance.api.slideshow;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One slide on a slideshow. {@code documentId} is the underlying
 * document's id — the UI builds the image URL via the standard
 * document content endpoint. {@code width}/{@code height} are
 * probed pixel dimensions; {@code null} when probing failed
 * (e.g. for {@code image/svg+xml}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("slideshow")
public class SlideView {

    private String documentId;

    private String path;

    private String relativePath;

    private @Nullable String caption;

    private @Nullable Integer width;

    private @Nullable Integer height;

    private long sizeBytes;

    private String mimeType;
}
