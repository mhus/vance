<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  type BrainWsApi,
  WebSocketRequestError,
} from '@vance/shared';
import type {
  ProjectGroupSummary,
  ProjectSummary,
  SessionListRequest,
  SessionListResponse,
  SessionSummary,
  SessionBootstrapRequest,
  SessionBootstrapResponse,
} from '@vance/generated';
import { useTenantProjects } from '@composables/useTenantProjects';
import { VAlert, VButton, VEmptyState } from '@components/index';

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

const sessions = ref<SessionSummary[]>([]);
const selectedProjectName = ref<string | null>(null);
const sessionsLoading = ref(false);
const sessionsError = ref<string | null>(null);
const bootstrapping = ref(false);
const bootstrapError = ref<string | null>(null);

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
    const response = await props.socket.send<SessionListRequest, SessionListResponse>(
      'session-list', { projectId: projectName });
    const sorted = (response.sessions ?? []).slice().sort(
      (a, b) => b.lastActivityAt - a.lastActivityAt);
    sessions.value = sorted;
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

function pickSession(session: SessionSummary): void {
  if (session.bound) return;
  emit('session-picked', session.sessionId);
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

function formatRelativeTime(epochMillis: number): string {
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

onMounted(async () => {
  await loadProjects();
  if (!selectedProjectName.value && projects.value.length > 0) {
    selectedProjectName.value = projects.value[0].name;
  }
});

watch(selectedProjectName, async (newName) => {
  if (newName) await loadSessions(newName);
});
</script>

<template>
  <div class="flex h-full min-h-0">
    <!-- Sidebar: project groups → projects -->
    <aside class="w-72 shrink-0 border-r border-base-300 bg-base-100 overflow-y-auto p-4 flex flex-col gap-4">
      <div class="text-xs uppercase tracking-wide opacity-60 font-semibold">
        {{ $t('chat.picker.projectsTitle') }}
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
          <VButton
            variant="primary"
            :disabled="!selectedProjectName || bootstrapping"
            :loading="bootstrapping"
            @click="bootstrapNew"
          >
            {{ $t('chat.picker.newSession') }}
          </VButton>
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
            :class="[
              'card bg-base-100 shadow-sm border border-base-300',
              session.bound
                ? 'opacity-60 cursor-not-allowed'
                : 'cursor-pointer hover:border-primary',
            ]"
            @click="pickSession(session)"
          >
            <div class="card-body p-4 flex flex-row items-start gap-3">
              <span
                class="inline-block w-2.5 h-2.5 rounded-full shrink-0 mt-1.5"
                :class="session.bound ? 'bg-error' : 'bg-base-content/40'"
                :title="session.bound ? $t('chat.picker.occupiedTooltip') : $t('chat.picker.available')"
              />
              <div class="flex-1 min-w-0">
                <div
                  class="font-medium truncate"
                  :title="session.firstUserMessage ?? session.displayName ?? session.sessionId"
                >
                  {{ session.firstUserMessage || session.displayName || session.sessionId }}
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
              </div>
              <span v-if="session.bound" class="text-xs text-error shrink-0 mt-1">
                {{ $t('chat.picker.occupied') }}
              </span>
            </div>
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>
