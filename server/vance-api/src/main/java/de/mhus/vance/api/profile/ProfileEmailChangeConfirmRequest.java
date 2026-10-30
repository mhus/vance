package de.mhus.vance.api.profile;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /brain/{tenant}/profile/email-change/confirm}: the handle
 * from the start answer and the code mailed to the new address. The response is
 * the updated {@link ProfileDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("profile")
public class ProfileEmailChangeConfirmRequest {

    @NotBlank
    private String transactionId = "";

    /** The twelve digits; spaces between the groups are fine. */
    @NotBlank
    private String token = "";
}
