package de.mhus.vance.api.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
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

    private List<String> scopeProfiles;

    /** Human labels of the profiles, so a list needs no second lookup. */
    private List<String> scopeProfileLabels;

    private @Nullable String projectId;

    private String label;

    private @Nullable Long createdAtTimestamp;

    private @Nullable Long expiresAtTimestamp;

    private @Nullable Long lastUsedAtTimestamp;

    /**
     * IP from which the token last authenticated. {@code null} when the token
     * has never been used. Surfaced to the owner so a token used from an
     * unexpected IP stands out — that's the second-place visibility the
     * sliding-lifetime threat model relies on.
     */
    private @Nullable String lastUsedIp;

    /**
     * The idle-days ceiling in effect when this token was minted. Null on
     * fixed-end tokens. The Face UI uses it to surface "renews on every
     * backup run" without a separate profile lookup.
     */
    private @Nullable Integer idleDays;

    private @Nullable Long revokedAtTimestamp;
}
