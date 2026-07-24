// Pure talk-mode command matching — no Vue, no DOM, no side effects.
//
// Talk mode accumulates dictated speech into the composer ("block
// mode") and commits or steers only on an explicit spoken command
// like "Computer senden". This module turns a single final STT phrase
// into a command action (or nothing), so the Vue composer only has to
// wire the result to mic/speaker/send. Kept side-effect free so it is
// unit-testable in isolation.

import type {
  TalkCommandAction,
  TalkCommandConfig,
} from '../platform/speechSettings';

export interface TalkCommandMatch {
  action: TalkCommandAction;
  /**
   * First token of the matched command phrase — the trigger name, or
   * the bare command word when no name was required. Callers cut the
   * dictated content at this token to drop the command tail.
   */
  firstToken: string;
}

/** Lower-case, strip sentence punctuation, collapse whitespace. */
export function normalizeForMatch(s: string): string {
  return s
    .toLowerCase()
    .replace(/[.,!?;:]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Detect a spoken command at the tail of `phrase`. Matching the tail
 * (not just the whole utterance) means a command works whether it
 * arrives on its own ("Computer senden") or trails dictated content in
 * the same STT chunk ("...das war's Computer senden").
 *
 * Only actions listed in `allowed` are considered, so the caller gates
 * by talk status (e.g. "send" is ignored while paused). When the config
 * requires a trigger name, a bare command word never matches.
 */
export function matchTalkCommand(
  phrase: string,
  config: TalkCommandConfig,
  allowed: readonly TalkCommandAction[],
): TalkCommandMatch | null {
  const norm = normalizeForMatch(phrase);
  if (!norm) return null;
  const names = config.triggerNames
    .map((n) => n.toLowerCase().trim())
    .filter(Boolean);
  for (const action of allowed) {
    for (const raw of config.commands[action] ?? []) {
      const word = raw.toLowerCase().trim();
      if (!word) continue;
      for (const name of names) {
        if (norm === `${name} ${word}` || norm.endsWith(` ${name} ${word}`)) {
          return { action, firstToken: name };
        }
      }
      if (!config.requireTriggerName
          && (norm === word || norm.endsWith(` ${word}`))) {
        return { action, firstToken: word };
      }
    }
  }
  return null;
}

/**
 * Remove the trailing command (from the last occurrence of `firstToken`
 * onward) from a phrase, returning just the dictated content that
 * preceded it. Trailing punctuation/whitespace is trimmed.
 */
export function stripCommandTail(phrase: string, firstToken: string): string {
  const idx = phrase.toLowerCase().lastIndexOf(firstToken.toLowerCase());
  if (idx < 0) return phrase.trim();
  return phrase.slice(0, idx).replace(/[\s.,!?;:]+$/, '').trim();
}
