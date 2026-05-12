import { useEffect, useMemo, useState } from 'react';
import { FlatList, Pressable, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { ProjectSummary } from '@vance/generated';
import { useTenantProjects } from '@/hooks/useProjects';
import { useCreateSession } from '@/hooks/useCreateSession';
import { MobileShell, VAlert, VButton, VLoading } from '@/components';
import { isUserProject, getUserProjectName } from '@/util/userProject';
import type { ChatStackParamList } from '@/navigation/types';

type Nav = NativeStackNavigationProp<ChatStackParamList, 'NewSession'>;

/**
 * Pick a project, then start a new chat session in it. Defaults to
 * the user's home project (`_user_<username>`); falls back to the
 * first enabled project when the home project is absent (which
 * happens on legacy seed data).
 */
export default function NewSessionScreen() {
  const nav = useNavigation<Nav>();
  const projectsQuery = useTenantProjects();
  const create = useCreateSession();

  const projects: ProjectSummary[] = useMemo(() => {
    const all = projectsQuery.data?.projects ?? [];
    return all.filter((p) => p.enabled);
  }, [projectsQuery.data]);

  const [selected, setSelected] = useState<string | null>(null);

  // Pick a sensible default: the user's home project if it exists,
  // otherwise the first available project.
  useEffect(() => {
    if (selected !== null || projects.length === 0) return;
    const userPrj = getUserProjectName();
    const home = userPrj ? projects.find((p) => p.name === userPrj) : undefined;
    setSelected(home?.name ?? projects[0].name);
  }, [projects, selected]);

  async function onCreate() {
    if (selected === null) return;
    const resp = await create.create(selected);
    if (resp) {
      nav.replace('ChatLive', { sessionId: resp.sessionId });
    }
  }

  return (
    <MobileShell title="New session" onBack={() => nav.goBack()}>
      {projectsQuery.isLoading && !projectsQuery.data ? (
        <VLoading variant="centered" label="Loading projects…" />
      ) : projectsQuery.isError ? (
        <View className="px-4 py-3">
          <VAlert variant="error">Could not load projects.</VAlert>
        </View>
      ) : projects.length === 0 ? (
        <View className="px-4 py-3">
          <VAlert variant="warning">No active projects in this tenant.</VAlert>
        </View>
      ) : (
        <View className="flex-1">
          <Text className="px-4 pt-3 pb-2 text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
            Project
          </Text>
          <FlatList
            data={projects}
            keyExtractor={(p) => p.name}
            renderItem={({ item }) => (
              <ProjectOption
                item={item}
                selected={item.name === selected}
                onPress={() => setSelected(item.name)}
              />
            )}
          />
          <View className="p-4 gap-2 border-t border-slate-200 dark:border-slate-800">
            {create.error !== null ? (
              <VAlert variant="error">{create.error}</VAlert>
            ) : null}
            <VButton
              variant="primary"
              size="lg"
              loading={create.creating}
              disabled={create.creating || selected === null}
              onPress={onCreate}
            >
              Start chat
            </VButton>
          </View>
        </View>
      )}
    </MobileShell>
  );
}

function ProjectOption({
  item,
  selected,
  onPress,
}: {
  item: ProjectSummary;
  selected: boolean;
  onPress: () => void;
}) {
  const heading = item.title && item.title.length > 0 ? item.title : item.name;
  const isHome = isUserProject(item.name);
  return (
    <Pressable
      onPress={onPress}
      className={`flex-row items-center gap-3 px-4 py-3 border-b border-slate-100 dark:border-slate-900 ${
        selected ? 'bg-blue-50 dark:bg-blue-950' : 'active:bg-slate-50 dark:active:bg-slate-900'
      }`}
    >
      <Ionicons
        name={selected ? 'radio-button-on' : 'radio-button-off'}
        size={22}
        color={selected ? '#2563eb' : '#94a3b8'}
      />
      <View className="flex-1 min-w-0">
        <View className="flex-row items-center gap-2">
          <Text
            numberOfLines={1}
            className="text-base font-semibold text-slate-900 dark:text-slate-100"
          >
            {heading}
          </Text>
          {isHome ? (
            <Text className="text-xs text-blue-600 dark:text-blue-400">home</Text>
          ) : null}
        </View>
        {item.title ? (
          <Text className="text-xs text-slate-500 dark:text-slate-400" numberOfLines={1}>
            {item.name}
          </Text>
        ) : null}
      </View>
    </Pressable>
  );
}
