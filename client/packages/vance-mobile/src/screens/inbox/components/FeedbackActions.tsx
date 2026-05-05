import { useState } from 'react';
import { View } from 'react-native';
import { AnswerOutcome, type InboxItemDto } from '@vance/generated';
import { VButton, VInput } from '@/components';
import { useAnswerInboxItem } from '@/hooks/useInbox';

interface Props {
  item: InboxItemDto;
  onAnswered: () => void;
}

/**
 * FEEDBACK action bar — multiline text input + Send. Empty string
 * sends nothing. The third-state outcomes (`Insufficient` /
 * `Undecidable`) are still available because the mobile spec
 * (`mobile-ui.md` §3) keeps every ASK type's three-state contract.
 */
export function FeedbackActions({ item, onAnswered }: Props) {
  const [text, setText] = useState('');
  const mutation = useAnswerInboxItem();

  function answer(outcome: AnswerOutcome) {
    const value =
      outcome === AnswerOutcome.DECIDED ? { text: text.trim() } : undefined;
    mutation.mutate(
      {
        id: item.id,
        body: {
          itemId: item.id,
          outcome,
          value,
        },
      },
      { onSuccess: onAnswered },
    );
  }

  const busy = mutation.isPending;
  const sendDisabled = busy || text.trim() === '';
  return (
    <View className="gap-2">
      <VInput
        value={text}
        onChangeText={setText}
        editable={!busy}
        multiline
        numberOfLines={4}
        textAlignVertical="top"
        autoCorrect
        autoCapitalize="sentences"
        placeholder="Your feedback…"
      />
      <VButton variant="primary" disabled={sendDisabled} loading={busy} onPress={() => answer(AnswerOutcome.DECIDED)}>
        Send feedback
      </VButton>
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
