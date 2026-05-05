import { ScrollView, Text, View } from 'react-native';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { InboxItemType } from '@vance/generated';
import { useInboxItem } from '@/hooks/useInbox';
import { MobileShell, VAlert, VLoading } from '@/components';
import { CriticalityBadge } from './components/CriticalityBadge';
import { InboxActionBar } from './components/InboxActionBar';
import { relativeTime } from '@/util/format';
import type { InboxStackParamList } from '@/navigation/types';

type Nav = NativeStackNavigationProp<InboxStackParamList, 'InboxDetail'>;
type Route = RouteProp<InboxStackParamList, 'InboxDetail'>;

const TYPE_LABEL: Record<InboxItemType, string> = {
  [InboxItemType.APPROVAL]: 'Approval',
  [InboxItemType.DECISION]: 'Decision',
  [InboxItemType.FEEDBACK]: 'Feedback',
  [InboxItemType.ORDERING]: 'Ordering',
  [InboxItemType.STRUCTURE_EDIT]: 'Structure edit',
  [InboxItemType.OUTPUT_TEXT]: 'Text output',
  [InboxItemType.OUTPUT_IMAGE]: 'Image output',
  [InboxItemType.OUTPUT_DOCUMENT]: 'Document output',
};

export default function InboxDetailScreen() {
  const nav = useNavigation<Nav>();
  const { id } = useRoute<Route>().params;
  const query = useInboxItem(id);

  function back() {
    nav.goBack();
  }

  return (
    <MobileShell title="Item" onBack={back}>
      {query.isLoading && !query.data ? (
        <VLoading variant="centered" label="Loading item…" />
      ) : query.isError || !query.data ? (
        <View className="px-4 py-3">
          <VAlert variant="error">
            Could not load this item.
          </VAlert>
        </View>
      ) : (
        <ScrollView contentContainerClassName="p-4 gap-4">
          <View className="gap-2">
            <View className="flex-row items-center gap-2">
              <CriticalityBadge criticality={query.data.criticality} />
              <Text className="text-xs text-slate-500 dark:text-slate-400 uppercase">
                {TYPE_LABEL[query.data.type]}
              </Text>
              <View className="flex-1" />
              <Text className="text-xs text-slate-500 dark:text-slate-500">
                {relativeTime(query.data.createdAt)}
              </Text>
            </View>
            <Text className="text-xl font-semibold text-slate-900 dark:text-slate-100">
              {query.data.title}
            </Text>
            {query.data.body ? (
              <Text className="text-base text-slate-700 dark:text-slate-300">
                {query.data.body}
              </Text>
            ) : null}
          </View>
          <View className="h-px bg-slate-200 dark:bg-slate-800" />
          <InboxActionBar item={query.data} onResolved={back} />
        </ScrollView>
      )}
    </MobileShell>
  );
}
