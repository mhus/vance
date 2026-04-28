package de.mhus.vance.api.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response of {@code POST /{tenant}/refresh} — a re-minted JWT issued in
 * exchange for a still-valid one. Same wire-shape as {@link AccessTokenResponse}
 * but kept as its own type for endpoint symmetry and clearer TypeScript
 * generation.
 *
 * <p>{@link #getExpiresAtTimestamp()} is Unix-millis (matching the convention
 * used in the WebSocket wire protocol, see
 * {@code specification/websocket-protokoll.md} §1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("access")
public class RefreshTokenResponse {

    private String token;

    private long expiresAtTimestamp;
}
