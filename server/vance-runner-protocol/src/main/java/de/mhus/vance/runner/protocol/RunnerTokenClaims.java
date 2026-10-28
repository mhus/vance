package de.mhus.vance.runner.protocol;

/**
 * The claim names and values of a runner-bound JWT. The names follow the brain's
 * other tokens ({@code VanceJwtClaims}); the brain mints with these constants and the
 * runner verifies against them, so the two cannot drift apart.
 */
public final class RunnerTokenClaims {

    /** Token type claim, as in every Vance JWT. */
    public static final String CLAIM_TYPE = "tt";

    public static final String CLAIM_TENANT_ID = "tid";
    public static final String CLAIM_PROJECT_ID = "pid";
    public static final String CLAIM_ID = "jti";
    public static final String CLAIM_AUDIENCE = "aud";
    public static final String CLAIM_EXPIRES = "exp";

    /** Optional: the RootDir the call is confined to. */
    public static final String CLAIM_ROOT = "rdr";

    /** The value of {@link #CLAIM_TYPE} — never accepted by the brain as a login. */
    public static final String TYPE = "runner";

    private static final String AUDIENCE_PREFIX = "runner:";

    private RunnerTokenClaims() {}

    /** The audience a token for runner {@code runnerId} carries — one runner, no other. */
    public static String audience(String runnerId) {
        return AUDIENCE_PREFIX + runnerId;
    }
}
