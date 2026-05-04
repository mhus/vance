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
 * Heuristic to pick a sensible default voice for `lang` when the
 * user hasn't chosen one. We prefer voices whose name does *not*
 * match a small list of common female-name hints — the Web Speech
 * API doesn't expose voice gender, so this is best-effort. The user
 * can always override in the settings popover.
 */
export declare function pickDefaultMaleVoice(voices: SpeechSynthesisVoice[], lang: string): SpeechSynthesisVoice | null;
/**
 * Resolve the {@link SpeechSynthesisVoice} to use for a given
 * language: prefer the user's explicit pick (matched by URI); fall
 * back to {@link pickDefaultMaleVoice}.
 */
export declare function resolveVoice(lang: string): SpeechSynthesisVoice | null;
/**
 * Build a {@link SpeechSynthesisUtterance} preconfigured from the
 * persisted preferences. Caller is responsible for
 * `speechSynthesis.speak()`.
 */
export declare function buildUtterance(text: string, lang: string): SpeechSynthesisUtterance | null;
//# sourceMappingURL=speechWeb.d.ts.map