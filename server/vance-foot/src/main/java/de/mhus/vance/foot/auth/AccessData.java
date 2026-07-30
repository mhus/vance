package de.mhus.vance.foot.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * Contents of {@code .vancetope/access.yaml} — the per-user credential cache.
 * Holds the brain-issued access token and (when the login requested one)
 * a longer-lived refresh token used to re-mint without re-prompting.
 *
 * <p>This is a <b>secret</b>: written {@code chmod 600} and kept out of
 * version control (see {@code GitignoreGuard}). Never commit it.
 *
 * <p>Timestamps are Unix-millis, matching {@code AccessTokenResponse}.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessData {

    /** The user this token belongs to — authoritative for the access URL. */
    private @Nullable String username;

    private @Nullable String accessToken;
    private @Nullable Long accessExpiresAt;

    private @Nullable String refreshToken;
    private @Nullable Long refreshExpiresAt;
}
