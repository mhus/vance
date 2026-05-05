import { Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';

interface Props {
  title?: string;
  /** Show a back chevron in the leading slot when defined. */
  onBack?: () => void;
  /** Right-aligned header content — typically icon-buttons. */
  trailing?: React.ReactNode;
  /** Sticky bottom region — chat composer, action bar, etc. */
  footer?: React.ReactNode;
  children: React.ReactNode;
}

/**
 * The single top-level layout for every screen below the Login.
 * Header geometry (height, padding, separator) is centralised here
 * so screens don't drift visually.
 *
 * Title is optional — list screens that own the header text via
 * the navigator's title bar may pass `<MobileShell>` without one.
 */
export function MobileShell({ title, onBack, trailing, footer, children }: Props) {
  const hasHeader = title !== undefined || onBack !== undefined || trailing !== undefined;
  return (
    <SafeAreaView edges={['top', 'left', 'right']} className="flex-1 bg-white dark:bg-slate-950">
      {hasHeader ? (
        <View className="flex-row items-center px-3 h-12 border-b border-slate-200 dark:border-slate-800">
          {onBack ? (
            <Pressable
              onPress={onBack}
              hitSlop={8}
              className="p-1 -ml-1"
              accessibilityRole="button"
              accessibilityLabel="Back"
            >
              <Ionicons name="chevron-back" size={24} color="#475569" />
            </Pressable>
          ) : null}
          <View className="flex-1 px-2">
            {title ? (
              <Text
                className="text-lg font-semibold text-slate-900 dark:text-slate-100"
                numberOfLines={1}
              >
                {title}
              </Text>
            ) : null}
          </View>
          {trailing ? <View className="flex-row items-center gap-1">{trailing}</View> : null}
        </View>
      ) : null}
      <View className="flex-1">{children}</View>
      {footer ? (
        <SafeAreaView edges={['bottom']} className="border-t border-slate-200 dark:border-slate-800">
          {footer}
        </SafeAreaView>
      ) : null}
    </SafeAreaView>
  );
}
