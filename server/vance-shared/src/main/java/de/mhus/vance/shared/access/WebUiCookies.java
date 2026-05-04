package de.mhus.vance.shared.access;

/**
 * Cookie-name constants shared between the access controller (sets the
 * cookies) and the access filter (reads {@link #ACCESS} as an auth
 * fallback).
 *
 * <p>Cookies are how the browser-hosted web UI keeps tokens out of
 * JavaScript reach: {@code HttpOnly} on the access and refresh cookies
 * means an XSS payload can't exfiltrate them. The {@code data} cookie
 * is intentionally <b>not</b> {@code HttpOnly} — the SPA reads it once
 * on bootstrap to know who is logged in, what their display name is,
 * and which {@code webui.*} settings to apply. The data cookie carries
 * no credential, so JS exposure is acceptable.
 *
 * <p>The CLI clients (vance-foot) keep using the bearer-header path —
 * cookies are a web-only concern. Servers MUST accept either, so
 * existing automation keeps working.
 */
public final class WebUiCookies {

    /** Carries the access JWT. {@code HttpOnly}, short-lived (24 h). */
    public static final String ACCESS = "vance_access";

    /** Carries the refresh JWT. {@code HttpOnly}, long-lived (30 d). */
    public static final String REFRESH = "vance_refresh";

    /**
     * Non-HttpOnly cookie carrying the {@link de.mhus.vance.api.access.WebUiSessionData}
     * payload as URL-encoded JSON. The web UI reads it on every page
     * load to render the user's identity + apply {@code webui.*}
     * settings. Refreshed by every {@code /access} response.
     */
    public static final String DATA = "vance_data";

    /**
     * Prefix for per-user settings that ship in the {@link #DATA}
     * cookie. Stored in the {@code _user_<login>} project on the
     * {@code project} reference scope. Examples: {@code webui.theme},
     * {@code webui.language}, {@code webui.editor.composerMode}.
     */
    public static final String SETTINGS_PREFIX = "webui.";

    private WebUiCookies() {}
}
