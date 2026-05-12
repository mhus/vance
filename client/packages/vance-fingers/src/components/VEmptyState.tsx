import { Text, View } from 'react-native';

interface Props {
  headline: string;
  body?: string;
  icon?: React.ReactNode;
  action?: React.ReactNode;
}

/**
 * Consistent "nothing here" panel for any list-style screen.
 * Centred vertically inside the parent.
 */
export function VEmptyState({ headline, body, icon, action }: Props) {
  return (
    <View className="flex-1 items-center justify-center px-8 gap-3">
      {icon ? <View>{icon}</View> : null}
      <Text className="text-lg font-semibold text-slate-900 dark:text-slate-100 text-center">
        {headline}
      </Text>
      {body ? (
        <Text className="text-sm text-slate-600 dark:text-slate-400 text-center">{body}</Text>
      ) : null}
      {action ? <View className="mt-2">{action}</View> : null}
    </View>
  );
}
