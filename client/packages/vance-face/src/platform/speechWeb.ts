import {
  getSpeechRate,
  getSpeechVoiceURI,
  getSpeechVolume,
} from './speechSettings';

/**
 * Web Speech Synthesis bindings — the platform-specific half that the
 * shared `speech/preferences.ts` deliberately does not contain.
 * Mobile builds use `expo-speech` instead and never import this file.
 *
 * Voice resolution ({@link resolveVoice}) and the language-matching
 * picker ({@link pickFirstVoiceForLanguage}) live here because they
 * speak the `SpeechSynthesisVoice` type — a DOM-only construct. The
 * preferences they read (URI / rate / volume) come from
 * `./speechSettings` so the same user choice surfaces on every page.
 */

/**
 * Returns whether the browser exposes the Web Speech Synthesis API.
 * Firefox supports it on all desktop platforms; Chrome and Safari
 * since long. Mobile WebViews are spotty.
 */
export function isSpeechSynthesisSupported(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window;
}

/**
 * Returns the platform's voice catalogue. The list is populated
 * asynchronously on first call in some browsers — see
 * {@link onVoicesChanged} to subscribe to the ready event.
 */
export function listVoices(): SpeechSynthesisVoice[] {
  if (!isSpeechSynthesisSupported()) return [];
  return window.speechSynthesis.getVoices();
}

/**
 * Subscribe to the `voiceschanged` event. Returns an unsubscribe
 * function. The handler also fires immediately if voices are already
 * available, so callers can use it as a "one-shot ready" hook.
 */
export function onVoicesChanged(handler: () => void): () => void {
  if (!isSpeechSynthesisSupported()) return () => {};
  const synth = window.speechSynthesis;
  synth.addEventListener('voiceschanged', handler);
  // Some browsers populate voices synchronously — fire once now.
  if (synth.getVoices().length > 0) {
    queueMicrotask(handler);
  }
  return () => synth.removeEventListener('voiceschanged', handler);
}

/**
 * First available voice whose BCP-47 primary subtag matches
 * {@code lang}'s primary subtag (e.g. {@code 'de-DE'} → {@code 'de'}).
 * Returns {@code null} when no voice matches — the caller should then
 * skip TTS rather than fall back to a wrong-language voice.
 */
export function pickFirstVoiceForLanguage(
  voices: SpeechSynthesisVoice[],
  lang: string,
): SpeechSynthesisVoice | null {
  if (voices.length === 0) return null;
  const langPrefix = lang.toLowerCase().split('-')[0];
  const matching = voices.filter(
    (v) => v.lang.toLowerCase().replace('_', '-').split('-')[0] === langPrefix,
  );
  if (matching.length === 0) return null;
  // Prefer the platform's default voice for that language if it's
  // in the matching set — otherwise just the first one.
  const def = matching.find((v) => v.default);
  return def ?? matching[0];
}

/**
 * Resolve the {@link SpeechSynthesisVoice} to use for {@code lang}:
 *   1. user's stored pick (matched by URI), provided its language
 *      still matches — covers the cross-device case where the saved
 *      URI exists in the voice catalogue but is, say, a German
 *      voice on an EN page.
 *   2. first voice in the requested language.
 *   3. {@code null} — caller skips TTS rather than speaking the
 *      wrong language.
 */
export function resolveVoice(lang: string): SpeechSynthesisVoice | null {
  const voices = listVoices();
  const langPrefix = lang.toLowerCase().split('-')[0];
  const stored = getSpeechVoiceURI();
  if (stored) {
    const explicit = voices.find((v) => v.voiceURI === stored);
    const explicitPrefix = explicit
      ? explicit.lang.toLowerCase().replace('_', '-').split('-')[0]
      : null;
    if (explicit && explicitPrefix === langPrefix) return explicit;
  }
  return pickFirstVoiceForLanguage(voices, lang);
}

/**
 * Build a {@link SpeechSynthesisUtterance}. {@code rate} / {@code volume}
 * default to the persisted profile preferences but the caller can
 * override either — used by the chat composer to apply the user's
 * in-session quick-adjust without writing it back to the server.
 * Caller is responsible for `speechSynthesis.speak()`.
 */
export function buildUtterance(
  text: string,
  lang: string,
  overrides: { rate?: number; volume?: number } = {},
): SpeechSynthesisUtterance | null {
  if (!isSpeechSynthesisSupported()) return null;
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = lang;
  const voice = resolveVoice(lang);
  if (voice) utterance.voice = voice;
  utterance.rate = overrides.rate ?? getSpeechRate();
  utterance.volume = overrides.volume ?? getSpeechVolume();
  return utterance;
}
