import { ActivityIndicator, Text, View } from 'react-native';

interface Props {
  label?: string;
  /** Centred full-screen loader vs. inline spinner. Default `inline`. */
  variant?: 'inline' | 'centered';
}

export function VLoading({ label, variant = 'inline' }: Props) {
  if (variant === 'centered') {
    return (
      <View className="flex-1 items-center justify-center gap-3">
        <ActivityIndicator />
        {label ? (
          <Text className="text-sm text-slate-500 dark:text-slate-400">{label}</Text>
        ) : null}
      </View>
    );
  }
  return (
    <View className="flex-row items-center gap-2">
      <ActivityIndicator />
      {label ? (
        <Text className="text-sm text-slate-500 dark:text-slate-400">{label}</Text>
      ) : null}
    </View>
  );
}
