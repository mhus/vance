package de.mhus.vance.api.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.teams.TeamSummary;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Aggregated profile-page payload. Returned by
 * {@code GET /brain/{tenant}/profile} and refreshed after every
 * profile-update call so the UI never needs to compose the parts
 * itself.
 *
 * <p>Carries only data the user is allowed to view about themselves —
 * cross-user fields (status, password) are deliberately omitted.
 * Editor-side, the Web-UI maps the {@code webUiSettings} keys (with
 * {@code webui.} prefix) directly onto its preference inputs
 * (language, theme, ...).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("profile")
public class ProfileDto {

    private String tenantId;

    private String name;

    private @Nullable String title;

    private @Nullable String email;

    /** Teams the user is a member of in this tenant. */
    private List<TeamSummary> teams;

    /**
     * All {@code webui.*} settings stored on the per-user
     * {@code _user_<login>} project, keys preserved with their
     * {@code webui.} prefix.
     */
    private Map<String, String> webUiSettings;
}
