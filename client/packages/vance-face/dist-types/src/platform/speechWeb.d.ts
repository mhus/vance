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
export declare function isSpeechSynthesisSupported(): boolean;
/**
 * Returns the platform's voice catalogue. The list is populated
 * asynchronously on first call in some browsers — see
 * {@link onVoicesChanged} to subscribe to the ready event.
 */
export declare function listVoices(): SpeechSynthesisVoice[];
/**
 * Subscribe to the `voiceschanged` event. Returns an unsubscribe
 * function. The handler also fires immediately if voices are already
 * available, so callers can use it as a "one-shot ready" hook.
 */
export declare function onVoicesChanged(handler: () => void): () => void;
/**
 * First available voice whose BCP-47 primary subtag matches
 * {@code lang}'s primary subtag (e.g. {@code 'de-DE'} → {@code 'de'}).
 * Returns {@code null} when no voice matches — the caller should then
 * skip TTS rather than fall back to a wrong-language voice.
 */
export declare function pickFirstVoiceForLanguage(voices: SpeechSynthesisVoice[], lang: string): SpeechSynthesisVoice | null;
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
export declare function resolveVoice(lang: string): SpeechSynthesisVoice | null;
/**
 * Build a {@link SpeechSynthesisUtterance}. {@code rate} / {@code volume}
 * default to the persisted profile preferences but the caller can
 * override either — used by the chat composer to apply the user's
 * in-session quick-adjust without writing it back to the server.
 * Caller is responsible for `speechSynthesis.speak()`.
 */
export declare function buildUtterance(text: string, lang: string, overrides?: {
    rate?: number;
    volume?: number;
}): SpeechSynthesisUtterance | null;
//# sourceMappingURL=speechWeb.d.ts.map