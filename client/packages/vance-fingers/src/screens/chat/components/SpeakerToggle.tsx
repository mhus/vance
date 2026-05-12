import { useState } from 'react';
import { Pressable } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { getSpeakerEnabled, setSpeakerEnabled } from '@vance/shared';
import { stopSpeaking } from '@/voice/speakAloud';

/**
 * Trailing-slot icon for the chat header. Toggles the persisted
 * `getSpeakerEnabled()` flag. When the user turns the speaker off
 * we also cancel any in-flight utterance so we don't trail off
 * mid-sentence.
 */
export function SpeakerToggle() {
  const [enabled, setEnabled] = useState(getSpeakerEnabled());

  function toggle() {
    const next = !enabled;
    setSpeakerEnabled(next);
    setEnabled(next);
    if (!next) stopSpeaking();
  }

  return (
    <Pressable
      onPress={toggle}
      hitSlop={8}
      className="p-2"
      accessibilityRole="switch"
      accessibilityState={{ checked: enabled }}
      accessibilityLabel="Toggle speaker"
    >
      <Ionicons
        name={enabled ? 'volume-medium' : 'volume-mute-outline'}
        size={22}
        color={enabled ? '#2563eb' : '#475569'}
      />
    </Pressable>
  );
}
