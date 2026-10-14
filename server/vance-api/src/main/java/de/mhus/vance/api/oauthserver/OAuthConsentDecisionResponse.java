package de.mhus.vance.api.oauthserver;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Where the browser goes after the decision — the client's redirect URI with
 * either {@code code} or {@code error}. The page navigates there itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("oauthserver")
public class OAuthConsentDecisionResponse {

    private String redirectUrl;
}
