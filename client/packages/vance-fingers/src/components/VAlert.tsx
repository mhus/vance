import { Text, View } from 'react-native';

export type VAlertVariant = 'info' | 'warning' | 'error' | 'success';

interface Props {
  variant?: VAlertVariant;
  children: React.ReactNode;
}

const containerByVariant: Record<VAlertVariant, string> = {
  info: 'bg-blue-50 dark:bg-blue-950 border-blue-200 dark:border-blue-900',
  warning: 'bg-amber-50 dark:bg-amber-950 border-amber-200 dark:border-amber-900',
  error: 'bg-red-50 dark:bg-red-950 border-red-200 dark:border-red-900',
  success: 'bg-green-50 dark:bg-green-950 border-green-200 dark:border-green-900',
};

const textByVariant: Record<VAlertVariant, string> = {
  info: 'text-blue-800 dark:text-blue-200',
  warning: 'text-amber-800 dark:text-amber-200',
  error: 'text-red-800 dark:text-red-200',
  success: 'text-green-800 dark:text-green-200',
};

export function VAlert({ variant = 'info', children }: Props) {
  return (
    <View className={`px-3 py-2 rounded border ${containerByVariant[variant]}`}>
      {typeof children === 'string' ? (
        <Text className={`text-sm ${textByVariant[variant]}`}>{children}</Text>
      ) : (
        children
      )}
    </View>
  );
}
