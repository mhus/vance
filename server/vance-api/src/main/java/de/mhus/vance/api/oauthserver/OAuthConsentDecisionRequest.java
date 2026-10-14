package de.mhus.vance.api.oauthserver;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The user's answer on the consent page. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("oauthserver")
public class OAuthConsentDecisionRequest {

    @NotBlank
    private String requestId;

    @NotBlank
    private String consentTicket;

    private boolean approve;
}
