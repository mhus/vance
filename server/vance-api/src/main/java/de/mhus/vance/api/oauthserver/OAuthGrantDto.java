package de.mhus.vance.api.oauthserver;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** One OAuth grant ("connected app") as its owner sees it. Never carries a token. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("oauthserver")
public class OAuthGrantDto {

    private String grantId;

    private String clientId;

    private String clientName;

    private String scope;

    private @Nullable String resource;

    private @Nullable String projectId;

    /** Live: not revoked and not past its end. */
    private boolean active;

    private @Nullable Long createdAtTimestamp;

    private @Nullable Long lastUsedAtTimestamp;

    private @Nullable Long expiresAtTimestamp;

    private @Nullable Long revokedAtTimestamp;

    private @Nullable String revokeReason;
}
