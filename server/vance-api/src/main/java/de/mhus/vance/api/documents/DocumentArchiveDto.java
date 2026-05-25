package de.mhus.vance.api.documents;

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
 * Full archived document representation returned by
 * {@code GET /brain/{tenant}/documents/{id}/archives/{archiveId}}.
 *
 * <p>{@link #inlineText} carries the snapshot body for inline-stored
 * versions; storage-backed versions have {@code inlineText == null} and the
 * client uses the {@code .../content} endpoint for download / preview.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentArchiveDto {

    private String id;

    private String lineageId;

    private String path;

    private String name;

    private @Nullable String title;

    private @Nullable String mimeType;

    private long size;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private boolean inline;

    /** Snapshot text content — present iff {@link #inline} is {@code true}. */
    private @Nullable String inlineText;

    private @Nullable String kind;

    private @Nullable String createdBy;

    private long archivedAtMs;
}
