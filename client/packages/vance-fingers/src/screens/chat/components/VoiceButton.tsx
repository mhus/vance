import { useEffect } from 'react';
import { Pressable, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useVoiceRecognition } from '@/voice/voiceRecognition';

interface Props {
  /** Disable while the composer is busy sending. */
  disabled?: boolean;
  /** Called as the recogniser produces partial / final transcripts. */
  onTranscript: (text: string) => void;
}

/**
 * Push-to-talk button. Hold to listen, release to commit. The
 * transcript flows live into the composer via {@link onTranscript}.
 *
 * Disabled with a hint colour when `expo-speech-recognition`'s
 * native module is not available (Expo Go without a dev build).
 */
export function VoiceButton({ disabled = false, onTranscript }: Props) {
  const v = useVoiceRecognition();

  // Mirror partial/final transcripts into the composer as they arrive.
  useEffect(() => {
    if (v.transcript !== '') onTranscript(v.transcript);
  }, [v.transcript, onTranscript]);

  const interactive = v.available && !disabled;
  const colour = !v.available ? '#cbd5e1' : v.listening ? '#dc2626' : '#2563eb';

  return (
    <Pressable
      onPressIn={interactive ? v.start : undefined}
      onPressOut={interactive ? v.stop : undefined}
      disabled={!interactive}
      hitSlop={8}
      className={`h-11 w-11 items-center justify-center rounded-full ${
        v.listening ? 'bg-red-100 dark:bg-red-950' : 'bg-slate-100 dark:bg-slate-800'
      } ${!interactive ? 'opacity-50' : ''}`}
      accessibilityRole="button"
      accessibilityLabel={v.listening ? 'Stop listening' : 'Hold to dictate'}
    >
      <View>
        <Ionicons name={v.listening ? 'stop-circle' : 'mic'} size={22} color={colour} />
      </View>
    </Pressable>
  );
}
