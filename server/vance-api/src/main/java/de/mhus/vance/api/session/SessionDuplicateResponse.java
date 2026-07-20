package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Result of a session-duplicate call: the business id of the freshly
 * created copy plus its resolved title. The caller (Web UI list menu)
 * uses {@code sessionId} to optionally open the copy and reloads its
 * session list to surface it.
 *
 * <p>See {@code specification/public/session-duplicate.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionDuplicateResponse {

    /** Business id ({@code sess_...}) of the new duplicated session. */
    private String sessionId = "";

    /** Resolved title of the copy — typically the source title with a copy marker. */
    private @Nullable String title;
}
