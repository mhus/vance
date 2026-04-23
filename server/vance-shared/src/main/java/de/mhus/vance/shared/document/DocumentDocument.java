package de.mhus.vance.shared.document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent document record. Scoped to a tenant + project; addressed by
 * {@code path} inside the project.
 *
 * <p>Exactly one of {@link #inlineText} or {@link #storageId} is populated:
 * small text documents stay inline; everything else lives in
 * {@code StorageService}. The {@link #size} field always reflects the logical
 * (uncompressed, un-encoded) byte size of the content.
 */
@Document(collection = "documents")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_project_path_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'path': 1 }",
                unique = true),
        @CompoundIndex(
                name = "tenant_project_status_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'status': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    /** Owning project ({@code ProjectDocument.name}). */
    private String projectId = "";

    /** Virtual path inside the project, e.g. {@code "notes/thesis/ch1.md"}. */
    private String path = "";

    /** File-name portion of {@link #path} — derived on create, kept for indexed lookups. */
    private String name = "";

    /** Human-readable title. Nullable; UI falls back to {@link #name}. */
    private @Nullable String title;

    /** Orthogonal tag set — the second organizing axis next to {@link #path}. */
    @Indexed
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** e.g. {@code "text/markdown"}, {@code "application/pdf"}. */
    private @Nullable String mimeType;

    /** Logical content size in bytes. */
    private long size;

    /** Storage id ({@code StorageService}) when content is kept out-of-band. */
    private @Nullable String storageId;

    /** Content for small text documents, held directly on the record. */
    private @Nullable String inlineText;

    /** Username of the creator ({@code UserDocument.name}). */
    private @Nullable String createdBy;

    @Builder.Default
    private DocumentStatus status = DocumentStatus.ACTIVE;

    @CreatedDate
    private @Nullable Instant createdAt;
}
