import { useMemo } from 'react';
import { Pressable, Text, View } from 'react-native';
import { ChatRole } from '@vance/generated';

/**
 * Structured ASK_USER picker entry. Mirrors the schema set by the
 * action handlers in {@code ArthurActionSchema} / {@code
 * EddieActionSchema}. The Markdown rendering of the same data sits
 * in {@link Props.content} as a fallback for clients that don't
 * implement the picker.
 */
export interface AskUserOption {
  label: string;
  description?: string;
}

interface Props {
  role: ChatRole;
  content: string;
  /** Stream-buffered messages render with a slight visual cue. */
  streaming?: boolean;
  /**
   * Optional structured metadata mirroring {@code ChatMessageDto.meta}.
   * Today we only consume {@code askUserOptions}; unknown keys are
   * ignored gracefully.
   */
  meta?: Record<string, unknown> | null;
  /**
   * Whether the picker buttons are still clickable. Set false once
   * the user has answered (a later USER message landed) so stale
   * buttons grey out instead of double-firing the same answer.
   */
  optionsActionable?: boolean;
  /**
   * Picker click handler — receives the option {@code label}. The
   * caller feeds it into the chat send path so a click is
   * semantically identical to typing the same text.
   */
  onPickOption?: (label: string) => void;
}

/**
 * Single chat bubble. User messages right-aligned in the primary
 * colour, assistant messages left-aligned with a neutral surface,
 * system messages full-width with muted styling.
 *
 * <p>When an assistant message carries {@code meta.askUserOptions},
 * the picker renders below the text — see
 * {@code specification/eddie-engine.md} §5.6 / §5.8.
 */
export function MessageBubble({
  role,
  content,
  streaming = false,
  meta,
  optionsActionable = true,
  onPickOption,
}: Props) {
  const options = useMemo<AskUserOption[]>(() => {
    const raw = meta?.['askUserOptions'];
    if (!Array.isArray(raw)) return [];
    const out: AskUserOption[] = [];
    for (const item of raw) {
      if (!item || typeof item !== 'object') continue;
      const obj = item as Record<string, unknown>;
      const label = obj['label'];
      if (typeof label !== 'string' || !label.trim()) continue;
      const desc = obj['description'];
      out.push({
        label: label.trim(),
        description:
          typeof desc === 'string' && desc.trim() ? desc.trim() : undefined,
      });
    }
    return out;
  }, [meta]);

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
        {options.length > 0 ? (
          <View className="mt-3 flex-row flex-wrap gap-2">
            {options.map((opt) => (
              <Pressable
                key={opt.label}
                onPress={() => {
                  if (!optionsActionable) return;
                  onPickOption?.(opt.label);
                }}
                disabled={!optionsActionable}
                className={`px-3 py-1.5 rounded-lg border ${
                  optionsActionable
                    ? 'border-blue-500/40 bg-blue-500/10 active:bg-blue-500/20'
                    : 'border-slate-300 dark:border-slate-600 bg-slate-200/40 dark:bg-slate-700/40 opacity-50'
                }`}
              >
                <Text
                  className={`text-sm font-medium ${
                    optionsActionable
                      ? 'text-blue-700 dark:text-blue-200'
                      : 'text-slate-500 dark:text-slate-400'
                  }`}
                >
                  {opt.label}
                </Text>
                {opt.description ? (
                  <Text
                    className={`text-[10px] mt-0.5 ${
                      optionsActionable
                        ? 'text-blue-700/70 dark:text-blue-200/70'
                        : 'text-slate-500/70 dark:text-slate-400/70'
                    }`}
                  >
                    {opt.description}
                  </Text>
                ) : null}
              </Pressable>
            ))}
          </View>
        ) : null}
      </View>
    </View>
  );
}
