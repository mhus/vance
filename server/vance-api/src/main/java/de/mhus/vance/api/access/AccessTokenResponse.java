package de.mhus.vance.api.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response of {@code POST /{tenant}/access/{username}} — a freshly minted JWT.
 *
 * <p>{@link #getExpiresAtTimestamp()} is Unix-millis (matching the convention used
 * in the WebSocket wire protocol, see {@code specification/websocket-protokoll.md} §1).
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
}
