package de.mhus.vance.api.profile;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /brain/{tenant}/profile/email-change} — the new address
 * the caller wants. A confirmation code goes to <em>that</em> address; the
 * address only changes once the code comes back through
 * {@code …/email-change/confirm}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("profile")
public class ProfileEmailChangeRequest {

    @NotBlank
    @Email
    @Size(max = 254)
    private String email = "";
}
