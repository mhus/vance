<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  type BrainWsApi,
  WebSocketRequestError,
  listSessions,
  reactivateSession,
} from '@vance/shared';
import {
  SessionColor,
  SessionStatus,
  type ProjectGroupSummary,
  type ProjectSummary,
  type SessionBootstrapRequest,
  type SessionBootstrapResponse,
  type SessionSummaryRichDto,
} from '@vance/generated';
import { useTenantProjects } from '@composables/useTenantProjects';
import {
  VAlert,
  VButton,
  VCheckbox,
  VEmptyState,
} from '@components/index';
import SessionSearchModal from './SessionSearchModal.vue';

const { t } = useI18n();

const props = defineProps<{
  socket: BrainWsApi;
  username: string | null;
}>();

const emit = defineEmits<{
  (event: 'session-picked', sessionId: string): void;
  (event: 'session-bootstrapped', sessionId: string): void;
}>();

const { groups, projects, loading: projectsLoading, error: projectsError, reload: loadProjects } =
  useTenantProjects();

const sessions = ref<SessionSummaryRichDto[]>([]);
const selectedProjectName = ref<string | null>(null);
const sessionsLoading = ref(false);
const sessionsError = ref<string | null>(null);
const bootstrapping = ref(false);
const bootstrapError = ref<string | null>(null);
const showArchived = ref(false);
const reactivating = ref<string | null>(null);
const searchOpen = ref(false);

interface GroupBlock {
  group: ProjectGroupSummary | null;
  projects: ProjectSummary[];
}

const projectsByGroup = computed<GroupBlock[]>(() => {
  const byKey = new Map<string | null, ProjectSummary[]>();
  for (const p of projects.value) {
    const key = p.projectGroupId ?? null;
    const list = byKey.get(key) ?? [];
    list.push(p);
    byKey.set(key, list);
  }
  const groupByName = new Map(groups.value.map((g) => [g.name, g] as const));
  const result: GroupBlock[] = [];
  for (const [groupName, list] of byKey.entries()) {
    const group = groupName ? groupByName.get(groupName) ?? null : null;
    result.push({ group, projects: list });
  }
  // Stable order: ungrouped first, then groups by name.
  result.sort((a, b) => {
    if (a.group === null && b.group !== null) return -1;
    if (a.group !== null && b.group === null) return 1;
    if (!a.group || !b.group) return 0;
    return a.group.name.localeCompare(b.group.name);
  });
  return result;
});

async function loadSessions(projectName: string): Promise<void> {
  sessionsLoading.value = true;
  sessionsError.value = null;
  try {
    // REST list is owner-scoped + sorted by pinned, lastActivityAt desc.
    sessions.value = await listSessions({
      projectId: projectName,
      includeArchived: showArchived.value,
    });
  } catch (e) {
    sessionsError.value = describeError(e, t('chat.picker.failedToLoadSessions'));
    sessions.value = [];
  } finally {
    sessionsLoading.value = false;
  }
}

function selectProject(projectName: string): void {
  selectedProjectName.value = projectName;
}

function pickSession(session: SessionSummaryRichDto): void {
  if (session.bound) return;
  if (session.status === SessionStatus.ARCHIVED) return;
  emit('session-picked', session.sessionId);
}

async function reactivateAndOpen(session: SessionSummaryRichDto): Promise<void> {
  if (!window.confirm(t('chat.sessionHeader.reactivateConfirm'))) return;
  reactivating.value = session.sessionId;
  try {
    await reactivateSession(session.sessionId);
    emit('session-picked', session.sessionId);
  } catch (e) {
    sessionsError.value = describeError(e, t('chat.picker.failedToLoadSessions'));
  } finally {
    reactivating.value = null;
  }
}

async function bootstrapNew(): Promise<void> {
  if (!selectedProjectName.value) return;
  bootstrapping.value = true;
  bootstrapError.value = null;
  try {
    const response = await props.socket.send<SessionBootstrapRequest, SessionBootstrapResponse>(
      'session-bootstrap',
      { projectId: selectedProjectName.value, processes: [] });
    emit('session-bootstrapped', response.sessionId);
  } catch (e) {
    bootstrapError.value = describeError(e, t('chat.picker.failedToStartSession'));
  } finally {
    bootstrapping.value = false;
  }
}

function describeError(e: unknown, fallback: string): string {
  if (e instanceof WebSocketRequestError) {
    return `${e.message} (code ${e.errorCode})`;
  }
  return e instanceof Error ? e.message : fallback;
}

function toEpochMillis(d: Date | string | number | undefined): number {
  if (d === undefined || d === null) return 0;
  if (d instanceof Date) return d.getTime();
  if (typeof d === 'number') return d;
  const parsed = new Date(d).getTime();
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatRelativeTime(value: Date | string | number | undefined): string {
  const epochMillis = toEpochMillis(value);
  if (!epochMillis) return '';
  const diffMs = Date.now() - epochMillis;
  const seconds = Math.floor(diffMs / 1000);
  if (seconds < 60) return t('chat.picker.relativeJustNow');
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return t('chat.picker.relativeMinutes', { n: minutes });
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return t('chat.picker.relativeHours', { n: hours });
  const days = Math.floor(hours / 24);
  if (days < 7) return t('chat.picker.relativeDays', { n: days });
  return new Date(epochMillis).toLocaleDateString();
}

function projectTitle(name: string): string {
  const p = projects.value.find((x) => x.name === name);
  return p?.title || p?.name || name;
}

function groupLabel(block: GroupBlock): string {
  if (!block.group) return t('chat.picker.ungrouped');
  return block.group.title || block.group.name;
}

function sessionTitle(session: SessionSummaryRichDto): string {
  if (session.title && session.title.trim().length > 0) return session.title;
  if (session.firstUserMessage && session.firstUserMessage.trim().length > 0) {
    return session.firstUserMessage;
  }
  return t('chat.sessionHeader.untitled');
}

const COLOR_BORDER: Record<SessionColor, string> = {
  [SessionColor.SLATE]: 'border-l-slate-500',
  [SessionColor.RED]: 'border-l-red-500',
  [SessionColor.ORANGE]: 'border-l-orange-500',
  [SessionColor.AMBER]: 'border-l-amber-500',
  [SessionColor.GREEN]: 'border-l-green-500',
  [SessionColor.TEAL]: 'border-l-teal-500',
  [SessionColor.CYAN]: 'border-l-cyan-500',
  [SessionColor.BLUE]: 'border-l-blue-500',
  [SessionColor.INDIGO]: 'border-l-indigo-500',
  [SessionColor.PURPLE]: 'border-l-purple-500',
  [SessionColor.PINK]: 'border-l-pink-500',
  [SessionColor.ROSE]: 'border-l-rose-500',
};

function colorBorderClass(session: SessionSummaryRichDto): string {
  if (session.color === undefined) return 'border-l-base-300';
  return COLOR_BORDER[session.color] ?? 'border-l-base-300';
}

function onSearchPick(sessionId: string): void {
  searchOpen.value = false;
  emit('session-picked', sessionId);
}

onMounted(async () => {
  await loadProjects();
  if (!selectedProjectName.value && projects.value.length > 0) {
    selectedProjectName.value = projects.value[0].name;
  }
});

watch(selectedProjectName, async (newName) => {
  if (newName) await loadSessions(newName);
});

watch(showArchived, async () => {
  if (selectedProjectName.value) await loadSessions(selectedProjectName.value);
});
</script>

<template>
  <div class="flex h-full min-h-0">
    <!-- Sidebar: project groups → projects -->
    <aside class="w-72 shrink-0 border-r border-base-300 bg-base-100 overflow-y-auto p-4 flex flex-col gap-4">
      <div class="flex items-center justify-between">
        <div class="text-xs uppercase tracking-wide opacity-60 font-semibold">
          {{ $t('chat.picker.projectsTitle') }}
        </div>
        <VButton
          variant="ghost"
          size="sm"
          :title="$t('chat.picker.searchTooltip')"
          @click="searchOpen = true"
        >
          🔍
        </VButton>
      </div>

      <div v-if="projectsLoading" class="text-sm opacity-60">
        {{ $t('chat.picker.loading') }}
      </div>

      <VAlert v-else-if="projectsError" variant="error">
        {{ projectsError }}
      </VAlert>

      <template v-else>
        <div
          v-for="block in projectsByGroup"
          :key="block.group?.name ?? '_ungrouped'"
          class="flex flex-col gap-1"
        >
          <div class="text-xs opacity-50 px-2">{{ groupLabel(block) }}</div>
          <button
            v-for="p in block.projects"
            :key="p.name"
            type="button"
            class="text-left px-2 py-1.5 rounded text-sm transition-colors"
            :class="selectedProjectName === p.name
              ? 'bg-primary/10 text-primary font-medium'
              : 'hover:bg-base-200'"
            @click="selectProject(p.name)"
          >
            {{ p.title || p.name }}
          </button>
        </div>

        <VEmptyState
          v-if="projects.length === 0"
          :headline="$t('chat.picker.noProjects')"
          :body="$t('chat.picker.noProjectsBody')"
        />
      </template>
    </aside>

    <!-- Main: sessions of selected project -->
    <section class="flex-1 min-w-0 overflow-y-auto p-6">
      <div class="max-w-3xl mx-auto flex flex-col gap-4">
        <div class="flex items-baseline justify-between">
          <div>
            <h2 class="text-lg font-semibold">
              {{ selectedProjectName ? projectTitle(selectedProjectName) : $t('chat.picker.pickAProject') }}
            </h2>
            <p class="text-sm opacity-60">
              <template v-if="username">
                {{ $t('chat.picker.signedInAs', { username }) }}
              </template>
            </p>
          </div>
          <div class="flex items-center gap-3">
            <VCheckbox
              v-model="showArchived"
              :label="$t('chat.picker.showArchived')"
            />
            <VButton
              variant="primary"
              :disabled="!selectedProjectName || bootstrapping"
              :loading="bootstrapping"
              @click="bootstrapNew"
            >
              {{ $t('chat.picker.newSession') }}
            </VButton>
          </div>
        </div>

        <VAlert v-if="bootstrapError" variant="error">{{ bootstrapError }}</VAlert>
        <VAlert v-if="sessionsError" variant="error">{{ sessionsError }}</VAlert>

        <div v-if="sessionsLoading" class="text-sm opacity-60">
          {{ $t('chat.picker.sessionsLoading') }}
        </div>

        <VEmptyState
          v-else-if="!sessionsLoading && sessions.length === 0 && selectedProjectName"
          :headline="$t('chat.picker.noSessions')"
          :body="$t('chat.picker.noSessionsBody')"
        />

        <ul v-else class="flex flex-col gap-2">
          <li
            v-for="session in sessions"
            :key="session.sessionId"
            class="card bg-base-100 shadow-sm border border-base-300 border-l-4"
            :class="[
              colorBorderClass(session),
              session.bound
                ? 'opacity-60'
                : '',
              session.status !== SessionStatus.ARCHIVED && !session.bound
                ? 'hover:border-primary cursor-pointer'
                : '',
              session.status === SessionStatus.ARCHIVED ? 'bg-base-200/40' : '',
            ]"
            @click="pickSession(session)"
          >
            <div class="card-body p-4 flex flex-row items-start gap-3">
              <div class="text-2xl leading-none shrink-0 mt-0.5 w-8 text-center">
                <span v-if="session.icon">{{ session.icon }}</span>
                <span v-else class="opacity-30">💬</span>
              </div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2 min-w-0">
                  <span
                    v-if="session.pinned"
                    class="shrink-0 text-xs"
                    :title="$t('chat.sessionHeader.pinTooltip')"
                  >📌</span>
                  <span
                    class="font-medium truncate"
                    :title="sessionTitle(session)"
                  >
                    {{ sessionTitle(session) }}
                  </span>
                  <span
                    v-if="session.titleAutoGenerated && session.title"
                    class="text-[10px] uppercase tracking-wide px-1 py-0.5 rounded bg-base-200 opacity-60 shrink-0"
                    :title="$t('chat.sessionHeader.autoTitle')"
                  >AI</span>
                  <span
                    v-if="session.status === SessionStatus.ARCHIVED"
                    class="text-xs uppercase tracking-wide px-1.5 py-0.5 rounded bg-warning/15 text-warning border border-warning/30 shrink-0"
                  >
                    {{ $t('chat.sessionHeader.archived') }}
                  </span>
                </div>
                <div
                  v-if="session.lastMessagePreview"
                  class="text-xs opacity-70 truncate mt-0.5"
                  :title="session.lastMessagePreview"
                >
                  {{ session.lastMessagePreview }}
                </div>
                <div class="text-xs opacity-60 truncate mt-0.5">
                  {{ session.status }} · {{ formatRelativeTime(session.lastActivityAt) }}
                </div>
                <div
                  v-if="session.tags && session.tags.length > 0"
                  class="flex flex-wrap gap-1 mt-2"
                >
                  <span
                    v-for="tag in session.tags"
                    :key="tag"
                    class="text-[10px] px-1.5 py-0.5 rounded bg-base-200"
                  >
                    {{ tag }}
                  </span>
                </div>
              </div>
              <div class="shrink-0 flex flex-col items-end gap-1">
                <span v-if="session.bound" class="text-xs text-error">
                  {{ $t('chat.picker.occupied') }}
                </span>
                <VButton
                  v-if="session.status === SessionStatus.ARCHIVED"
                  variant="primary"
                  size="sm"
                  :disabled="reactivating === session.sessionId"
                  @click.stop="reactivateAndOpen(session)"
                >
                  {{ $t('chat.sessionHeader.reactivate') }}
                </VButton>
              </div>
            </div>
          </li>
        </ul>
      </div>
    </section>

    <SessionSearchModal
      v-if="searchOpen"
      @close="searchOpen = false"
      @pick="onSearchPick"
    />
  </div>
</template>
