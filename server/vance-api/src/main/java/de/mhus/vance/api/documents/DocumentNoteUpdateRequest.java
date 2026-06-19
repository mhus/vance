package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request-Body für {@code PUT /brain/{tenant}/documents/{id}/notes/{noteId}}.
 *
 * <p>{@code null}-Werte bedeuten "Feld unverändert lassen". Den userId
 * kann der Client nie ändern — er wird beim Anlegen gesetzt und bleibt.
 *
 * <p>{@code line} hat eine Sentinel-Konvention: {@code Integer.MIN_VALUE}
 * heißt "Line-Anker explizit entfernen" (Note wird wieder unanchored).
 * Praktisch selten gebraucht; der häufigere Pfad ist "leave alone"
 * (null) oder "set to new line" (positiver Wert).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentNoteUpdateRequest {

    @Size(max = 16_384)
    private @Nullable String text;

    private @Nullable Boolean done;

    /** {@code Integer.MIN_VALUE} = unset; positiver Wert = neue Zeile. */
    private @Nullable Integer line;
}
