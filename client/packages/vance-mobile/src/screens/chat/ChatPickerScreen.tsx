import { useMemo, useState } from 'react';
import {
  Pressable,
  RefreshControl,
  SectionList,
  Text,
  View,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { ProjectSummary, SessionSummary } from '@vance/generated';
import { useChatSessions } from '@/hooks/useChatSessions';
import { useTenantProjects } from '@/hooks/useProjects';
import { MobileShell, SignOutButton, VAlert, VEmptyState, VLoading } from '@/components';
import { relativeTime } from '@/util/format';
import { isUserProject } from '@/util/userProject';
import type { ChatStackParamList } from '@/navigation/types';

type Nav = NativeStackNavigationProp<ChatStackParamList, 'ChatPicker'>;

interface ProjectSection {
  projectId: string;
  projectTitle: string;
  isHome: boolean;
  data: SessionSummary[];
}

export default function ChatPickerScreen() {
  const nav = useNavigation<Nav>();
  const sessions = useChatSessions();
  const projects = useTenantProjects();
  const [showAllProjects, setShowAllProjects] = useState(false);
  const [showInUse, setShowInUse] = useState(false);

  // Build a lookup so we can render project titles in the section
  // headers and sort by them. Projects without a session don't show
  // up — Sessions are the primary axis.
  const projectInfo = useMemo(() => {
    const map = new Map<string, { title: string; isHome: boolean }>();
    for (const p of projects.data?.projects ?? []) {
      const title = p.title && p.title.length > 0 ? p.title : p.name;
      map.set(p.name, { title, isHome: isUserProject(p.name) });
    }
    return map;
  }, [projects.data]);

  const sections: ProjectSection[] = useMemo(() => {
    const grouped = new Map<string, SessionSummary[]>();
    for (const s of sessions.sessions) {
      if (!showInUse && s.bound) continue;
      const key = s.projectId;
      const list = grouped.get(key) ?? [];
      list.push(s);
      grouped.set(key, list);
    }
    const out: ProjectSection[] = [];
    for (const [projectId, list] of grouped) {
      const info = projectInfo.get(projectId);
      const isHome = info?.isHome ?? isUserProject(projectId);
      if (!showAllProjects && !isHome) continue;
      list.sort((a, b) => b.lastActivityAt - a.lastActivityAt);
      out.push({
        projectId,
        projectTitle: info?.title ?? projectId,
        isHome,
        data: list,
      });
    }
    // Home project first, then alphabetical by title.
    out.sort((a, b) => {
      if (a.isHome !== b.isHome) return a.isHome ? -1 : 1;
      return a.projectTitle.localeCompare(b.projectTitle);
    });
    return out;
  }, [sessions.sessions, projectInfo, showAllProjects, showInUse]);

  const loading = sessions.loading || (projects.isLoading && !projects.data);
  const error = sessions.error;

  return (
    <MobileShell
      title="Chat"
      trailing={
        <View className="flex-row items-center gap-1">
          <Pressable
            onPress={() => nav.navigate('NewSession')}
            hitSlop={8}
            className="p-2"
            accessibilityRole="button"
            accessibilityLabel="New session"
          >
            <Ionicons name="add-circle-outline" size={24} color="#2563eb" />
          </Pressable>
          <SignOutButton />
        </View>
      }
    >
      <FilterBar
        showAllProjects={showAllProjects}
        showInUse={showInUse}
        onToggleScope={() => setShowAllProjects((v) => !v)}
        onToggleInUse={() => setShowInUse((v) => !v)}
      />

      {loading && sessions.sessions.length === 0 ? (
        <VLoading variant="centered" label="Loading sessions…" />
      ) : error !== null ? (
        <View className="px-4 py-3">
          <VAlert variant="error">{error}</VAlert>
        </View>
      ) : sections.length === 0 ? (
        <VEmptyState
          icon={<Ionicons name="chatbubbles-outline" size={48} color="#94a3b8" />}
          headline="No sessions"
          body={
            showAllProjects
              ? 'Tap + to start a new chat.'
              : 'No sessions in your home project. Toggle "All projects" or tap + to start one.'
          }
        />
      ) : (
        <SectionList
          sections={sections}
          keyExtractor={(item) => item.sessionId}
          stickySectionHeadersEnabled
          renderSectionHeader={({ section }) => (
            <ProjectHeader title={section.projectTitle} isHome={section.isHome} />
          )}
          renderItem={({ item }) => (
            <SessionRow
              project={projectInfo.get(item.projectId)}
              item={item}
              onPress={() =>
                item.bound
                  ? undefined
                  : nav.navigate('ChatLive', { sessionId: item.sessionId })
              }
            />
          )}
          refreshControl={
            <RefreshControl refreshing={sessions.loading} onRefresh={sessions.refresh} />
          }
        />
      )}
    </MobileShell>
  );
}

interface FilterProps {
  showAllProjects: boolean;
  showInUse: boolean;
  onToggleScope: () => void;
  onToggleInUse: () => void;
}

function FilterBar({ showAllProjects, showInUse, onToggleScope, onToggleInUse }: FilterProps) {
  return (
    <View className="flex-row px-3 py-2 gap-2 border-b border-slate-100 dark:border-slate-900">
      <Pill
        label={showAllProjects ? 'All projects' : 'Home only'}
        selected={showAllProjects}
        onPress={onToggleScope}
      />
      <Pill
        label={showInUse ? 'Incl. in-use' : 'Available only'}
        selected={showInUse}
        onPress={onToggleInUse}
      />
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

function ProjectHeader({ title, isHome }: { title: string; isHome: boolean }) {
  return (
    <View className="flex-row items-center gap-2 px-4 py-2 bg-slate-100 dark:bg-slate-900 border-b border-slate-200 dark:border-slate-800">
      <Ionicons name="folder" size={14} color="#475569" />
      <Text className="text-xs font-semibold uppercase tracking-wide text-slate-700 dark:text-slate-300">
        {title}
      </Text>
      {isHome ? (
        <Text className="text-xs text-blue-600 dark:text-blue-400">home</Text>
      ) : null}
    </View>
  );
}

function SessionRow({
  item,
  project: _project,
  onPress,
}: {
  item: SessionSummary;
  project: { title: string; isHome: boolean } | undefined;
  onPress: () => void;
}) {
  const heading =
    (item.firstUserMessage && item.firstUserMessage.trim()) ||
    (item.displayName && item.displayName.trim()) ||
    item.sessionId;
  const subtitle = item.lastMessagePreview ?? '';
  return (
    <Pressable
      onPress={item.bound ? undefined : onPress}
      disabled={item.bound}
      className={`px-4 py-3 border-b border-slate-100 dark:border-slate-900 ${
        item.bound ? 'opacity-60' : 'active:bg-slate-50 dark:active:bg-slate-900'
      }`}
    >
      <Text
        numberOfLines={2}
        className="text-base font-semibold text-slate-900 dark:text-slate-100"
      >
        {heading}
      </Text>
      {subtitle ? (
        <Text
          numberOfLines={1}
          className="text-sm text-slate-600 dark:text-slate-400 mt-0.5"
        >
          {subtitle}
        </Text>
      ) : null}
      <View className="flex-row items-center gap-2 mt-1">
        <Text className="text-xs text-slate-500 dark:text-slate-500">
          {relativeTime(item.lastActivityAt)}
        </Text>
        {item.bound ? (
          <Text className="text-xs text-amber-600 dark:text-amber-400">· in use</Text>
        ) : null}
      </View>
    </Pressable>
  );
}
