package de.mhus.vance.toolpack.rest;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Authentication configuration for a REST-API pack. Static credentials
 * only — OAuth flows are reserved for the future client-side variant.
 *
 * <p>YAML form (under {@code parameters.auth}):
 * <pre>
 *   auth:
 *     type: bearer | basic | apiKey | oauth | none
 *     # bearer:
 *     token: "{{secret:my.api.token}}"
 *     # basic:
 *     user: "alice"
 *     password: "{{secret:my.api.password}}"
 *     # apiKey:
 *     headerName: "X-API-Key"
 *     value: "{{secret:my.api.key}}"
 *     # or
 *     queryParamName: "api_key"
 *     # oauth: the host translates it into a bearer token before the
 *     # transport sees it (see {@link Type#OAUTH}).
 * </pre>
 *
 * <p>{@code {{secret:...}}} references are resolved at invoke time
 * via {@link de.mhus.vance.toolpack.core.SecretResolver}.
 */
public record AuthSpec(
        Type type,
        @Nullable String token,
        @Nullable String user,
        @Nullable String password,
        @Nullable String headerName,
        @Nullable String queryParamName,
        @Nullable String value) {

    /**
     * Auth types. {@link #OAUTH} carries no credential of its own — the
     * host that owns the OAuth tokens (the brain) replaces it with a
     * {@link #bearer bearer} spec via {@code McpConfig.withAuth} before a
     * transport is built. A transport that meets an untranslated
     * {@code OAUTH} spec fails loudly instead of calling unauthenticated.
     */
    public enum Type {
        NONE,
        BEARER,
        BASIC,
        API_KEY,
        OAUTH
    }

    public static final AuthSpec NONE = new AuthSpec(Type.NONE, null, null, null, null, null, null);

    /** Marker for {@code type: oauth} — see {@link Type#OAUTH}. */
    public static final AuthSpec OAUTH = new AuthSpec(Type.OAUTH, null, null, null, null, null, null);

    /** Bearer auth with a (possibly templated) token. */
    public static AuthSpec bearer(String token) {
        return new AuthSpec(Type.BEARER, token, null, null, null, null, null);
    }

    /**
     * Parses an {@code auth} sub-map from {@code parameters.auth}.
     * Unknown / missing block → {@link #NONE}.
     */
    public static AuthSpec fromMap(@Nullable Map<String, Object> authBlock) {
        if (authBlock == null || authBlock.isEmpty()) return NONE;
        String typeStr = stringOrNull(authBlock.get("type"));
        Type t = parseType(typeStr);
        return switch (t) {
            case NONE -> NONE;
            case OAUTH -> OAUTH;
            case BEARER ->
                new AuthSpec(Type.BEARER, stringOrNull(authBlock.get("token")), null, null, null, null, null);
            case BASIC ->
                new AuthSpec(
                        Type.BASIC,
                        null,
                        stringOrNull(authBlock.get("user")),
                        stringOrNull(authBlock.get("password")),
                        null,
                        null,
                        null);
            case API_KEY ->
                new AuthSpec(
                        Type.API_KEY,
                        null,
                        null,
                        null,
                        stringOrNull(authBlock.get("headerName")),
                        stringOrNull(authBlock.get("queryParamName")),
                        stringOrNull(authBlock.get("value")));
        };
    }

    private static Type parseType(@Nullable String s) {
        if (s == null) return Type.NONE;
        return switch (s.trim().toLowerCase()) {
            case "", "none" -> Type.NONE;
            case "bearer" -> Type.BEARER;
            case "basic" -> Type.BASIC;
            case "apikey", "api_key", "api-key" -> Type.API_KEY;
            case "oauth" -> Type.OAUTH;
            default ->
                throw new IllegalArgumentException(
                        "Unknown auth.type '" + s + "' — expected: none, bearer, basic, apiKey, oauth");
        };
    }

    private static @Nullable String stringOrNull(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
