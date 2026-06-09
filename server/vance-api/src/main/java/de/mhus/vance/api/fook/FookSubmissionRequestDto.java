package de.mhus.vance.api.fook;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/fook/submit} — the
 * UI-driven path (Fook button in the web user-menu, {@code /support}
 * command in the foot CLI). Engine-driven submissions go through
 * the {@code vance_support_request} tool instead, never this
 * endpoint.
 *
 * <p>The tenant in the URL is the reporter's tenant — the tenant
 * receiving the resulting inbox notification — not the
 * {@code _vance} system tenant where tickets are stored.
 *
 * <p>{@code type}, {@code title} and {@code severity} are
 * intentionally absent: Fook derives all three from {@link #getText}
 * during triage, so reporters don't grade their own bugs or have
 * to choose between bug/feature/question/other up front.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("fook")
public class FookSubmissionRequestDto {

    /** Free-form description of the issue or request. Markdown is
     *  fine — Fook reads it as-is. Must not be blank. */
    @NotBlank
    private String text;

    /** Optional project context — if the user filed the report
     *  from a project-bound editor, the UI passes the projectId
     *  through so Lunkwill can correlate later. {@code null} when
     *  the user is on a route with no project (the index page,
     *  most user-menu paths). */
    private @Nullable String projectId;

    /** Optional session context, same rationale as
     *  {@link #projectId}. */
    private @Nullable String sessionId;
}
