package de.mhus.vance.brain.oauthserver;

/** Where a client's identity comes from. */
public enum OAuthClientKind {
    /** Built-in Vance client (foot CLI) — trusted, may hold the {@code account} scope, no consent page. */
    FIRST_PARTY("first-party"),
    /** Registered through Dynamic Client Registration — everything it says about itself is self-asserted. */
    DCR("dcr"),
    /** {@code client_id} is an HTTPS URL to a Client ID Metadata Document — name vouched for by its domain. */
    CIMD("cimd");

    private final String wire;

    OAuthClientKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
