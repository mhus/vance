import { ActivityIndicator, Pressable, Text, type GestureResponderEvent } from 'react-native';

export type VButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'link';
export type VButtonSize = 'sm' | 'md' | 'lg';

interface Props {
  variant?: VButtonVariant;
  size?: VButtonSize;
  loading?: boolean;
  disabled?: boolean;
  onPress?: (e: GestureResponderEvent) => void;
  children?: React.ReactNode;
}

const containerByVariant: Record<VButtonVariant, string> = {
  primary: 'bg-blue-600 active:bg-blue-700',
  secondary: 'bg-slate-200 active:bg-slate-300 dark:bg-slate-800 dark:active:bg-slate-700',
  ghost: 'active:bg-slate-100 dark:active:bg-slate-800',
  danger: 'bg-red-600 active:bg-red-700',
  link: '',
};

const textByVariant: Record<VButtonVariant, string> = {
  primary: 'text-white',
  secondary: 'text-slate-900 dark:text-slate-100',
  ghost: 'text-slate-700 dark:text-slate-300',
  danger: 'text-white',
  link: 'text-blue-600 dark:text-blue-400 underline',
};

const containerBySize: Record<VButtonSize, string> = {
  sm: 'px-3 py-1.5 rounded',
  md: 'px-4 py-2.5 rounded-md',
  lg: 'px-5 py-3 rounded-md',
};

const textBySize: Record<VButtonSize, string> = {
  sm: 'text-sm font-medium',
  md: 'text-base font-medium',
  lg: 'text-base font-semibold',
};

/**
 * The only button in the app. Drives variant via {@link VButtonVariant}
 * — `primary` for the dominant call-to-action, `secondary` for
 * non-destructive alternates, `ghost` for inline tertiary actions,
 * `danger` for destructive confirmations, `link` for inline text
 * links.
 *
 * `loading` swaps the label for a spinner and disables press; the
 * caller still sees their handler fire only when neither `loading`
 * nor `disabled` is set.
 */
export function VButton({
  variant = 'primary',
  size = 'md',
  loading = false,
  disabled = false,
  onPress,
  children,
}: Props) {
  const isInteractive = !loading && !disabled;
  return (
    <Pressable
      onPress={isInteractive ? onPress : undefined}
      disabled={!isInteractive}
      className={`${containerBySize[size]} ${containerByVariant[variant]} items-center justify-center flex-row gap-2 ${
        !isInteractive && variant !== 'link' ? 'opacity-60' : ''
      }`}
    >
      {loading ? (
        <ActivityIndicator color={variant === 'primary' || variant === 'danger' ? 'white' : '#475569'} />
      ) : (
        <Text className={`${textBySize[size]} ${textByVariant[variant]}`}>{children}</Text>
      )}
    </Pressable>
  );
}
