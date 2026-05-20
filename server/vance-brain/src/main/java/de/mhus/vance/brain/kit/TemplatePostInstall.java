package de.mhus.vance.brain.kit;

import org.jspecify.annotations.Nullable;

/**
 * Hook the Web-UI / chat-agent shows after a successful template apply.
 * Two kinds today:
 *
 * <ul>
 *   <li>{@code OAUTH_CONNECT} — the user must click "Connect" in
 *       Connected Accounts to finish the setup (token exchange).</li>
 *   <li>{@code NOTICE} — pure informational message, no follow-up
 *       action wired. Used by templates that need to prompt for an
 *       out-of-band step (e.g. "now start the daemon").</li>
 * </ul>
 *
 * <p>Templates that need no follow-up step omit the {@code postInstall}
 * block entirely; the apply result then carries {@code null}.
 *
 * @param kind     What kind of follow-up: {@code oauth-connect} | {@code notice}.
 * @param provider Provider id for {@code OAUTH_CONNECT} — must match an
 *                 existing OAuth provider in the tenant. Ignored for {@code NOTICE}.
 * @param message  Human-readable instruction line.
 */
public record TemplatePostInstall(
        Kind kind,
        @Nullable String provider,
        @Nullable String message) {

    public enum Kind {
        OAUTH_CONNECT,
        /**
         * Informational follow-up — no UI action wired. The Web-UI shows
         * {@link TemplatePostInstall#message} in the apply-result modal;
         * the chat-agent treats it as the answer to relay to the user.
         */
        NOTICE;

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
                                + "' — supported: oauth-connect, notice");
            }
        }
    }
}
