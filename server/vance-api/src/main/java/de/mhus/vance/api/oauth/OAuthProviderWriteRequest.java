package de.mhus.vance.api.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code PUT /brain/{tenant}/admin/oauth/{providerId}} —
 * upserts the provider's YAML config and, when {@link #clientSecret}
 * is non-null, the tenant-PASSWORD setting that holds the secret.
 *
 * <p>{@code clientSecret} is intentionally <i>optional</i>: a YAML-only
 * edit (URL, scopes, …) sends {@code yaml} alone, leaving the stored
 * secret untouched. Setting a new secret sends a non-null
 * {@code clientSecret}; rotating consists of one PUT with both fields.
 * Empty string is treated as "explicitly remove" — the secret-setting
 * is deleted, mirroring how DELETE removes the whole provider.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("oauth")
public class OAuthProviderWriteRequest {

    /** Verbatim YAML body. Required. */
    @NotBlank
    private String yaml = "";

    /** New client secret to store ({@code null} = don't touch existing). */
    private @Nullable String clientSecret;
}
