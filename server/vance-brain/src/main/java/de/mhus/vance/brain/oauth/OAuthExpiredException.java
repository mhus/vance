package de.mhus.vance.brain.oauth;

/**
 * Raised by {@code OAuthTokenRefresher} when a user's tokens cannot be
 * refreshed: missing refresh token, missing provider config, provider
 * rejected the refresh, or provider config has no client secret.
 *
 * <p>Distinct from {@link OAuthFlowException} (which the provider beans
 * throw on individual HTTP failures): this is the higher-level
 * "user must re-authenticate" signal. The Web-UI catches it and shows
 * a "Reconnect Slack" banner; tool calls that depend on the token
 * fail with a clear message instead of a generic 401 from the provider.
 */
public class OAuthExpiredException extends RuntimeException {

    private final String providerId;

    public OAuthExpiredException(String providerId, String message) {
        super(message);
        this.providerId = providerId;
    }

    public OAuthExpiredException(String providerId, String message, Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
    }

    public String providerId() {
        return providerId;
    }
}
