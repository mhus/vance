package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request-Body für {@code POST /brain/{tenant}/documents/{id}/notes}.
 *
 * <p>{@code userId} wird absichtlich nicht mitgeschickt — der Server
 * leitet ihn aus dem authentifizierten JWT ab. Damit kann ein Client
 * sich nicht als anderer Nutzer ausgeben.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentNoteCreateRequest {

    /** Frei-Text-Inhalt der Notiz. */
    @NotNull
    @Size(max = 16_384)
    private String text;

    /** Optionale Zeilennummer (1-basiert). */
    private @Nullable Integer line;
}
