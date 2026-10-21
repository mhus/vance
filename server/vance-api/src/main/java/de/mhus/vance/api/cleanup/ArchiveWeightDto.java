package de.mhus.vance.api.cleanup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the breakdown's {@code topArchives} list — one lineage,
 * represented by its largest archive version, with the number of versions
 * the lineage has.
 *
 * <p>The UI uses {@link #size} as the sort key (biggest first) and
 * {@link #versionCount} as the hint "this document has been rewritten N
 * times — thinning it is more meaningful here than on a document with one
 * version".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("cleanup")
public class ArchiveWeightDto {

    /** Archive entry id — usable to call {@code DELETE /archives/{id}} one-by-one. */
    private String archiveId = "";

    /** Lineage id shared with the live document and every sibling archive entry. */
    private String lineageId = "";

    /** Path the document had when this version was archived (its "snapshot name"). */
    private String path = "";

    /** Size of this single version in bytes. */
    private long size;

    /** When the version was archived — newest first in the top-N list. */
    private @Nullable Instant archivedAt;

    /** Number of archive entries this lineage currently has (including this one). */
    private long versionCount;
}
