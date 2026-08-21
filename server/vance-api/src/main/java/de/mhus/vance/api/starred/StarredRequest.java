package de.mhus.vance.api.starred;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Star a document, or edit an existing entry's authored fields.
 *
 * <p>{@code kind} and {@code type} are deliberately <b>not</b> accepted: they are
 * read from the live document. A caller that could set them could break a "send
 * to" with nothing in the UI saying so.
 *
 * <p>The three optional flags are tri-state: {@code null} means "leave as it is",
 * which is what makes a plain re-star non-destructive to a typed description.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("starred")
public class StarredRequest {

    @NotBlank
    private String project;

    @NotBlank
    private String path;

    /** Overrides the stored label. Omit to keep it (or take the document's on a first star). */
    private @Nullable String title;

    private @Nullable String description;

    private @Nullable Boolean highlight;

    private @Nullable Boolean hidden;
}
