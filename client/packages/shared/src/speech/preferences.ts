import { getStorage } from '../platform/index';
import { StorageKeys } from '../storage/keys';

/**
 * Platform-neutral speech preferences and language picker. Persisted
 * via the host-bound {@link KeyValueStore} from
 * {@link configurePlatform}.
 *
 * Web Speech API specifics (voice catalogue, utterance construction)
 * live in `vance-face/src/platform/speechWeb.ts`. Mobile equivalents
 * (via `expo-speech`) live in the mobile app's platform layer. Both
 * read the same preferences from this module so a user's choices
 * survive across clients on the same device when the storage backend
 * is shared.
 */

// ──────────────── Range constants ────────────────

export const DEFAULT_RATE = 1.0;
export const MIN_RATE = 0.5;
export const MAX_RATE = 2.0;

export const DEFAULT_VOLUME = 1.0;
export const MIN_VOLUME = 0.0;
export const MAX_VOLUME = 1.0;

// ──────────────── Language ────────────────

/** Sentinel value: defer to the platform locale. */
export const AUTO_LANGUAGE = 'auto';

export interface SpeechLanguageOption {
  /** BCP-47 tag (e.g. `'de-DE'`) or {@link AUTO_LANGUAGE}. */
  code: string;
  /** Human-readable label shown in the picker. */
  label: string;
}

/**
 * Curated short list. Long enough to cover the team's likely user
 * base, short enough to avoid a wall of options. Extend by editing
 * this array — the picker reads it directly.
 */
export const SUPPORTED_SPEECH_LANGUAGES: readonly SpeechLanguageOption[] = [
  { code: AUTO_LANGUAGE, label: 'Auto (browser default)' },
  { code: 'de-DE', label: 'Deutsch (Deutschland)' },
  { code: 'en-US', label: 'English (US)' },
  { code: 'en-GB', label: 'English (UK)' },
  { code: 'fr-FR', label: 'Français' },
  { code: 'es-ES', label: 'Español' },
  { code: 'it-IT', label: 'Italiano' },
  { code: 'nl-NL', label: 'Nederlands' },
  { code: 'pt-BR', label: 'Português (Brasil)' },
];

/** Returns the stored preference, or {@link AUTO_LANGUAGE} on first run. */
export function getSpeechLanguage(): string {
  return getStorage().prefsStore.get(StorageKeys.speechLanguage) ?? AUTO_LANGUAGE;
}

/**
 * Persist a BCP-47 code (or {@link AUTO_LANGUAGE}). Pass `null` to
 * clear and fall back to auto. Validates against
 * {@link SUPPORTED_SPEECH_LANGUAGES} so we don't store typos.
 */
export function setSpeechLanguage(code: string | null): void {
  const prefs = getStorage().prefsStore;
  if (code === null || code === AUTO_LANGUAGE) {
    prefs.remove(StorageKeys.speechLanguage);
    return;
  }
  const known = SUPPORTED_SPEECH_LANGUAGES.some((opt) => opt.code === code);
  if (!known) {
    throw new Error(`Unknown speech language: ${code}`);
  }
  prefs.set(StorageKeys.speechLanguage, code);
}

/**
 * Effective BCP-47 code. Resolves the {@link AUTO_LANGUAGE} sentinel
 * against the host's current locale (`navigator.language` on Web,
 * Expo Localization shim on Mobile), falling back to `'en-US'` when
 * the host has no locale to report.
 */
export function resolveSpeechLanguage(): string {
  const stored = getSpeechLanguage();
  if (stored !== AUTO_LANGUAGE) return stored;
  return (typeof navigator !== 'undefined' && navigator.language) || 'en-US';
}

// ──────────────── Voice URI ────────────────

/** Voice URI of the user's chosen TTS voice, or `null` for auto. */
export function getSpeechVoiceURI(): string | null {
  return getStorage().prefsStore.get(StorageKeys.speechVoiceUri);
}

export function setSpeechVoiceURI(uri: string | null): void {
  const prefs = getStorage().prefsStore;
  if (uri === null || uri === '') {
    prefs.remove(StorageKeys.speechVoiceUri);
    return;
  }
  prefs.set(StorageKeys.speechVoiceUri, uri);
}

// ──────────────── Rate ────────────────

export function getSpeechRate(): number {
  const raw = getStorage().prefsStore.get(StorageKeys.speechRate);
  if (!raw) return DEFAULT_RATE;
  const parsed = parseFloat(raw);
  if (!Number.isFinite(parsed)) return DEFAULT_RATE;
  return clamp(parsed, MIN_RATE, MAX_RATE);
}

export function setSpeechRate(rate: number): void {
  if (!Number.isFinite(rate)) return;
  const prefs = getStorage().prefsStore;
  const clamped = clamp(rate, MIN_RATE, MAX_RATE);
  if (clamped === DEFAULT_RATE) {
    prefs.remove(StorageKeys.speechRate);
    return;
  }
  prefs.set(StorageKeys.speechRate, String(clamped));
}

// ──────────────── Volume ────────────────

export function getSpeechVolume(): number {
  const raw = getStorage().prefsStore.get(StorageKeys.speechVolume);
  if (!raw) return DEFAULT_VOLUME;
  const parsed = parseFloat(raw);
  if (!Number.isFinite(parsed)) return DEFAULT_VOLUME;
  return clamp(parsed, MIN_VOLUME, MAX_VOLUME);
}

export function setSpeechVolume(volume: number): void {
  if (!Number.isFinite(volume)) return;
  const prefs = getStorage().prefsStore;
  const clamped = clamp(volume, MIN_VOLUME, MAX_VOLUME);
  if (clamped === DEFAULT_VOLUME) {
    prefs.remove(StorageKeys.speechVolume);
    return;
  }
  prefs.set(StorageKeys.speechVolume, String(clamped));
}

// ──────────────── Speaker toggle ────────────────

export function getSpeakerEnabled(): boolean {
  return getStorage().prefsStore.get(StorageKeys.speakerEnabled) === '1';
}

export function setSpeakerEnabled(enabled: boolean): void {
  const prefs = getStorage().prefsStore;
  if (enabled) {
    prefs.set(StorageKeys.speakerEnabled, '1');
  } else {
    prefs.remove(StorageKeys.speakerEnabled);
  }
}

// ──────────────── Helpers ────────────────

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
