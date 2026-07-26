package de.mhus.vance.foot.auth;

import org.jspecify.annotations.Nullable;

/**
 * Resolved inputs for an interactive {@code /login}: where to authenticate
 * ({@code httpBase}/{@code wsBase}/{@code tenant}), who ({@code username}),
 * the optional project to bind, and the password to mint with.
 */
public record LoginRequest(
        String httpBase,
        String wsBase,
        String tenant,
        String username,
        @Nullable String project,
        String password) {
}
