import { Text, View } from 'react-native';
import { ChatRole } from '@vance/generated';

interface Props {
  role: ChatRole;
  content: string;
  /** Stream-buffered messages render with a slight visual cue. */
  streaming?: boolean;
}

/**
 * Single chat bubble. User messages right-aligned in the primary
 * colour, assistant messages left-aligned with a neutral surface,
 * system messages full-width with muted styling.
 */
export function MessageBubble({ role, content, streaming = false }: Props) {
  if (role === ChatRole.SYSTEM) {
    return (
      <View className="px-4 py-1">
        <Text className="text-xs italic text-slate-500 dark:text-slate-400 text-center">
          {content}
        </Text>
      </View>
    );
  }
  const isUser = role === ChatRole.USER;
  return (
    <View className={`px-3 py-1 ${isUser ? 'items-end' : 'items-start'}`}>
      <View
        className={`max-w-[85%] rounded-2xl px-3 py-2 ${
          isUser
            ? 'bg-blue-600'
            : streaming
              ? 'bg-slate-100 dark:bg-slate-800 opacity-80'
              : 'bg-slate-100 dark:bg-slate-800'
        }`}
      >
        <Text
          className={`text-base ${
            isUser ? 'text-white' : 'text-slate-900 dark:text-slate-100'
          }`}
        >
          {content}
        </Text>
      </View>
    </View>
  );
}
