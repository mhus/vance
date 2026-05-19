package de.mhus.vance.brain.oauth;

/**
 * Raised by {@link OAuthProvider} when the upstream provider rejects a
 * step of the flow — authorize, token exchange, or refresh. Carries
 * the {@code providerId} so the controller surface and the secret
 * resolver can render a precise reconnect message.
 */
public class OAuthFlowException extends RuntimeException {

    private final String providerId;

    public OAuthFlowException(String providerId, String message) {
        super(message);
        this.providerId = providerId;
    }

    public OAuthFlowException(String providerId, String message, Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
    }

    public String providerId() {
        return providerId;
    }
}
