import { useCallback, useState } from 'react';
import { Pressable, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { VoiceButton } from './VoiceButton';

interface Props {
  disabled?: boolean;
  onSend: (text: string) => void;
}

/**
 * Single-line composer with a push-to-talk dictation button on the
 * left and a send button on the right. Voice transcripts are routed
 * into the same `text` state so the user can edit before sending.
 */
export function ChatComposer({ disabled = false, onSend }: Props) {
  const [text, setText] = useState('');

  const send = useCallback(() => {
    const trimmed = text.trim();
    if (trimmed === '' || disabled) return;
    onSend(trimmed);
    setText('');
  }, [text, disabled, onSend]);

  const onTranscript = useCallback((t: string) => {
    setText(t);
  }, []);

  const sendDisabled = disabled || text.trim() === '';

  return (
    <View className="flex-row items-end gap-2 px-3 py-2 bg-white dark:bg-slate-950">
      <VoiceButton disabled={disabled} onTranscript={onTranscript} />
      <View className="flex-1">
        <TextInput
          value={text}
          onChangeText={setText}
          placeholder="Message…"
          placeholderTextColor="#94a3b8"
          editable={!disabled}
          multiline
          className="border border-slate-300 dark:border-slate-700 rounded-2xl px-3 py-2 text-base text-slate-900 dark:text-slate-100 max-h-32"
        />
      </View>
      <Pressable
        onPress={send}
        disabled={sendDisabled}
        hitSlop={8}
        className={`h-11 w-11 items-center justify-center rounded-full ${
          sendDisabled ? 'bg-blue-300 dark:bg-blue-900' : 'bg-blue-600 active:bg-blue-700'
        }`}
        accessibilityRole="button"
        accessibilityLabel="Send"
      >
        <Ionicons name="send" size={20} color="white" />
      </Pressable>
    </View>
  );
}
