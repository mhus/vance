import { StorageKeys } from '../persistence/keys';

/**
 * Browser text-to-speech preferences. Voice / rate / volume sit
 * alongside the language picker in {@code language.ts}; together they
 * feed any speak-aloud feature in the UI (chat speaker today,
 * read-aloud editors later).
 *
 * Voices are an enumerated list provided by the platform — names and
 * languages vary across OS / browser, so the UI must always populate
 * its picker from {@link listVoices} at runtime, not from a hard-coded
 * table.
 */

export const DEFAULT_RATE = 1.0;
export const MIN_RATE = 0.5;
export const MAX_RATE = 2.0;

export const DEFAULT_VOLUME = 1.0;
export const MIN_VOLUME = 0.0;
export const MAX_VOLUME = 1.0;

/**
 * Returns whether the platform exposes the Web Speech Synthesis API.
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
 * Subscribe to the {@code voiceschanged} event. Returns an unsubscribe
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

// ──────────────── Voice selection ────────────────

/** {@code voiceURI} of the user's chosen voice, or {@code null} for auto. */
export function getSpeechVoiceURI(): string | null {
  return localStorage.getItem(StorageKeys.speechVoiceUri);
}

export function setSpeechVoiceURI(uri: string | null): void {
  if (uri === null || uri === '') {
    localStorage.removeItem(StorageKeys.speechVoiceUri);
    return;
  }
  localStorage.setItem(StorageKeys.speechVoiceUri, uri);
}

/**
 * Heuristic to pick a sensible default voice for {@code lang} when the
 * user hasn't chosen one. We prefer voices whose name does *not* match
 * a small list of common female-name hints — the Web Speech API
 * doesn't expose voice gender, so this is best-effort. The user can
 * always override in the settings popover.
 */
export function pickDefaultMaleVoice(
  voices: SpeechSynthesisVoice[],
  lang: string,
): SpeechSynthesisVoice | null {
  if (voices.length === 0) return null;
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
export function resolveVoice(lang: string): SpeechSynthesisVoice | null {
  const voices = listVoices();
  const stored = getSpeechVoiceURI();
  if (stored) {
    const explicit = voices.find((v) => v.voiceURI === stored);
    if (explicit) return explicit;
  }
  return pickDefaultMaleVoice(voices, lang);
}

// ──────────────── Rate ────────────────

export function getSpeechRate(): number {
  const raw = localStorage.getItem(StorageKeys.speechRate);
  if (!raw) return DEFAULT_RATE;
  const parsed = parseFloat(raw);
  if (!Number.isFinite(parsed)) return DEFAULT_RATE;
  return clamp(parsed, MIN_RATE, MAX_RATE);
}

export function setSpeechRate(rate: number): void {
  if (!Number.isFinite(rate)) return;
  const clamped = clamp(rate, MIN_RATE, MAX_RATE);
  if (clamped === DEFAULT_RATE) {
    localStorage.removeItem(StorageKeys.speechRate);
    return;
  }
  localStorage.setItem(StorageKeys.speechRate, String(clamped));
}

// ──────────────── Volume ────────────────

export function getSpeechVolume(): number {
  const raw = localStorage.getItem(StorageKeys.speechVolume);
  if (!raw) return DEFAULT_VOLUME;
  const parsed = parseFloat(raw);
  if (!Number.isFinite(parsed)) return DEFAULT_VOLUME;
  return clamp(parsed, MIN_VOLUME, MAX_VOLUME);
}

export function setSpeechVolume(volume: number): void {
  if (!Number.isFinite(volume)) return;
  const clamped = clamp(volume, MIN_VOLUME, MAX_VOLUME);
  if (clamped === DEFAULT_VOLUME) {
    localStorage.removeItem(StorageKeys.speechVolume);
    return;
  }
  localStorage.setItem(StorageKeys.speechVolume, String(clamped));
}

// ──────────────── Speaker toggle (chat speak-aloud) ────────────────

export function getSpeakerEnabled(): boolean {
  return localStorage.getItem(StorageKeys.speakerEnabled) === '1';
}

export function setSpeakerEnabled(enabled: boolean): void {
  if (enabled) {
    localStorage.setItem(StorageKeys.speakerEnabled, '1');
  } else {
    localStorage.removeItem(StorageKeys.speakerEnabled);
  }
}

// ──────────────── Speak helpers ────────────────

/**
 * Strip the obvious Markdown tokens before reading aloud — code
 * fences, inline code marks, emphasis markers, link syntax, headings.
 * Reading raw asterisks and brackets is jarring; we don't try to
 * preserve every nuance. For richer rendering, plug in a proper
 * markdown-to-text strip later.
 */
export function stripMarkdown(text: string): string {
  return text
    // fenced code blocks → just the inner text
    .replace(/```[\s\S]*?```/g, (m) => m.replace(/```[a-zA-Z0-9]*\n?|```/g, ''))
    // inline code
    .replace(/`([^`]+)`/g, '$1')
    // images and links — keep the alt / link text only
    .replace(/!?\[([^\]]+)\]\([^)]+\)/g, '$1')
    // headings
    .replace(/^#{1,6}\s+/gm, '')
    // emphasis markers
    .replace(/[*_~]+/g, '')
    // collapse whitespace
    .replace(/[ \t]+\n/g, '\n')
    .trim();
}

/**
 * Build a {@link SpeechSynthesisUtterance} preconfigured from the
 * persisted preferences. Caller is responsible for `speechSynthesis.speak()`.
 */
export function buildUtterance(text: string, lang: string): SpeechSynthesisUtterance | null {
  if (!isSpeechSynthesisSupported()) return null;
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = lang;
  const voice = resolveVoice(lang);
  if (voice) utterance.voice = voice;
  utterance.rate = getSpeechRate();
  utterance.volume = getSpeechVolume();
  return utterance;
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
