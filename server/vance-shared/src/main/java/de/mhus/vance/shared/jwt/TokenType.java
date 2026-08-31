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
 *   <li>{@link #INTEGRATION} — long-lived credential for an external
 *       integration (browser extension, script, webhook consumer). Two
 *       claims narrow it: {@code scp} names a scope profile — the set of
 *       REST surfaces it may touch — and {@code pid} pins it to one
 *       project. Both are <em>restrictions</em>: the token can never do
 *       more than the account behind it, only less.
 *
 *       <p>The profile is carried as a <em>name</em>, not as a path list,
 *       precisely because these tokens outlive URL shapes. A path list
 *       baked into a year-old token is a permission decision frozen at
 *       mint time; a name is resolved against the running code on every
 *       request, so renaming an endpoint moves its profile with it.
 *
 *       <p>Like {@link #SCRIPT_RUN} the TTL is only a safety net —
 *       revocation is the {@code jti} registry row, checked per request.
 *       That check is what makes a long-lived token acceptable at all.
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
    SCRIPT_RUN,
    INTEGRATION;

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
