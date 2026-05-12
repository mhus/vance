import { Text, TextInput, View, type TextInputProps } from 'react-native';

interface Props extends Omit<TextInputProps, 'className'> {
  label?: string;
  error?: string | null;
  helpText?: string;
}

/**
 * Single-line text input with consistent label / help-text / error
 * geometry. Wrap any RN `TextInputProps` — secureTextEntry,
 * autoCapitalize, etc. flow through unchanged.
 */
export function VInput({ label, error, helpText, ...inputProps }: Props) {
  return (
    <View className="gap-1">
      {label !== undefined ? (
        <Text className="text-xs font-medium text-slate-600 dark:text-slate-400 uppercase tracking-wide">
          {label}
        </Text>
      ) : null}
      <TextInput
        {...inputProps}
        placeholderTextColor="#94a3b8"
        className={`border rounded px-3 py-2 text-base text-slate-900 dark:text-slate-100 bg-white dark:bg-slate-900 ${
          error
            ? 'border-red-500 dark:border-red-400'
            : 'border-slate-300 dark:border-slate-700'
        }`}
      />
      {error ? (
        <Text className="text-xs text-red-600 dark:text-red-400">{error}</Text>
      ) : helpText ? (
        <Text className="text-xs text-slate-500 dark:text-slate-400">{helpText}</Text>
      ) : null}
    </View>
  );
}
