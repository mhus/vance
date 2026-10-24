package de.mhus.vance.api.me;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.home.HomeStatusDto;
import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The signed-in user as the Web-UI needs it — one request instead of a
 * cookie snapshot plus separate status and role calls.
 *
 * <p>Display information only: the server enforces every request on its own,
 * so nothing here grants anything. Each part is read fresh on every call, which
 * is the point — a value someone else changed (a role, a quota) shows up on the
 * next page load instead of on the next login.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("me")
public class MeDto {

    private String username;

    private String tenantId;

    private @Nullable String displayName;

    private TenantRole tenantRole;

    /** The project asked about ({@code ?project=}), if any. */
    private @Nullable String project;

    /**
     * The caller's own membership in {@link #project} — the stored role, not
     * what the tenant role implies. {@code null} when not a member or when no
     * project was asked about.
     */
    private @Nullable ProjectRole projectRole;

    /**
     * The user's UI settings: the {@code webui.*} keys plus the self-service
     * extras ({@code chat.language}, the display timezone). Keys absent when
     * unset.
     */
    @Builder.Default
    private Map<String, String> settings = new LinkedHashMap<>();

    /**
     * Power switch, budget and storage hints — budget at project level when
     * {@link #project} is set and readable. {@code null} when not answerable.
     */
    private @Nullable HomeStatusDto status;
}
