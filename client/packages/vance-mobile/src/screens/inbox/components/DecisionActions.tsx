import { View } from 'react-native';
import { AnswerOutcome, type InboxItemDto } from '@vance/generated';
import { VAlert, VButton } from '@/components';
import { useAnswerInboxItem } from '@/hooks/useInbox';

interface Props {
  item: InboxItemDto;
  onAnswered: () => void;
}

interface DecisionOption {
  key: string;
  label: string;
}

/**
 * DECISION action bar — caller's payload is expected to carry an
 * `options: { key, label }[]` array. Each option becomes a button;
 * the chosen `key` is sent as `value.selected`.
 *
 * If the payload shape is missing the array, fall back to a notice
 * pointing the user at the desktop UI rather than guessing.
 */
export function DecisionActions({ item, onAnswered }: Props) {
  const mutation = useAnswerInboxItem();
  const options = parseOptions(item.payload);

  function answer(outcome: AnswerOutcome, selected?: string) {
    mutation.mutate(
      {
        id: item.id,
        body: {
          itemId: item.id,
          outcome,
          value: selected === undefined ? undefined : { selected },
        },
      },
      { onSuccess: onAnswered },
    );
  }

  if (options === null) {
    return (
      <View className="gap-2">
        <VAlert variant="warning">
          This decision uses an option set the mobile app cannot render yet.
          Open the desktop UI to choose, or mark as undecidable below.
        </VAlert>
        <VButton
          variant="ghost"
          size="sm"
          onPress={() => answer(AnswerOutcome.UNDECIDABLE)}
        >
          Undecidable
        </VButton>
      </View>
    );
  }

  const busy = mutation.isPending;
  return (
    <View className="gap-2">
      {options.map((opt) => (
        <VButton
          key={opt.key}
          variant="primary"
          disabled={busy}
          onPress={() => answer(AnswerOutcome.DECIDED, opt.key)}
        >
          {opt.label}
        </VButton>
      ))}
      <View className="flex-row gap-2 mt-1">
        <View className="flex-1">
          <VButton
            variant="ghost"
            size="sm"
            disabled={busy}
            onPress={() => answer(AnswerOutcome.INSUFFICIENT_INFO)}
          >
            Insufficient info
          </VButton>
        </View>
        <View className="flex-1">
          <VButton
            variant="ghost"
            size="sm"
            disabled={busy}
            onPress={() => answer(AnswerOutcome.UNDECIDABLE)}
          >
            Undecidable
          </VButton>
        </View>
      </View>
    </View>
  );
}

function parseOptions(payload: Record<string, unknown> | undefined): DecisionOption[] | null {
  if (!payload || !Array.isArray(payload.options)) return null;
  const out: DecisionOption[] = [];
  for (const raw of payload.options) {
    if (typeof raw !== 'object' || raw === null) continue;
    const key = (raw as { key?: unknown }).key;
    const label = (raw as { label?: unknown }).label;
    if (typeof key !== 'string' || typeof label !== 'string') continue;
    out.push({ key, label });
  }
  return out.length > 0 ? out : null;
}
