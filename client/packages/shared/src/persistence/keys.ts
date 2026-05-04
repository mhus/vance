// Browser localStorage keys used by the Vance Web-UI.
// All keys are prefixed `vance.` to avoid collisions with other apps on the
// same origin.

export const StorageKeys = {
  jwt: 'vance.jwt',
  tenantId: 'vance.tenantId',
  username: 'vance.username',
  activeSessionId: 'vance.activeSessionId',
  /** One-shot draft handed across editors (e.g. Inbox → Document
   *  "create from message"). See `documentDraft.ts`. */
  documentDraft: 'vance.documentDraft',
  /** BCP-47 code for browser speech features (STT today, TTS later).
   *  Sentinel `'auto'` means "fall back to navigator.language". */
  speechLanguage: 'vance.speechLanguage',
  /** SpeechSynthesisVoice.voiceURI of the user's preferred TTS voice.
   *  Empty / missing means "auto-pick a sensible voice for the language". */
  speechVoiceUri: 'vance.speechVoiceUri',
  /** TTS playback rate (0.5–2.0). String-stored decimal. */
  speechRate: 'vance.speechRate',
  /** TTS volume (0.0–1.0). String-stored decimal. */
  speechVolume: 'vance.speechVolume',
  /** Chat speaker on/off — `'1'` enabled, anything else disabled. */
  speakerEnabled: 'vance.speakerEnabled',
  /** Tenant + username pair pre-filled on the login form when the
   *  user ticked "Remember user" on a previous successful login.
   *  Stored as JSON `{tenant, username}` so future fields (display
   *  hints, last-used login mode) can be added without a key bump. */
  rememberedLogin: 'vance.rememberedLogin',
} as const;

export type StorageKey = (typeof StorageKeys)[keyof typeof StorageKeys];
