import { StorageKeys } from '../persistence/keys';

/**
 * Browser-speech preferences shared across speech-to-text (chat
 * composer mic) and any future text-to-speech reader. Stored in
 * `localStorage` so the choice survives reloads.
 *
 * The sentinel {@link AUTO_LANGUAGE} means "follow the browser" —
 * resolved at use-site to `navigator.language` so the UI keeps
 * working on machines that switch UI language without us hard-coding
 * a fallback.
 */

/** Sentinel value: defer to {@link navigator.language}. */
export const AUTO_LANGUAGE = 'auto';

export interface SpeechLanguageOption {
  /** BCP-47 tag (e.g. {@code 'de-DE'}) or {@link AUTO_LANGUAGE}. */
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
  return localStorage.getItem(StorageKeys.speechLanguage) ?? AUTO_LANGUAGE;
}

/**
 * Persist a BCP-47 code (or {@link AUTO_LANGUAGE}). Pass {@code null}
 * to clear and fall back to auto. Validates against
 * {@link SUPPORTED_SPEECH_LANGUAGES} so we don't store typos.
 */
export function setSpeechLanguage(code: string | null): void {
  if (code === null || code === AUTO_LANGUAGE) {
    localStorage.removeItem(StorageKeys.speechLanguage);
    return;
  }
  const known = SUPPORTED_SPEECH_LANGUAGES.some((opt) => opt.code === code);
  if (!known) {
    throw new Error(`Unknown speech language: ${code}`);
  }
  localStorage.setItem(StorageKeys.speechLanguage, code);
}

/**
 * Effective BCP-47 code to hand to a Web Speech API constructor —
 * resolves the {@link AUTO_LANGUAGE} sentinel against
 * {@link navigator.language}, falls back to {@code 'en-US'} when the
 * navigator value is unset.
 */
export function resolveSpeechLanguage(): string {
  const stored = getSpeechLanguage();
  if (stored !== AUTO_LANGUAGE) return stored;
  return (typeof navigator !== 'undefined' && navigator.language) || 'en-US';
}
