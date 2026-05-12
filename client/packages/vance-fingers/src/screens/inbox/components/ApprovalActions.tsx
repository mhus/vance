import { View } from 'react-native';
import { AnswerOutcome, type InboxItemDto } from '@vance/generated';
import { VButton } from '@/components';
import { useAnswerInboxItem } from '@/hooks/useInbox';

interface Props {
  item: InboxItemDto;
  onAnswered: () => void;
}

/**
 * APPROVAL action bar — Yes / No are both `DECIDED` outcomes; the
 * `value.accepted` flag is the actual decision. `Insufficient` and
 * `Undecidable` are the third-state escape hatches that the spec
 * (`user-interaction.md` §3) requires every ASK type to expose.
 */
export function ApprovalActions({ item, onAnswered }: Props) {
  const mutation = useAnswerInboxItem();

  function answer(outcome: AnswerOutcome, accepted?: boolean) {
    mutation.mutate(
      {
        id: item.id,
        body: {
          itemId: item.id,
          outcome,
          value: accepted === undefined ? undefined : { accepted },
        },
      },
      { onSuccess: onAnswered },
    );
  }

  const busy = mutation.isPending;
  return (
    <View className="gap-2">
      <View className="flex-row gap-2">
        <View className="flex-1">
          <VButton
            variant="primary"
            disabled={busy}
            loading={busy}
            onPress={() => answer(AnswerOutcome.DECIDED, true)}
          >
            Yes
          </VButton>
        </View>
        <View className="flex-1">
          <VButton
            variant="danger"
            disabled={busy}
            onPress={() => answer(AnswerOutcome.DECIDED, false)}
          >
            No
          </VButton>
        </View>
      </View>
      <View className="flex-row gap-2">
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
