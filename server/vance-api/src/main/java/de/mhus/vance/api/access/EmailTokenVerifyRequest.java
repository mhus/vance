package de.mhus.vance.api.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /brain/{tenant}/access/{username}/email-token/verify}: the
 * code from the mail, plus the handle from the start answer. The response is an
 * {@link AccessTokenResponse}, shaped by the same two flags as the password login.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("access")
public class EmailTokenVerifyRequest {

    @NotBlank
    private String transactionId = "";

    /** The twelve digits; spaces between the groups are fine. */
    @NotBlank
    private String token = "";

    private boolean requestRefreshToken;

    private boolean requestCookies;
}
