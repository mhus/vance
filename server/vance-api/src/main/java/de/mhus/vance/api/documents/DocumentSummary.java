package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.common.AccentColor;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Lightweight document representation used in {@link DocumentListResponse}.
 * Excludes the inline content — fetch {@code GET /brain/{tenant}/documents/{id}}
 * to load that.
 *
 * <p>{@link #inline} indicates whether the document is small enough to be
 * stored on the record itself (and therefore editable through
 * {@link DocumentUpdateRequest#getInlineText()}). Documents backed by the
 * {@code StorageService} have {@code inline = false} and need a separate
 * upload flow that the v1 Web-UI does not expose.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentSummary {

    private String id;

    private String projectId;

    /** Virtual path inside the project, e.g. {@code "notes/thesis/ch1.md"}. */
    private String path;

    /** File-name portion of {@link #path}. */
    private String name;

    private @Nullable String title;

    /** Accent color from the restricted 12-value palette; {@code null} means no color set. */
    private @Nullable AccentColor color;

    private @Nullable String mimeType;

    private long size;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private @Nullable Long createdAtMs;

    /**
     * Wall-clock at which the document was last meaningfully changed —
     * a content write, a metadata edit (title, tags, color, notes,
     * path/rename), or a lock-state change. Drives the "zuletzt
     * geändert" column in the UI and the mtime of files in the Liver
     * backup tool ({@code planning/user-backup.md} §3.1). Bookkeeping
     * writes (auto-summary, RAG index, archive pointer updates) do not
     * bump this field.
     *
     * <p>{@code null} on rows that pre-date the
     * {@code Migrator_2026_10_21_001_DocumentUpdatedAt} backfill and
     * have not been written to since.
     */
    private @Nullable Long updatedAtMs;

    private @Nullable String createdBy;

    /** Legacy client-side cache flag — server no longer sets this. */
    @Deprecated
    private boolean inline;

    /**
     * {@code kind:} value parsed from markdown front matter, mirrored on
     * the document record so the list view can render a badge / filter
     * by kind without loading the body. {@code null} for non-markdown
     * documents and markdown documents without a front matter.
     */
    private @Nullable String kind;
}
