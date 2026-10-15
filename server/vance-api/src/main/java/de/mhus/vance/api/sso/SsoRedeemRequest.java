package de.mhus.vance.api.sso;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /brain/_sso/redeem} — a native app's WebView turns the handoff
 * code from the system browser into its own session. The verifier is the one
 * whose S256 challenge the WebView sent when the sign-in started.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("sso")
public class SsoRedeemRequest {

    @NotBlank
    private String code;

    @NotBlank
    private String verifier;
}
