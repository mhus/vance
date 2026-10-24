package de.mhus.vance.api.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the JS-readable {@code vance_data} cookie issued by
 * {@code POST /brain/{tenant}/access/{username}} when the request
 * sets {@code requestCookies=true}.
 *
 * <p>Carries authentication facts only — who is signed in, in which
 * tenant, and until when. Anything that can change while the cookie lives
 * (display name, settings, roles, quotas) comes from
 * {@code GET /brain/{tenant}/me}. Contains no credential — the access and
 * refresh tokens live in separate {@code HttpOnly} cookies that JavaScript
 * cannot reach.
 *
 * <p>{@link #accessExpiresAtTimestamp} and
 * {@link #refreshExpiresAtTimestamp} let the SPA schedule a silent
 * re-mint before either cookie expires, without ever touching the
 * tokens themselves.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("access")
public class WebUiSessionData {

    private String username;

    private String tenantId;

    /** Unix-millis access-token / access-cookie expiry. */
    private long accessExpiresAtTimestamp;

    /** Unix-millis refresh-token / refresh-cookie expiry — null when no refresh was issued. */
    private @Nullable Long refreshExpiresAtTimestamp;
}
