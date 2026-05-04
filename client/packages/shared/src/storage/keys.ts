/**
 * Canonical key strings for the platform's {@link KeyValueStore}
 * bindings. All keys are prefixed `vance.` to avoid collisions with
 * other apps that share the storage namespace (Web `localStorage`,
 * Mobile `AsyncStorage`).
 *
 * Each key documents which store it belongs to:
 * - `secureStore`: tokens and other sensitive material
 * - `prefsStore`: UI preferences, identity hints, draft state
 *
 * Web collapses both stores onto `localStorage`, so the distinction
 * is non-load-bearing there; Mobile honours it (Keychain vs.
 * AsyncStorage).
 *
 * This module replaces the legacy `persistence/keys.ts`, which is
 * kept for backwards compatibility until Phase 4 of the
 * platform-neutrality refactor (see
 * `readme/reorg-webui-to-clean-shared.md`).
 */
export const StorageKeys = {
  // ── secureStore ─────────────────────────────────────────────────
  /** Access JWT — Bearer-mode REST/WS authentication. Mobile only.
   *  Web cookie-mode never sees the token. */
  authAccessToken: 'vance.auth.accessToken',
  /** Refresh JWT — exchanged at `POST /brain/{tenant}/refresh` for a
   *  fresh access token. Mobile only. */
  authRefreshToken: 'vance.auth.refreshToken',

  // ── prefsStore ──────────────────────────────────────────────────
  /** Tenant the user belongs to. Set after successful login on both
   *  Web (mirrored from the `vance_data` cookie) and Mobile (read
   *  from the login response body). */
  identityTenantId: 'vance.identity.tenantId',
  /** Username of the currently signed-in user. Mirror semantics like
   *  {@link identityTenantId}. */
  identityUsername: 'vance.identity.username',
  /** Currently active session id. Set when the user enters a Chat
   *  session, cleared on logout. */
  activeSessionId: 'vance.activeSessionId',
  /** Tenant + username pair pre-filled on the login form when the
   *  user ticked "Remember user" on a previous successful sign-in.
   *  Stored as JSON `{tenant, username}`. Never carries credentials. */
  rememberedLogin: 'vance.rememberedLogin',
  /** BCP-47 code for speech features (STT + TTS). Sentinel `'auto'`
   *  means fall back to the platform locale. */
  speechLanguage: 'vance.speechLanguage',
  /** Voice URI (Web: `SpeechSynthesisVoice.voiceURI`; Mobile:
   *  `expo-speech` voice identifier) of the user's preferred TTS
   *  voice. Empty / missing means pick a sensible default. */
  speechVoiceUri: 'vance.speechVoiceUri',
  /** TTS playback rate (0.5–2.0). String-stored decimal. */
  speechRate: 'vance.speechRate',
  /** TTS volume (0.0–1.0). Web only — Mobile uses system volume.
   *  String-stored decimal. */
  speechVolume: 'vance.speechVolume',
  /** Chat speaker on/off — `'1'` enabled, anything else disabled. */
  speakerEnabled: 'vance.speakerEnabled',
} as const;

export type StorageKey = (typeof StorageKeys)[keyof typeof StorageKeys];
