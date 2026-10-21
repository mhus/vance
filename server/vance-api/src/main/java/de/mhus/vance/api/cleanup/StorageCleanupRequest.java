package de.mhus.vance.api.cleanup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/cleanup/archives}. The depth knob the
 * admin sets in the dialog before confirming: how many versions of each
 * document to keep after the run. The server clamps to a sane range so a
 * typo (e.g. {@code -1} or {@code 9999999}) cannot wipe every version.
 *
 * <p>{@code keepLatest == 0} keeps nothing — every archive entry of every
 * lineage is deleted, leaving only the live documents. That takes an
 * explicit {@code 0}: a missing field (empty body, misspelled key) is
 * {@code null} and means the default, never "keep nothing". The UI shows a
 * separate confirmation for {@code 0}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("cleanup")
public class StorageCleanupRequest {

    /**
     * Versions to keep per lineage, newest first. {@code null} and negative
     * values become {@code DEFAULT_KEEP_LATEST}; values above
     * {@code MAX_KEEP_LATEST} are capped to it.
     */
    private @Nullable Integer keepLatest;
}
