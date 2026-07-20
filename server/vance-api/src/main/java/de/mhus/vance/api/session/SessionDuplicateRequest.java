package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body for the session-duplicate call. All fields optional — an empty
 * body duplicates with the source title carried over verbatim.
 *
 * <p>See {@code specification/public/session-duplicate.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionDuplicateRequest {

    /**
     * Title for the copy. {@code null} / absent carries over the source
     * title unchanged; the caller (Web UI) typically sends a localized
     * "Copy of …" label.
     */
    private @Nullable String title;
}
