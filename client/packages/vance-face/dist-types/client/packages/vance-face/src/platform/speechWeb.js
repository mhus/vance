import { getSpeechRate, getSpeechVoiceURI, getSpeechVolume } from '@vance/shared';
/**
 * Web Speech Synthesis bindings — the platform-specific half that the
 * shared `speech/preferences.ts` deliberately does not contain.
 * Mobile builds use `expo-speech` instead and never import this file.
 *
 * The picker heuristic ({@link pickDefaultMaleVoice}) and voice
 * resolution ({@link resolveVoice}) live here because they speak the
 * `SpeechSynthesisVoice` type — a DOM-only construct. The
 * preferences they read (URI / rate / volume) come from
 * `@vance/shared/speech/preferences` so the same user choice
 * surfaces on both platforms.
 */
/**
 * Returns whether the browser exposes the Web Speech Synthesis API.
 * Firefox supports it on all desktop platforms; Chrome and Safari
 * since long. Mobile WebViews are spotty.
 */
export function isSpeechSynthesisSupported() {
    return typeof window !== 'undefined' && 'speechSynthesis' in window;
}
/**
 * Returns the platform's voice catalogue. The list is populated
 * asynchronously on first call in some browsers — see
 * {@link onVoicesChanged} to subscribe to the ready event.
 */
export function listVoices() {
    if (!isSpeechSynthesisSupported())
        return [];
    return window.speechSynthesis.getVoices();
}
/**
 * Subscribe to the `voiceschanged` event. Returns an unsubscribe
 * function. The handler also fires immediately if voices are already
 * available, so callers can use it as a "one-shot ready" hook.
 */
export function onVoicesChanged(handler) {
    if (!isSpeechSynthesisSupported())
        return () => { };
    const synth = window.speechSynthesis;
    synth.addEventListener('voiceschanged', handler);
    // Some browsers populate voices synchronously — fire once now.
    if (synth.getVoices().length > 0) {
        queueMicrotask(handler);
    }
    return () => synth.removeEventListener('voiceschanged', handler);
}
/**
 * Heuristic to pick a sensible default voice for `lang` when the
 * user hasn't chosen one. We prefer voices whose name does *not*
 * match a small list of common female-name hints — the Web Speech
 * API doesn't expose voice gender, so this is best-effort. The user
 * can always override in the settings popover.
 */
export function pickDefaultMaleVoice(voices, lang) {
    if (voices.length === 0)
        return null;
    const langPrefix = lang.toLowerCase().split('-')[0];
    const matching = voices.filter((v) => v.lang.toLowerCase().startsWith(langPrefix));
    const pool = matching.length > 0 ? matching : voices;
    // Names / hints commonly used for female voices across macOS,
    // Windows and Google's voice catalogue. Best-effort — not exhaustive.
    const FEMALE = /\b(female|frau|woman|donna|femme|mujer|samantha|karen|victoria|kate|helena|ellen|emma|hilda|monica|paulina|ines|allison|ava|nora|salli|kimberly|joanna|fiona|moira|susan|tessa|martha|natasha|claire|marlene|alva|ursula|kyoko|silvia|veena|yuna|zuzana|alice|lucia|laura|carmen|petra|katja|anna|merl|tatyana|milena|amelie|sandy|princess)\b/i;
    const male = pool.find((v) => !FEMALE.test(v.name) && !FEMALE.test(v.voiceURI));
    return male ?? pool[0];
}
/**
 * Resolve the {@link SpeechSynthesisVoice} to use for a given
 * language: prefer the user's explicit pick (matched by URI); fall
 * back to {@link pickDefaultMaleVoice}.
 */
export function resolveVoice(lang) {
    const voices = listVoices();
    const stored = getSpeechVoiceURI();
    if (stored) {
        const explicit = voices.find((v) => v.voiceURI === stored);
        if (explicit)
            return explicit;
    }
    return pickDefaultMaleVoice(voices, lang);
}
/**
 * Build a {@link SpeechSynthesisUtterance} preconfigured from the
 * persisted preferences. Caller is responsible for
 * `speechSynthesis.speak()`.
 */
export function buildUtterance(text, lang) {
    if (!isSpeechSynthesisSupported())
        return null;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = lang;
    const voice = resolveVoice(lang);
    if (voice)
        utterance.voice = voice;
    utterance.rate = getSpeechRate();
    utterance.volume = getSpeechVolume();
    return utterance;
}
//# sourceMappingURL=speechWeb.js.map