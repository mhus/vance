package de.mhus.vance.api.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Response of {@code POST /{tenant}/access/{username}} — a freshly minted JWT.
 *
 * <p>{@link #getExpiresAtTimestamp()} is Unix-millis (matching the convention used
 * in the WebSocket wire protocol, see {@code specification/websocket-protokoll.md} §1).
 *
 * <p>{@link #refreshToken} and {@link #refreshTokenExpiresAtTimestamp}
 * are populated only when the request set {@code requestRefreshToken=true}.
 * Refresh tokens have a much longer lifetime (30 d) and may only be used
 * to re-mint at this same endpoint — they are rejected by the access
 * filter on regular API calls.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("access")
public class AccessTokenResponse {

    private String token;

    private long expiresAtTimestamp;

    private @Nullable String refreshToken;

    private @Nullable Long refreshTokenExpiresAtTimestamp;
}
