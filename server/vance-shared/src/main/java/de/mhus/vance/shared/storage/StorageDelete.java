package de.mhus.vance.shared.storage;

import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Soft-delete marker. Pointer to a {@code storageId} + a scheduled
 * {@code deletedAt} time — the cleanup scheduler removes the actual chunks
 * only after that time has passed, so in-flight reads can finish.
 */
@Document(collection = "storage_delete")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class StorageDelete {

    @Id
    private @Nullable String id;

    /** UUID of the {@link StorageData} chunks to remove. */
    @Indexed
    private String storageId = "";

    /** When the chunks become eligible for actual deletion. */
    @Indexed
    private Date deletedAt = new Date();
}
