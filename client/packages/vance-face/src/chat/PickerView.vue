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
  type SessionBootstrapRequest,
  type SessionBootstrapResponse,
  type SessionSummaryRichDto,
} from '@vance/generated';
import { useTenantProjects } from '@composables/useTenantProjects';
import {
  ProjectListSidebar,
  VAlert,
  VButton,
  VCheckbox,
  VEmptyState,
  VInput,
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
  /** User clicked something in the project-list sidebar — focus the
   *  main sessions view so the picked project's sessions get the
   *  attention. */
  (event: 'focus-main'): void;
  /** User actively clicked a project button — distinct from a
   *  prop-driven selection sync. Parent uses this to push browser
   *  history (so back/forward steps between projects). */
  (event: 'project-pick', payload: { name: string; title: string }): void;
  /** Selected project's display title is now known (projects list
   *  finished loading, or selection changed). Parent uses this to
   *  update the topbar breadcrumb. */
  (event: 'project-resolved', payload: { name: string; title: string }): void;
}>();

/**
 * Two-way bound with ChatApp's {@code pickerProjectName} — keeps the
 * picker's selection in sync with the URL state. Writes from inside
 * (user clicks) propagate up; writes from outside (popstate) propagate
 * back down.
 */
const selectedProjectName = defineModel<string | null>('selectedProject', {
  default: null,
});

/**
 * The {@code Teleport} target div lives in ChatApp's EditorShell
 * sidebar slot. Both this component and the target render in the same
 * Vue mount pass, but during the very first render `document.getElementById`
 * may not see the target yet. We disable the Teleport for that first
 * tick and flip it on after mount.
 */
const teleportReady = ref(false);

const { groups, projects, loading: projectsLoading, error: projectsError, reload: loadProjects } =
  useTenantProjects();

const sessions = ref<SessionSummaryRichDto[]>([]);
const sessionsLoading = ref(false);
const sessionsError = ref<string | null>(null);
const bootstrapping = ref(false);
const bootstrapError = ref<string | null>(null);
const showArchived = ref(false);
const reactivating = ref<string | null>(null);
const searchOpen = ref(false);

/**
 * Free-text filter for the sessions list in the main area — matches
 * against the displayed session title (case-insensitive substring).
 */
const sessionFilter = ref('');

/**
 * Narrow-viewport: the session-filter / archived-toggle / new-session
 * cluster collapses into a single {@code ⋯} button on phones. Toggled
 * open by tapping the button.
 */
const pickerToolsOpen = ref(false);

const filteredSessions = computed<SessionSummaryRichDto[]>(() => {
  const needle = sessionFilter.value.trim().toLowerCase();
  if (!needle) return sessions.value;
  return sessions.value.filter((s) =>
    sessionTitle(s).toLowerCase().includes(needle));
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

/** Forwarded from {@link ProjectListSidebar} — wraps the v-model
 *  write with the URL-history emit so back/forward steps between
 *  projects (mirrors the old in-PickerView {@code selectProject}). */
function onProjectPick(payload: { name: string; title: string }): void {
  emit('project-pick', payload);
}

/** {@link ProjectListSidebar} created a new group or project.
 *  Reload {@code useTenantProjects} so the new entry shows up;
 *  for projects, jump straight into the new workspace. */
async function onProjectListDataChanged(
  payload: { kind: 'group' | 'project'; name: string },
): Promise<void> {
  await loadProjects();
  if (payload.kind === 'project') {
    selectedProjectName.value = payload.name;
    emit('project-pick', {
      name: payload.name,
      title: projectTitle(payload.name),
    });
  }
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
  // Enable the project-list Teleport on the next tick — after the
  // first render flush so ChatApp's sidebar-slot target div is in DOM.
  teleportReady.value = true;
  await loadProjects();
  // Title resolution can only happen once {@link projects} has loaded.
  // Emit resolution for whatever selection arrived via the URL-driven
  // v-model so ChatApp can populate the breadcrumb on first paint.
  if (selectedProjectName.value && projects.value.length > 0) {
    emit('project-resolved', {
      name: selectedProjectName.value,
      title: projectTitle(selectedProjectName.value),
    });
  }
});

// Title resolution on any subsequent change (e.g. URL-driven popstate
// switching to a different project). Catches the case where the
// selection changes after the projects list is already loaded.
watch(selectedProjectName, (name) => {
  if (name && projects.value.length > 0) {
    emit('project-resolved', { name, title: projectTitle(name) });
  }
});

// {@code immediate} ensures sessions also load when the picker mounts
// with a pre-set selection (e.g. after leaveLive from a chat session,
// or on a fresh page load with {@code ?project=}). Without it, the
// watcher only catches subsequent changes and the sessions list stays
// empty until the user re-picks the same project.
watch(selectedProjectName, async (newName) => {
  if (newName) await loadSessions(newName);
}, { immediate: true });

watch(showArchived, async () => {
  if (selectedProjectName.value) await loadSessions(selectedProjectName.value);
});
</script>

<template>
  <div class="h-full min-h-0 flex flex-col">
    <!--
      Project list teleports into the EditorShell sidebar zone via
      ChatApp's #sidebar slot target. Outer width/border/background
      styling lives on the zone-sidebar aside in EditorShell; here we
      only set inner padding + flex layout. Click handlers on project
      buttons emit `focus-main` (with @pointerdown.stop) so the focus
      jumps to the sessions view as soon as a project is picked.
    -->
    <Teleport to="#vance-picker-projects-target" :disabled="!teleportReady">
      <ProjectListSidebar
        v-model:selected-project="selectedProjectName"
        :groups="groups"
        :projects="projects"
        :loading="projectsLoading"
        :error="projectsError"
        :heading="$t('chat.picker.projectsTitle')"
        :filter-placeholder="$t('chat.picker.filterPlaceholder')"
        :ungrouped-label="$t('chat.picker.ungrouped')"
        :empty-headline="$t('chat.picker.noProjects')"
        :empty-body="$t('chat.picker.noProjectsBody')"
        edit-enabled
        @project-pick="onProjectPick"
        @focus-main="emit('focus-main')"
        @data-changed="onProjectListDataChanged"
      >
        <template #header-extra>
          <VButton
            variant="ghost"
            size="sm"
            :title="$t('chat.picker.searchTooltip')"
            @pointerdown.stop="emit('focus-main')"
            @click="searchOpen = true"
          >
            🔍
          </VButton>
        </template>
        <template #loading>
          {{ $t('chat.picker.loading') }}
        </template>
        <template #filter-no-match="{ filter }">
          {{ $t('chat.picker.filterNoMatch', { filter }) }}
        </template>
      </ProjectListSidebar>
    </Teleport>

    <!-- Main: sessions of selected project -->
    <section class="flex-1 min-w-0 min-h-0 flex flex-col">
      <!-- Full-width header: project info + tool cluster sit in the
           same row when there's room (flex-wrap drops the cluster to
           the next line on medium widths; the {@code ⋯} toggle hides
           the cluster entirely on phones — see scoped style below). -->
      <div class="px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-3 relative">
        <!-- Project info — takes the available space; title truncates
             on overflow. The technical name is shown only on viewports
             with room for it (≥ sm). On phone-narrow, only the title
             plus the {@code ⋯} toggle share the row. -->
        <div v-if="selectedProjectName" class="flex-1 min-w-0 flex items-baseline gap-2">
          <h2 class="text-lg font-semibold truncate">{{ projectTitle(selectedProjectName) }}</h2>
          <span class="hidden sm:inline text-sm opacity-50 font-mono truncate">{{ selectedProjectName }}</span>
        </div>
        <h2 v-else class="flex-1 min-w-0 text-lg font-semibold">
          {{ $t('chat.picker.pickAProject') }}
        </h2>

        <!-- Phone-only menu toggle. CSS in scoped block hides it on
             wider screens and turns .picker-tools into a popup. -->
        <VButton
          variant="ghost"
          size="sm"
          class="picker-tools-toggle"
          :title="pickerToolsOpen ? 'Hide tools' : 'Show tools'"
          @click="pickerToolsOpen = !pickerToolsOpen"
        >
          ⋯
        </VButton>

        <div
          class="picker-tools"
          :class="{ 'picker-tools--open': pickerToolsOpen }"
        >
          <div class="w-[150px]">
            <VInput
              v-model="sessionFilter"
              :placeholder="$t('chat.picker.sessionFilterPlaceholder')"
            />
          </div>
          <VCheckbox
            v-model="showArchived"
            :label="$t('chat.picker.showArchived')"
          />
          <VButton
            variant="primary"
            :disabled="!selectedProjectName || bootstrapping"
            :loading="bootstrapping"
            :title="$t('chat.picker.newSession')"
            @click="bootstrapNew"
          >
            +
          </VButton>
        </div>
      </div>

      <!-- Scrollable list area — content is centered/constrained for
           readability while the header above stays full-width. -->
      <div class="flex-1 min-h-0 overflow-y-auto px-6 py-4">
        <div class="max-w-3xl mx-auto flex flex-col gap-4">
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

        <div
          v-else-if="sessions.length > 0 && filteredSessions.length === 0"
          class="text-sm opacity-60"
        >
          {{ $t('chat.picker.sessionFilterNoMatch', { filter: sessionFilter }) }}
        </div>

        <ul v-else class="flex flex-col gap-2">
          <li
            v-for="session in filteredSessions"
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
      </div>
    </section>

    <SessionSearchModal
      v-if="searchOpen"
      @close="searchOpen = false"
      @pick="onSearchPick"
    />
  </div>
</template>

<style scoped>
/* On wide-enough viewports the session-filter / archived-toggle /
 * new-session cluster sits inline next to the project title. On
 * phones (≤ 640px) it collapses behind a {@code ⋯} button and the
 * cluster reappears as a popup anchored to the header's bottom-right. */

.picker-tools-toggle {
  display: none;
}
.picker-tools {
  display: flex;
  gap: 0.75rem;
  align-items: center;
}

@media (max-width: 640px) {
  .picker-tools-toggle {
    display: inline-flex;
  }
  .picker-tools {
    display: none;
  }
  .picker-tools.picker-tools--open {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 0.75rem;
    position: absolute;
    top: 100%;
    right: 0.5rem;
    z-index: 20;
    padding: 0.75rem;
    background-color: var(--fallback-b1, oklch(var(--b1) / 1));
    border: 1px solid var(--fallback-b3, oklch(var(--b3) / 1));
    border-radius: 0.5rem;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  }
}
</style>
