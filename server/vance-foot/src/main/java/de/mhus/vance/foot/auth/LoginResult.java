package de.mhus.vance.foot.auth;

import de.mhus.vance.api.access.AccessTokenResponse;
import java.nio.file.Path;

/**
 * Outcome of a successful {@code /login}: the directory the credentials
 * were written to (for the git-ignore check and user feedback), the minted
 * token, and the binding that was persisted + applied to the running config.
 */
public record LoginResult(
        Path dir,
        AccessTokenResponse token,
        ProjectBinding binding) {
}
