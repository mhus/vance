package de.mhus.vance.shared.jwt;

/**
 * Distinguishes the JWT roles in the Vance auth model.
 *
 * <ul>
 *   <li>{@link #ACCESS} — short-lived (24 h), accepted by every
 *       authenticated REST/WS endpoint via {@code AccessFilterBase}.
 *   </li>
 *   <li>{@link #REFRESH} — long-lived (30 d), accepted <em>only</em> by
 *       the token-mint endpoint
 *       ({@code POST /brain/{tenant}/access/{username}}) as an alternate
 *       credential in place of the password. Never grants direct API
 *       access — the filter rejects refresh tokens in {@code Authorization}
 *       headers explicitly.
 *   </li>
 *   <li>{@link #SCRIPT_RUN} — long-lived, scoped to a single script
 *       execution: claims carry a {@code srid} (run id), {@code pid}
 *       (project), and optional {@code sid} (session). Acceptance
 *       additionally requires (a) the request originates from the
 *       loopback interface (script runs as a subprocess in the same
 *       pod as the brain) and (b) the run id is still
 *       {@code RUNNING} in the brain's execution registry. The TTL is
 *       only a safety net — termination of the run revokes the token
 *       immediately via the registry-status check.
 *   </li>
 * </ul>
 *
 * <p>The discriminator is carried in the {@code tt} claim. Tokens that
 * predate this discriminator (no {@code tt} claim) are interpreted as
 * {@link #ACCESS} so the change is backward-compatible with already-issued
 * tokens.
 */
public enum TokenType {
    ACCESS,
    REFRESH,
    SCRIPT_RUN;

    /** JSON value for the {@code tt} claim — lower-case enum name. */
    public String wireValue() {
        return name().toLowerCase();
    }

    /**
     * Reverse of {@link #wireValue()}; falls back to {@link #ACCESS} for
     * unknown / missing values to stay backward-compatible with tokens
     * issued before the {@code tt} claim existed.
     */
    public static TokenType fromWire(String value) {
        if (value == null) return ACCESS;
        for (TokenType t : values()) {
            if (t.wireValue().equalsIgnoreCase(value)) return t;
        }
        return ACCESS;
    }
}
