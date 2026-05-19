package de.mhus.vance.brain.kit;

import org.jspecify.annotations.Nullable;

/**
 * Hook the Web-UI / chat-agent shows after a successful template apply.
 * Today the only kind is {@code OAUTH_CONNECT} — the user must click
 * "Connect" in Connected Accounts to finish the setup. New kinds get
 * added on demand (e.g. an "open this URL"-link, "run this once"-cmd).
 *
 * <p>Templates that need no follow-up step omit the {@code postInstall}
 * block entirely; the apply result then carries {@code null}.
 *
 * @param kind     What kind of follow-up. Today: {@code oauth-connect}.
 * @param provider Provider id for {@code OAUTH_CONNECT} — must match an
 *                 existing OAuth provider in the tenant.
 * @param message  Human-readable instruction line.
 */
public record TemplatePostInstall(
        Kind kind,
        @Nullable String provider,
        @Nullable String message) {

    public enum Kind {
        OAUTH_CONNECT;

        public static Kind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "template.postInstall: 'kind' is required");
            }
            String norm = raw.trim().toLowerCase().replace('-', '_');
            try {
                return Kind.valueOf(norm.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "template.postInstall: unknown kind '" + raw
                                + "' — supported: oauth-connect");
            }
        }
    }
}
