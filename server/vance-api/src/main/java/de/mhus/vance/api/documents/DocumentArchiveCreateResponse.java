package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Response of {@code POST /brain/{tenant}/documents/{id}/archives} — the manual
 * "create version now" action.
 *
 * <p>{@link #created} is {@code true} only when a new version was actually
 * written. When {@code false}, {@link #reason} explains why:
 * <ul>
 *   <li>{@code UNCHANGED} — the current content is byte-identical to the latest
 *       archived version, so no duplicate version was created.</li>
 *   <li>{@code DISABLED} — archiving is turned off for this project (operator
 *       kill-switch or {@code documents.archive.enabled} setting).</li>
 * </ul>
 * {@link #archive} carries the fresh version's summary when {@code created}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentArchiveCreateResponse {

    private boolean created;

    /** {@code CREATED} / {@code UNCHANGED} / {@code DISABLED}. */
    private String reason;

    /** The newly created version — {@code null} unless {@link #created}. */
    private @Nullable DocumentArchiveSummary archive;
}
