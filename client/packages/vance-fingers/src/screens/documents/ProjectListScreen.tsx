import { useMemo, useState } from 'react';
import { FlatList, Pressable, RefreshControl, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { ProjectSummary } from '@vance/generated';
import { useTenantProjects } from '@/hooks/useProjects';
import { MobileShell, VAlert, VEmptyState, VLoading } from '@/components';
import { getSortPref, setSortPref } from '@/util/sortPref';
import { relativeTime } from '@/util/format';
import type { DocumentsStackParamList } from '@/navigation/types';

type Nav = NativeStackNavigationProp<DocumentsStackParamList, 'ProjectList'>;

type SortOrder = 'name' | 'newest';
const SORT_SCOPE = 'documents.projects';

export default function ProjectListScreen() {
  const nav = useNavigation<Nav>();
  const [sort, setSort] = useState<SortOrder>(() =>
    getSortPref<SortOrder>(SORT_SCOPE, 'name'),
  );
  const query = useTenantProjects();

  const items: ProjectSummary[] = useMemo(() => {
    const raw = query.data?.projects ?? [];
    const copy = raw.slice();
    if (sort === 'name') {
      copy.sort((a, b) => byTitle(a).localeCompare(byTitle(b)));
    } else {
      copy.sort((a, b) => (b.createdAtMs ?? 0) - (a.createdAtMs ?? 0));
    }
    return copy;
  }, [query.data, sort]);

  function pickSort(next: SortOrder) {
    setSort(next);
    setSortPref(SORT_SCOPE, next);
  }

  return (
    <MobileShell title="Documents">
      <SortToggle active={sort} onChange={pickSort} />
      {query.isLoading && !query.data ? (
        <VLoading variant="centered" label="Loading projects…" />
      ) : query.isError ? (
        <View className="px-4 py-3">
          <VAlert variant="error">Could not load projects — pull to retry.</VAlert>
        </View>
      ) : items.length === 0 ? (
        <VEmptyState
          icon={<Ionicons name="folder-open-outline" size={48} color="#94a3b8" />}
          headline="No projects"
          body="This tenant has no projects yet."
        />
      ) : (
        <FlatList
          data={items}
          keyExtractor={(p) => p.name}
          renderItem={({ item }) => (
            <ProjectRow
              item={item}
              onPress={() =>
                nav.navigate('DocumentList', {
                  projectId: item.name,
                  projectTitle: byTitle(item),
                })
              }
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

function byTitle(p: ProjectSummary): string {
  return p.title && p.title.length > 0 ? p.title : p.name;
}

interface ToggleProps {
  active: SortOrder;
  onChange: (s: SortOrder) => void;
}

function SortToggle({ active, onChange }: ToggleProps) {
  return (
    <View className="flex-row px-3 py-2 gap-2 border-b border-slate-100 dark:border-slate-900">
      <Pill label="A → Z" selected={active === 'name'} onPress={() => onChange('name')} />
      <Pill label="Newest" selected={active === 'newest'} onPress={() => onChange('newest')} />
    </View>
  );
}

function Pill({
  label,
  selected,
  onPress,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
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
        {label}
      </Text>
    </Pressable>
  );
}

function ProjectRow({ item, onPress }: { item: ProjectSummary; onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      className="flex-row items-center gap-3 px-4 py-3 border-b border-slate-100 dark:border-slate-900 active:bg-slate-50 dark:active:bg-slate-900"
    >
      <Ionicons name="folder" size={22} color="#64748b" />
      <View className="flex-1 min-w-0">
        <Text
          numberOfLines={1}
          className="text-base font-semibold text-slate-900 dark:text-slate-100"
        >
          {byTitle(item)}
        </Text>
        <View className="flex-row items-center gap-2 mt-0.5">
          {item.title ? (
            <Text className="text-xs text-slate-500 dark:text-slate-400">{item.name}</Text>
          ) : null}
          {item.createdAtMs !== undefined ? (
            <>
              {item.title ? (
                <Text className="text-xs text-slate-500 dark:text-slate-500">·</Text>
              ) : null}
              <Text className="text-xs text-slate-500 dark:text-slate-500">
                {relativeTime(item.createdAtMs)}
              </Text>
            </>
          ) : null}
        </View>
      </View>
      <Ionicons name="chevron-forward" size={18} color="#94a3b8" />
    </Pressable>
  );
}
