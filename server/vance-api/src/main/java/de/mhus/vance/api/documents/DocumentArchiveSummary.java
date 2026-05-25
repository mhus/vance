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
 * Lightweight description of one archived document version. Used by
 * {@code GET /brain/{tenant}/documents/{id}/archives} so the version list in
 * the editor can render without pulling each archive's body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentArchiveSummary {

    private String id;

    private String lineageId;

    private String path;

    private String name;

    private @Nullable String title;

    private @Nullable String mimeType;

    private long size;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** Whether the archived version was inline-stored (vs. storage-backed). */
    private boolean inline;

    private @Nullable String kind;

    private @Nullable String createdBy;

    /** Epoch ms at which this version was archived — used as the version label. */
    private long archivedAtMs;
}
