// Web speech preferences — server-persistent, read from `/me`.
//
// Reads come from the current `/me` answer (`./me`); writes go through
// PUT/DELETE /profile/settings and then reload `/me`, so the next read
// sees the new value.
//
// The matching `@vance/shared/speech/preferences` module is a
// localStorage-based equivalent used by the mobile client. The
// two modules deliberately stay separate: Web stores on the brain,
// Mobile stores locally until we wire Mobile's settings path through
// the brain too.

import { brainFetch } from '@vance/shared';
import {
  DEFAULT_RATE,
  DEFAULT_VOLUME,
  MAX_RATE,
  MAX_VOLUME,
  MIN_RATE,
  MIN_VOLUME,
} from '@vance/shared';
import { loadMe, meSetting } from './me';

// ──────────────── Setting keys ────────────────

const KEY_VOICE_URI = 'webui.speech.voiceUri';
const KEY_RATE = 'webui.speech.rate';
const KEY_VOLUME = 'webui.speech.volume';
const KEY_SPEAKER_ENABLED = 'webui.speech.speakerEnabled';
const KEY_TALK_COMMANDS = 'webui.speech.talkCommands';
const KEY_CHAT_LANGUAGE = 'chat.language';
const KEY_WEBUI_LANGUAGE = 'webui.language';

// ──────────────── Talk-mode voice commands ────────────────
//
// In talk mode nothing is sent on an STT silence gap — that signal is
// unreliable (the user pauses to think and the sentence isn't done).
// Instead every phrase accumulates into the composer ("block mode")
// and an explicit spoken command commits or steers. A later "direct
// mode" may add silence-based auto-send; v1 is block-only.
//
// A command is `<triggerName> <word>`, e.g. "Computer senden". The
// trigger name defaults to "Computer" (STT transcribes it reliably,
// unlike engine names such as "Arthur"). Synonyms cover both German
// and English because the STT language follows the chat language.

export type TalkCommandAction = 'send' | 'clear' | 'pause' | 'resume' | 'end';

export interface TalkCommandConfig {
  /** Wake-words that may prefix a command, e.g. "Computer". */
  triggerNames: string[];
  /** When true a bare command word (no trigger name) is ignored. */
  requireTriggerName: boolean;
  /**
   * When true the "send" command hands over instead of staying ACTIVE:
   * the mic keeps listening for "resume" but nothing said after the send
   * lands in the next message. The reply is still read aloud — unlike an
   * explicit "pause", which also silences the speaker. For users who
   * want to hand over deliberately rather than dictate continuously.
   */
  autoPause: boolean;
  /** Phrase synonyms per action (lower-cased on match). */
  commands: Record<TalkCommandAction, string[]>;
}

function baseTalkCommands(): TalkCommandConfig {
  return {
    triggerNames: ['Computer', 'Vance'],
    requireTriggerName: true,
    autoPause: false,
    commands: {
      send: ['senden', 'abschicken', 'send'],
      clear: ['korrektur', 'verwerfen', 'correction', 'clear'],
      pause: ['pause', 'stopp', 'stop'],
      resume: ['weiter', 'fortsetzen', 'resume', 'continue'],
      end: ['ende', 'beenden', 'end'],
    },
  };
}

/** Fresh, mutation-safe copy of the bundled default command config. */
export function defaultTalkCommands(): TalkCommandConfig {
  return baseTalkCommands();
}

// ──────────────── Reads ────────────────

function readSetting(key: string): string | null {
  return meSetting(key);
}

export function getSpeechVoiceURI(): string | null {
  return readSetting(KEY_VOICE_URI);
}

export function getSpeechRate(): number {
  const raw = readSetting(KEY_RATE);
  if (!raw) return DEFAULT_RATE;
  const parsed = parseFloat(raw);
  if (!Number.isFinite(parsed)) return DEFAULT_RATE;
  return clamp(parsed, MIN_RATE, MAX_RATE);
}

export function getSpeechVolume(): number {
  const raw = readSetting(KEY_VOLUME);
  if (!raw) return DEFAULT_VOLUME;
  const parsed = parseFloat(raw);
  if (!Number.isFinite(parsed)) return DEFAULT_VOLUME;
  return clamp(parsed, MIN_VOLUME, MAX_VOLUME);
}

export function getSpeakerEnabled(): boolean {
  return readSetting(KEY_SPEAKER_ENABLED) === '1';
}

/**
 * Merge a stored JSON override over the bundled default (per-field; the
 * per-action synonym lists replace the default list wholesale when
 * present). Malformed / empty JSON yields the plain default so a broken
 * setting never disables talk mode. Pure — callers pass the raw setting
 * value from whichever source they hold (`/me` or profile object).
 */
export function parseTalkCommands(raw: string | null | undefined): TalkCommandConfig {
  if (!raw) return defaultTalkCommands();
  let parsed: Partial<TalkCommandConfig>;
  try {
    parsed = JSON.parse(raw) as Partial<TalkCommandConfig>;
  } catch {
    return defaultTalkCommands();
  }
  const merged = defaultTalkCommands();
  if (Array.isArray(parsed.triggerNames) && parsed.triggerNames.length > 0) {
    merged.triggerNames = parsed.triggerNames.filter((n) => typeof n === 'string');
  }
  if (typeof parsed.requireTriggerName === 'boolean') {
    merged.requireTriggerName = parsed.requireTriggerName;
  }
  if (typeof parsed.autoPause === 'boolean') {
    merged.autoPause = parsed.autoPause;
  }
  if (parsed.commands && typeof parsed.commands === 'object') {
    for (const action of Object.keys(merged.commands) as TalkCommandAction[]) {
      const list = parsed.commands[action];
      if (Array.isArray(list) && list.length > 0) {
        merged.commands[action] = list.filter((w) => typeof w === 'string');
      }
    }
  }
  return merged;
}

/** Talk-mode command config of the current user (from `/me`). */
export function getTalkCommands(): TalkCommandConfig {
  return parseTalkCommands(readSetting(KEY_TALK_COMMANDS));
}

/**
 * Speech recognition / synthesis language. Cascade:
 *   1. `chat.language` — explicit assistant-language choice from the
 *      profile page; wins because the user picked it for the chat
 *      itself.
 *   2. `webui.language` — the broader UI-language choice. Used when
 *      the user has not split assistant-language from UI-language —
 *      the common case.
 *   3. {@code navigator.language}.
 *   4. `'en-US'`.
 *
 * Short tags (`'de'`, `'en'`) get expanded to a BCP-47 region tag the
 * Web Speech API understands.
 */
export function resolveSpeechLanguage(): string {
  const fromChat = readSetting(KEY_CHAT_LANGUAGE);
  if (fromChat) return expandLanguageTag(fromChat);
  const fromWebUi = readSetting(KEY_WEBUI_LANGUAGE);
  if (fromWebUi) return expandLanguageTag(fromWebUi);
  if (typeof navigator !== 'undefined' && navigator.language) {
    return navigator.language;
  }
  return 'en-US';
}

// ──────────────── Writes ────────────────

async function putSetting(key: string, value: string): Promise<void> {
  await brainFetch('PUT', `profile/settings/${encodeURIComponent(key)}`, {
    body: { value },
  });
  await loadMe();
}

async function deleteSetting(key: string): Promise<void> {
  await brainFetch('DELETE', `profile/settings/${encodeURIComponent(key)}`);
  await loadMe();
}

export async function saveSpeechVoiceURI(uri: string | null): Promise<void> {
  if (!uri) {
    await deleteSetting(KEY_VOICE_URI);
  } else {
    await putSetting(KEY_VOICE_URI, uri);
  }
}

export async function saveSpeechRate(rate: number): Promise<void> {
  if (!Number.isFinite(rate)) return;
  const clamped = clamp(rate, MIN_RATE, MAX_RATE);
  if (clamped === DEFAULT_RATE) {
    await deleteSetting(KEY_RATE);
  } else {
    await putSetting(KEY_RATE, String(clamped));
  }
}

export async function saveSpeechVolume(volume: number): Promise<void> {
  if (!Number.isFinite(volume)) return;
  const clamped = clamp(volume, MIN_VOLUME, MAX_VOLUME);
  if (clamped === DEFAULT_VOLUME) {
    await deleteSetting(KEY_VOLUME);
  } else {
    await putSetting(KEY_VOLUME, String(clamped));
  }
}

export async function saveSpeakerEnabled(enabled: boolean): Promise<void> {
  if (enabled) {
    await putSetting(KEY_SPEAKER_ENABLED, '1');
  } else {
    await deleteSetting(KEY_SPEAKER_ENABLED);
  }
}

export async function saveTalkCommands(cfg: TalkCommandConfig | null): Promise<void> {
  if (!cfg) {
    await deleteSetting(KEY_TALK_COMMANDS);
  } else {
    await putSetting(KEY_TALK_COMMANDS, JSON.stringify(cfg));
  }
}

// ──────────────── Helpers ────────────────

const LANGUAGE_TAG_EXPANSION: Readonly<Record<string, string>> = {
  de: 'de-DE',
  en: 'en-US',
  fr: 'fr-FR',
  es: 'es-ES',
  it: 'it-IT',
  nl: 'nl-NL',
  pt: 'pt-BR',
};

function expandLanguageTag(tag: string): string {
  if (tag.includes('-')) return tag;
  return LANGUAGE_TAG_EXPANSION[tag.toLowerCase()] ?? tag;
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
