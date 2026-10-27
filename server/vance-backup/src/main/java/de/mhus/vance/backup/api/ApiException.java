package de.mhus.vance.backup.api;

/**
 * Thrown by {@link BackupApiClient} when the brain responds with a
 * status that prevents a backup run from continuing. The CLI maps
 * this to exit code 2 ({@code planning/user-backup.md} §4.2: auth)
 * — the umbrella covers 401/403/410/anything that says the token
 * is dead or the policy is closed.
 *
 * <p>The HTTP status code and the trimmed response body (if any) are
 * preserved so the operator can see *why* the server rejected the
 * call. The connection string is never echoed.
 */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final @org.jspecify.annotations.Nullable String responseBody;

    public ApiException(int statusCode, String message) {
        this(statusCode, message, null, null);
    }

    public ApiException(int statusCode, String message, @org.jspecify.annotations.Nullable String body) {
        this(statusCode, message, body, null);
    }

    public ApiException(
            int statusCode,
            String message,
            @org.jspecify.annotations.Nullable String body,
            @org.jspecify.annotations.Nullable Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = body;
    }

    public int statusCode() {
        return statusCode;
    }

    public @org.jspecify.annotations.Nullable String responseBody() {
        return responseBody;
    }
}
