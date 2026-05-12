import { useMemo, useState } from 'react';
import { FlatList, Pressable, RefreshControl, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { InboxItemStatus, type InboxItemDto } from '@vance/generated';
import { useInboxList } from '@/hooks/useInbox';
import { MobileShell, VAlert, VEmptyState, VLoading } from '@/components';
import { InboxItemRow } from './components/InboxItemRow';
import type { InboxStackParamList } from '@/navigation/types';

type Nav = NativeStackNavigationProp<InboxStackParamList, 'InboxList'>;

const STATUS_ORDER = [
  InboxItemStatus.PENDING,
  InboxItemStatus.ANSWERED,
  InboxItemStatus.DISMISSED,
  InboxItemStatus.ARCHIVED,
] as const;

const STATUS_LABEL: Record<InboxItemStatus, string> = {
  [InboxItemStatus.PENDING]: 'Pending',
  [InboxItemStatus.ANSWERED]: 'Answered',
  [InboxItemStatus.DISMISSED]: 'Dismissed',
  [InboxItemStatus.ARCHIVED]: 'Archived',
};

export default function InboxListScreen() {
  const nav = useNavigation<Nav>();
  const [status, setStatus] = useState<InboxItemStatus>(InboxItemStatus.PENDING);
  const query = useInboxList({ status });

  const items: InboxItemDto[] = useMemo(() => query.data?.items ?? [], [query.data]);

  return (
    <MobileShell title="Inbox">
      <StatusFilter active={status} onChange={setStatus} />
      {query.isLoading && !query.data ? (
        <VLoading variant="centered" label="Loading inbox…" />
      ) : query.isError ? (
        <View className="px-4 py-3">
          <VAlert variant="error">
            Could not load inbox — pull to retry.
          </VAlert>
        </View>
      ) : items.length === 0 ? (
        <VEmptyState
          icon={<Ionicons name="checkmark-circle-outline" size={48} color="#94a3b8" />}
          headline="Nothing here"
          body={
            status === InboxItemStatus.PENDING
              ? 'No items waiting for your attention.'
              : `No ${STATUS_LABEL[status].toLowerCase()} items.`
          }
        />
      ) : (
        <FlatList
          data={items}
          keyExtractor={(item) => item.id}
          renderItem={({ item }) => (
            <InboxItemRow
              item={item}
              onPress={() => nav.navigate('InboxDetail', { id: item.id })}
            />
          )}
          refreshControl={
            <RefreshControl
              refreshing={query.isFetching && !query.isLoading}
              onRefresh={() => query.refetch()}
            />
          }
        />
      )}
    </MobileShell>
  );
}

interface FilterProps {
  active: InboxItemStatus;
  onChange: (s: InboxItemStatus) => void;
}

function StatusFilter({ active, onChange }: FilterProps) {
  return (
    <View className="flex-row px-3 py-2 gap-2 border-b border-slate-100 dark:border-slate-900">
      {STATUS_ORDER.map((s) => {
        const selected = s === active;
        return (
          <Pressable
            key={s}
            onPress={() => onChange(s)}
            className={`px-3 py-1.5 rounded-full ${
              selected
                ? 'bg-blue-600'
                : 'bg-slate-100 dark:bg-slate-800 active:bg-slate-200 dark:active:bg-slate-700'
            }`}
          >
            <Text
              className={`text-xs font-medium ${
                selected ? 'text-white' : 'text-slate-700 dark:text-slate-300'
              }`}
            >
              {STATUS_LABEL[s]}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}
