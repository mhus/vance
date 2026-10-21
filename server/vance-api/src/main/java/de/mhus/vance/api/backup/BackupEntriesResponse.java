package de.mhus.vance.api.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Response for {@code POST /brain/{tenant}/backup/projects/{project}/entries}.
 *
 * <p>{@code nextCursor} is the {@code _id} of the last entry in this page —
 * pass it back as the {@code cursor} on the next call. {@code null} means
 * the page is the last one; the client stops calling.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("backup")
public class BackupEntriesResponse {

    private List<BackupEntryDto> entries;

    private @Nullable String nextCursor;

    /**
     * Approximate size of the page in bytes (logical content size summed
     * across entries). Useful for the client's progress bar without forcing
     * a second stats call.
     */
    private long bytesTotal;
}
