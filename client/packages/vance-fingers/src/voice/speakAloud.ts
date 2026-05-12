import * as Speech from 'expo-speech';
import {
  getSpeakerEnabled,
  getSpeechRate,
  resolveSpeechLanguage,
  stripMarkdown,
} from '@vance/shared';

/**
 * Read the given text aloud using the platform TTS engine when the
 * user has the chat speaker on. No-op when `getSpeakerEnabled()`
 * returns false. Markdown tokens are stripped so the user does not
 * hear "asterisk" or "back-tick" mid-sentence.
 *
 * The speech preferences (rate, language) come from the same shared
 * store the Web UI reads — choices made on either client carry
 * across when the underlying KV store is shared.
 */
export function speakAloud(text: string): void {
  if (!getSpeakerEnabled()) return;
  const cleaned = stripMarkdown(text);
  if (cleaned === '') return;
  Speech.speak(cleaned, {
    language: resolveSpeechLanguage(),
    rate: getSpeechRate(),
  });
}

/**
 * Cancel any in-flight utterance. Call when navigating away from
 * the live chat so the speaker does not keep reading after the
 * screen is gone.
 */
export function stopSpeaking(): void {
  void Speech.stop();
}
