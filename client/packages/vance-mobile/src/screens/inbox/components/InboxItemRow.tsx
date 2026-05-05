import { Pressable, Text, View } from 'react-native';
import { InboxItemType, type InboxItemDto } from '@vance/generated';
import { CriticalityBadge } from './CriticalityBadge';
import { relativeTime } from '@/util/format';

interface Props {
  item: InboxItemDto;
  onPress: () => void;
}

const typeLabel: Record<InboxItemType, string> = {
  [InboxItemType.APPROVAL]: 'approval',
  [InboxItemType.DECISION]: 'decision',
  [InboxItemType.FEEDBACK]: 'feedback',
  [InboxItemType.ORDERING]: 'ordering',
  [InboxItemType.STRUCTURE_EDIT]: 'structure',
  [InboxItemType.OUTPUT_TEXT]: 'text',
  [InboxItemType.OUTPUT_IMAGE]: 'image',
  [InboxItemType.OUTPUT_DOCUMENT]: 'document',
};

export function InboxItemRow({ item, onPress }: Props) {
  const subtitle = item.body ?? '';
  return (
    <Pressable
      onPress={onPress}
      className="px-4 py-3 border-b border-slate-100 dark:border-slate-900 active:bg-slate-50 dark:active:bg-slate-900"
    >
      <View className="flex-row items-center gap-2 mb-1">
        <CriticalityBadge criticality={item.criticality} />
        <Text className="text-xs text-slate-500 dark:text-slate-400 uppercase">
          {typeLabel[item.type]}
        </Text>
        <View className="flex-1" />
        <Text className="text-xs text-slate-500 dark:text-slate-500">
          {relativeTime(item.createdAt)}
        </Text>
      </View>
      <Text
        numberOfLines={1}
        className="text-base font-semibold text-slate-900 dark:text-slate-100"
      >
        {item.title}
      </Text>
      {subtitle ? (
        <Text
          numberOfLines={2}
          className="text-sm text-slate-600 dark:text-slate-400 mt-0.5"
        >
          {subtitle}
        </Text>
      ) : null}
    </Pressable>
  );
}
