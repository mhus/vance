package de.mhus.vance.api.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
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
 * <p>Carries identity + UI-relevant per-user settings so the SPA can
 * render the post-login state without a follow-up REST call. Contains
 * no credential — the access and refresh tokens live in separate
 * {@code HttpOnly} cookies that JavaScript cannot reach.
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

    private @Nullable String displayName;

    /** Unix-millis access-token / access-cookie expiry. */
    private long accessExpiresAtTimestamp;

    /** Unix-millis refresh-token / refresh-cookie expiry — null when no refresh was issued. */
    private @Nullable Long refreshExpiresAtTimestamp;

    /**
     * Per-user {@code webui.*} settings, keys without the prefix.
     * Empty (not null) when the request did not set
     * {@code includeWebUiSettings=true} or the user has no overrides.
     */
    private Map<String, String> webUiSettings;
}
