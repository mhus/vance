package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Wire-Repräsentation einer Document-Sticky-Note. Spiegelt
 * {@code DocumentNote} aus vance-shared, übersetzt {@code Instant}s in
 * Epoch-ms (dem gleichen Muster folgend wie {@link DocumentDto}).
 *
 * <p>Embedded als Wert in {@link DocumentDto#getNotes()} (Map keyed by
 * {@link #id}); ausserdem Response der Note-CRUD-Endpoints unter
 * {@code /brain/{tenant}/documents/{id}/notes}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentNoteDto {

    /** UUID, identisch mit dem Map-Key auf {@link DocumentDto#getNotes()}. */
    private String id;

    private String text;

    /** Username des Autors. */
    private String userId;

    private long createdAtMs;
    private long updatedAtMs;

    /** Workflow-Häkchen. */
    private boolean done;

    /**
     * Optionale 1-basierte Zeilennummer im Dokument. {@code null} =
     * unanchored / file-level. Statisch: bewegt sich nicht mit Edits.
     */
    private @Nullable Integer line;
}
