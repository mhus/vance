package de.mhus.vance.api.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One integration token as the owner sees it.
 *
 * <p>{@link #token} is populated <b>only</b> in the response that mints it and
 * is never stored — the server keeps no copy it could show again. A list
 * therefore always has it {@code null}, and that is not an omission to fix
 * later: a surface that can re-display a credential is a second place it can
 * leak from.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("access")
public class IntegrationTokenDto {

    /** Registry key — also what a revoke call addresses. */
    private String tokenId;

    /** The signed JWT. Present exactly once, in the mint response. */
    private @Nullable String token;

    private String scopeProfile;

    /** Human label of the profile, so a list needs no second lookup. */
    private @Nullable String scopeProfileLabel;

    private @Nullable String projectId;

    private String label;

    private @Nullable Long createdAtTimestamp;

    private @Nullable Long expiresAtTimestamp;

    private @Nullable Long lastUsedAtTimestamp;

    private @Nullable Long revokedAtTimestamp;
}
