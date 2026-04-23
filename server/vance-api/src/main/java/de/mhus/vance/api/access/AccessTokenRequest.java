package de.mhus.vance.api.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /{tenant}/access/{username}} — carries the user's
 * plaintext password over TLS so the server can verify it against the stored
 * hash.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("access")
public class AccessTokenRequest {

    @NotBlank
    private String password;
}
