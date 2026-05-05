import { Text, View } from 'react-native';

export type VBadgeVariant = 'default' | 'primary' | 'danger' | 'warning' | 'success';

interface Props {
  variant?: VBadgeVariant;
  children: React.ReactNode;
}

const containerByVariant: Record<VBadgeVariant, string> = {
  default: 'bg-slate-200 dark:bg-slate-700',
  primary: 'bg-blue-600',
  danger: 'bg-red-600',
  warning: 'bg-amber-500',
  success: 'bg-green-600',
};

const textByVariant: Record<VBadgeVariant, string> = {
  default: 'text-slate-700 dark:text-slate-200',
  primary: 'text-white',
  danger: 'text-white',
  warning: 'text-white',
  success: 'text-white',
};

export function VBadge({ variant = 'default', children }: Props) {
  return (
    <View className={`px-2 py-0.5 rounded-full ${containerByVariant[variant]}`}>
      <Text className={`text-xs font-medium ${textByVariant[variant]}`}>{children}</Text>
    </View>
  );
}
