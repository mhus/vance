<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  type BrainWsApi,
  WebSocketRequestError,
  listProjectRecipes,
  listSessions,
  reactivateSession,
} from '@vance/shared';
import {
  AccentColor,
  SessionStatus,
  type RecipeListedDto,
  type SessionBootstrapRequest,
  type SessionBootstrapResponse,
  type SessionGroupDto,
  type SessionSummaryRichDto,
} from '@vance/generated';
import { useTenantProjects } from '@composables/useTenantProjects';
import { useSessionGroups } from '@composables/useSessionGroups';
import { useSessionGroupCollapse } from '@composables/useSessionGroupCollapse';
import {
  ProjectListSidebar,
  SessionActionsMenu,
  VAlert,
  VButton,
  VCheckbox,
  VEmptyState,
  VInput,
  VModal,
} from '@components/index';
import SessionSearchModal from './SessionSearchModal.vue';
import SessionCropModal from './SessionCropModal.vue';

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

/** Modify/Crop modal — holds the session id whose memory is being edited. */
const cropOpen = ref(false);
const cropSessionId = ref<string | null>(null);

function openCrop(sessionId: string): void {
  cropSessionId.value = sessionId;
  cropOpen.value = true;
}

/**
 * Recipe picker — opens on the "+" button. {@code null} entry is the
 * always-present "Default" choice that triggers a bootstrap without
 * {@code chatRecipe}, falling back to the server's default-recipe
 * resolution.
 */
const recipeModalOpen = ref(false);
const recipeOptions = ref<RecipeListedDto[]>([]);
const recipesLoading = ref(false);
const recipesError = ref<string | null>(null);

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

// ─── Session groups (per-user, per-project; UI organisation only) ───
// See planning/session-groups.md. Groups carry the membership; a session's
// group is not on the session itself, so grouping is computed by joining
// filteredSessions against each group's sessionIds. Sessions keep their
// server-side pinned+lastActivity order inside a group.

const sessionGroupsState = useSessionGroups();
const hasGroups = computed(() => sessionGroupsState.groups.value.length > 0);

/** Collapse state keyed by group name (+ ungrouped sentinel), persisted
 *  per-user/per-project via me/ui-state/session-groups. */
const UNGROUPED_KEY = 'ungrouped';
const collapse = useSessionGroupCollapse(selectedProjectName);

function groupKey(g: SessionGroupDto | null): string {
  return g ? g.name : UNGROUPED_KEY;
}

function isCollapsed(g: SessionGroupDto | null): boolean {
  if (sessionFilter.value.trim()) return false; // never hide matches
  return collapse.has(groupKey(g));
}

function toggleCollapsed(g: SessionGroupDto | null): void {
  collapse.toggle(groupKey(g));
}

interface SessionBlock {
  group: SessionGroupDto | null;
  sessions: SessionSummaryRichDto[];
}

/**
 * Partition {@link filteredSessions} into group blocks (ordered by the
 * group's sortIndex) plus a trailing "ungrouped" block. A sessionId listed
 * in a group but absent from the current (filtered/archive-scoped) session
 * list is silently dropped. When there are no groups at all, a single
 * headerless block carries the flat list.
 */
const sessionBlocks = computed<SessionBlock[]>(() => {
  const groups = sessionGroupsState.groups.value;
  if (groups.length === 0) {
    return [{ group: null, sessions: filteredSessions.value }];
  }
  const byId = new Map<string, SessionSummaryRichDto>();
  for (const s of filteredSessions.value) byId.set(s.sessionId, s);

  const grouped = new Set<string>();
  const groupBlocks: SessionBlock[] = [];
  for (const g of groups) {
    const members: SessionSummaryRichDto[] = [];
    for (const sid of g.sessionIds) {
      const s = byId.get(sid);
      if (s) {
        members.push(s);
        grouped.add(sid);
      }
    }
    groupBlocks.push({ group: g, sessions: members });
  }
  const ungrouped = filteredSessions.value.filter((s) => !grouped.has(s.sessionId));
  // "Ungrouped" leads, named groups follow (by sortIndex).
  return [{ group: null, sessions: ungrouped }, ...groupBlocks];
});

// ─── Group reorder (arrow buttons — drag is reserved for sessions) ───

function groupIndex(g: SessionGroupDto): number {
  return sessionGroupsState.groups.value.findIndex((x) => x.name === g.name);
}

async function moveGroup(index: number, delta: number): Promise<void> {
  const project = selectedProjectName.value;
  if (!project) return;
  const names = sessionGroupsState.groups.value.map((g) => g.name);
  const target = index + delta;
  if (target < 0 || target >= names.length) return;
  [names[index], names[target]] = [names[target], names[index]];
  await sessionGroupsState.reorder(project, names);
}

// ─── Create group ───

const showCreateGroup = ref(false);
const newGroupName = ref('');
const newGroupTitle = ref('');

/** Mirror the create-project slug rule: server key is ^[a-z0-9][a-z0-9_-]*$. */
function slugifyGroupName(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^[^a-z0-9]+/, '')
    .replace(/[-_]+$/, '');
}

function openCreateGroup(): void {
  newGroupName.value = '';
  newGroupTitle.value = '';
  showCreateGroup.value = true;
}

async function submitCreateGroup(): Promise<void> {
  const project = selectedProjectName.value;
  if (!project) return;
  const raw = newGroupName.value.trim();
  const name = slugifyGroupName(raw);
  if (!name) return;
  const title = newGroupTitle.value.trim() || (raw !== name ? raw : null);
  try {
    await sessionGroupsState.create(project, name, title);
    showCreateGroup.value = false;
  } catch {
    /* sessionGroupsState.error surfaces in the alert */
  }
}

async function deleteGroup(g: SessionGroupDto): Promise<void> {
  const project = selectedProjectName.value;
  if (!project) return;
  if (!window.confirm(t('chat.picker.groups.confirmDelete', { name: g.title || g.name }))) return;
  try {
    await sessionGroupsState.remove(project, g.name);
  } catch {
    /* sessionGroupsState.error */
  }
}

// ─── Drag & drop: move a session into a group (or ungroup) ───

const draggingSessionId = ref<string | null>(null);
const dragHoverKey = ref<string | null>(null);

// Auto-scroll while dragging near the list's top/bottom edge — adapted
// from ProjectListSidebar so a drop target below the fold stays reachable.
const AUTOSCROLL_EDGE = 48;
const AUTOSCROLL_MAX = 16;
let scrollContainer: HTMLElement | null = null;
let scrollRaf: number | null = null;
let lastPointerY = 0;

function findScrollableAncestor(el: HTMLElement | null): HTMLElement | null {
  let cur = el;
  while (cur && cur !== document.body && cur !== document.documentElement) {
    const overflowY = window.getComputedStyle(cur).overflowY;
    if ((overflowY === 'auto' || overflowY === 'scroll')
        && cur.scrollHeight > cur.clientHeight) {
      return cur;
    }
    cur = cur.parentElement;
  }
  return null;
}

function onDocumentDragOver(ev: DragEvent): void {
  lastPointerY = ev.clientY;
}

function scrollLoop(): void {
  if (!scrollContainer || !draggingSessionId.value) {
    scrollRaf = null;
    return;
  }
  const rect = scrollContainer.getBoundingClientRect();
  const fromTop = lastPointerY - rect.top;
  const fromBottom = rect.bottom - lastPointerY;
  let delta = 0;
  if (fromTop >= 0 && fromTop < AUTOSCROLL_EDGE) {
    delta = -Math.max(2, Math.round((1 - fromTop / AUTOSCROLL_EDGE) * AUTOSCROLL_MAX));
  } else if (fromBottom >= 0 && fromBottom < AUTOSCROLL_EDGE) {
    delta = Math.max(2, Math.round((1 - fromBottom / AUTOSCROLL_EDGE) * AUTOSCROLL_MAX));
  }
  if (delta !== 0) scrollContainer.scrollTop += delta;
  scrollRaf = requestAnimationFrame(scrollLoop);
}

function startAutoScroll(originEl: HTMLElement | null): void {
  scrollContainer = findScrollableAncestor(originEl);
  if (!scrollContainer) return;
  document.addEventListener('dragover', onDocumentDragOver);
  if (scrollRaf === null) scrollRaf = requestAnimationFrame(scrollLoop);
}

function stopAutoScroll(): void {
  document.removeEventListener('dragover', onDocumentDragOver);
  if (scrollRaf !== null) {
    cancelAnimationFrame(scrollRaf);
    scrollRaf = null;
  }
  scrollContainer = null;
}

function onSessionDragStart(session: SessionSummaryRichDto, ev: DragEvent): void {
  if (!hasGroups.value) return;
  draggingSessionId.value = session.sessionId;
  if (ev.dataTransfer) {
    ev.dataTransfer.effectAllowed = 'move';
    ev.dataTransfer.setData('application/x-vance-session', session.sessionId);
  }
  lastPointerY = ev.clientY;
  startAutoScroll(ev.target as HTMLElement | null);
}

function onSessionDragEnd(): void {
  draggingSessionId.value = null;
  dragHoverKey.value = null;
  stopAutoScroll();
}

function onBlockDragOver(block: SessionBlock, ev: DragEvent): void {
  if (!draggingSessionId.value) return;
  ev.preventDefault();
  if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'move';
  dragHoverKey.value = groupKey(block.group);
}

function onBlockDragLeave(block: SessionBlock): void {
  if (dragHoverKey.value === groupKey(block.group)) dragHoverKey.value = null;
}

async function onBlockDrop(block: SessionBlock, ev: DragEvent): Promise<void> {
  ev.preventDefault();
  const project = selectedProjectName.value;
  const sessionId =
    draggingSessionId.value ?? ev.dataTransfer?.getData('application/x-vance-session') ?? '';
  draggingSessionId.value = null;
  dragHoverKey.value = null;
  stopAutoScroll();
  if (!project || !sessionId) return;

  const targetGroup = block.group ? block.group.name : null;
  // No-op if already in the target group.
  const current = sessionGroupsState.groups.value.find((g) => g.sessionIds.includes(sessionId));
  const currentName = current ? current.name : null;
  if (currentName === targetGroup) return;

  try {
    await sessionGroupsState.assign(project, sessionId, targetGroup);
  } catch {
    /* sessionGroupsState.error */
  }
}

/**
 * "Hard-blocked" = the user can't open this session at all in the
 * picker. Multi-user-aware: shared sessions are never hard-blocked
 * (they can always be joined), even when another participant currently
 * holds the bind.
 */
function isHardBlocked(session: SessionSummaryRichDto): boolean {
  if (!session.bound) return false;
  if (session.allowMultipleClients) return false;
  // Private + bound + not mine = can't take over.
  return props.username === null || session.userId !== props.username;
}

function pickSession(session: SessionSummaryRichDto): void {
  if (session.status === SessionStatus.ARCHIVED) return;
  // Multi-user routing — see planning/multi-user-sessions.md §2.5.
  //  - Bound + shared (allowMultipleClients): always joinable, no
  //    prompt. The owner already declared "anyone may join", we
  //    just attach as a secondary participant.
  //  - Bound + private + owner==me: another tab/device of mine has
  //    the session. Confirm the hijack so the user knows what they're
  //    about to do.
  //  - Bound + private + owner!=me: blocked (legacy "occupied" UX).
  if (session.bound && !session.allowMultipleClients) {
    const mine = props.username !== null && session.userId === props.username;
    if (!mine) return;
    if (!window.confirm(t('chat.picker.hijackConfirm'))) return;
  }
  emit('session-picked', session.sessionId);
}

/**
 * Re-fetch the current project's sessions after a list-menu action
 * (pin/color/multi-user/archive/delete via {@link SessionActionsMenu}).
 * A round-trip keeps the server's pinned+lastActivity ordering authoritative
 * rather than reshuffling locally.
 */
async function refreshSessions(): Promise<void> {
  if (selectedProjectName.value) await loadSessions(selectedProjectName.value);
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

async function openRecipeModal(): Promise<void> {
  if (!selectedProjectName.value) return;
  bootstrapError.value = null;
  recipesError.value = null;
  recipeModalOpen.value = true;
  recipesLoading.value = true;
  try {
    recipeOptions.value = await listProjectRecipes(selectedProjectName.value);
  } catch (e) {
    recipesError.value = describeError(e, t('chat.picker.recipeLoadFailed'));
    recipeOptions.value = [];
  } finally {
    recipesLoading.value = false;
  }
}

async function bootstrapNew(chatRecipe: string | null): Promise<void> {
  if (!selectedProjectName.value) return;
  bootstrapping.value = true;
  bootstrapError.value = null;
  try {
    const payload: SessionBootstrapRequest = {
      projectId: selectedProjectName.value,
      processes: [],
    };
    if (chatRecipe) payload.chatRecipe = chatRecipe;
    const response = await props.socket.send<SessionBootstrapRequest, SessionBootstrapResponse>(
      'session-bootstrap',
      payload);
    recipeModalOpen.value = false;
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

const COLOR_BORDER: Record<AccentColor, string> = {
  [AccentColor.SLATE]: 'border-l-slate-500',
  [AccentColor.RED]: 'border-l-red-500',
  [AccentColor.ORANGE]: 'border-l-orange-500',
  [AccentColor.AMBER]: 'border-l-amber-500',
  [AccentColor.GREEN]: 'border-l-green-500',
  [AccentColor.TEAL]: 'border-l-teal-500',
  [AccentColor.CYAN]: 'border-l-cyan-500',
  [AccentColor.BLUE]: 'border-l-blue-500',
  [AccentColor.INDIGO]: 'border-l-indigo-500',
  [AccentColor.PURPLE]: 'border-l-purple-500',
  [AccentColor.PINK]: 'border-l-pink-500',
  [AccentColor.ROSE]: 'border-l-rose-500',
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
  if (newName) {
    await Promise.all([loadSessions(newName), sessionGroupsState.reload(newName)]);
  } else {
    sessionGroupsState.groups.value = [];
  }
}, { immediate: true });

watch(showArchived, async () => {
  if (selectedProjectName.value) await loadSessions(selectedProjectName.value);
});

onBeforeUnmount(stopAutoScroll);
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
            @pointerdown.stop
            @click="searchOpen = true; emit('focus-main')"
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
            variant="ghost"
            :disabled="!selectedProjectName"
            :title="$t('chat.picker.groups.createTooltip')"
            @click="openCreateGroup"
          >
            {{ $t('chat.picker.groups.create') }}
          </VButton>
          <VButton
            variant="primary"
            :disabled="!selectedProjectName || bootstrapping"
            :loading="bootstrapping"
            :title="$t('chat.picker.newSession')"
            @click="openRecipeModal"
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
        <VAlert v-if="sessionGroupsState.error.value" variant="error">
          {{ sessionGroupsState.error.value }}
        </VAlert>

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

        <div v-else class="flex flex-col gap-3">
          <div
            v-for="block in sessionBlocks"
            :key="block.group ? block.group.name : '__ungrouped__'"
            class="rounded-lg transition-shadow"
            :class="hasGroups && dragHoverKey === groupKey(block.group)
              ? 'ring-2 ring-primary/50 bg-primary/5'
              : ''"
            @dragover="onBlockDragOver(block, $event)"
            @dragleave="onBlockDragLeave(block)"
            @drop="onBlockDrop(block, $event)"
          >
            <!-- Group header — only when the project actually has groups. -->
            <div v-if="hasGroups" class="flex items-center gap-2 px-1 py-1.5">
              <button
                type="button"
                class="opacity-60 hover:opacity-100 w-4 text-center text-xs"
                @click="toggleCollapsed(block.group)"
              >{{ isCollapsed(block.group) ? '▸' : '▾' }}</button>
              <span class="font-semibold text-sm truncate">
                {{ block.group
                  ? (block.group.title || block.group.name)
                  : $t('chat.picker.groups.ungrouped') }}
              </span>
              <span class="opacity-50 text-xs">{{ block.sessions.length }}</span>
              <div v-if="block.group" class="ml-auto flex items-center gap-0.5">
                <VButton
                  variant="ghost"
                  size="sm"
                  :disabled="groupIndex(block.group) === 0"
                  :title="$t('chat.picker.groups.moveUp')"
                  @click="moveGroup(groupIndex(block.group), -1)"
                >▲</VButton>
                <VButton
                  variant="ghost"
                  size="sm"
                  :disabled="groupIndex(block.group) === sessionGroupsState.groups.value.length - 1"
                  :title="$t('chat.picker.groups.moveDown')"
                  @click="moveGroup(groupIndex(block.group), 1)"
                >▼</VButton>
                <VButton
                  variant="ghost"
                  size="sm"
                  :title="$t('chat.picker.groups.delete')"
                  @click="deleteGroup(block.group)"
                >✕</VButton>
              </div>
            </div>

            <ul
              v-show="!isCollapsed(block.group)"
              class="flex flex-col gap-2"
              :class="hasGroups ? 'pl-5' : ''"
            >
              <li
                v-for="session in block.sessions"
                :key="session.sessionId"
                class="card bg-base-100 shadow-sm border border-base-300 border-l-4"
                :class="[
                  colorBorderClass(session),
                  isHardBlocked(session) ? 'opacity-60' : '',
                  session.status !== SessionStatus.ARCHIVED && !isHardBlocked(session)
                    ? 'hover:border-primary cursor-pointer'
                    : '',
                  session.status === SessionStatus.ARCHIVED ? 'bg-base-200/40' : '',
                ]"
                :draggable="hasGroups"
                @click="pickSession(session)"
                @dragstart="onSessionDragStart(session, $event)"
                @dragend="onSessionDragEnd"
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
                    v-if="session.allowMultipleClients"
                    class="shrink-0 text-xs"
                    :title="$t('chat.picker.sharedTooltip')"
                  >👥</span>
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
                <div class="text-xs opacity-60 truncate mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5">
                  <span>{{ session.status }} · {{ formatRelativeTime(session.lastActivityAt) }}</span>
                  <span
                    v-if="session.chatRecipe"
                    class="font-mono opacity-70"
                    :title="$t('chat.picker.recipeBadgeTooltip')"
                  >
                    🧪 {{ session.chatRecipe }}
                  </span>
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
                <span v-if="isHardBlocked(session)" class="text-xs text-error">
                  {{ $t('chat.picker.occupied') }}
                </span>
                <span
                  v-else-if="session.bound && session.allowMultipleClients"
                  class="text-xs text-success"
                  :title="$t('chat.picker.sharedTooltip')"
                >
                  {{ $t('chat.picker.joinLive') }}
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
                <SessionActionsMenu
                  :session="session"
                  @changed="refreshSessions"
                  @archived="refreshSessions"
                  @reactivated="refreshSessions"
                  @deleted="refreshSessions"
                  @duplicated="refreshSessions"
                  @crop="openCrop"
                />
              </div>
            </div>
              </li>
              <li
                v-if="hasGroups && block.sessions.length === 0"
                class="text-xs opacity-40 px-2 py-2 italic"
              >{{ $t('chat.picker.groups.emptyDropHint') }}</li>
            </ul>
          </div>
        </div>
        </div>
      </div>
    </section>

    <SessionSearchModal
      v-if="searchOpen"
      @close="searchOpen = false"
      @pick="onSearchPick"
    />

    <SessionCropModal
      v-model="cropOpen"
      :session-id="cropSessionId"
    />

    <VModal
      v-model="recipeModalOpen"
      :title="$t('chat.picker.recipeModalTitle')"
    >
      <div class="space-y-3">
        <p class="text-sm opacity-70">{{ $t('chat.picker.recipeModalIntro') }}</p>

        <VAlert v-if="recipesError" variant="error">{{ recipesError }}</VAlert>
        <VAlert v-if="bootstrapError" variant="error">{{ bootstrapError }}</VAlert>

        <ul class="flex flex-col gap-2 max-h-[60vh] overflow-y-auto">
          <li>
            <button
              type="button"
              class="w-full text-left rounded-lg border border-base-300 hover:border-primary p-3 transition-colors"
              :disabled="bootstrapping"
              @click="bootstrapNew(null)"
            >
              <div class="font-semibold">{{ $t('chat.picker.recipeDefaultName') }}</div>
              <div class="text-xs opacity-70 mt-1">
                {{ $t('chat.picker.recipeDefaultDescription') }}
              </div>
            </button>
          </li>

          <li v-if="recipesLoading" class="text-sm opacity-60 px-1">
            {{ $t('chat.picker.sessionsLoading') }}
          </li>

          <li
            v-for="recipe in recipeOptions"
            :key="recipe.name"
          >
            <button
              type="button"
              class="w-full text-left rounded-lg border border-base-300 hover:border-primary p-3 transition-colors"
              :disabled="bootstrapping"
              @click="bootstrapNew(recipe.name)"
            >
              <div class="flex items-baseline gap-2 min-w-0">
                <span class="font-semibold truncate">
                  {{ recipe.title || recipe.name }}
                </span>
                <span
                  v-if="recipe.title"
                  class="text-xs opacity-50 font-mono truncate"
                >
                  {{ recipe.name }}
                </span>
              </div>
              <div
                v-if="recipe.description"
                class="text-xs opacity-70 mt-1 whitespace-pre-line line-clamp-3"
              >
                {{ recipe.description }}
              </div>
            </button>
          </li>
        </ul>
      </div>
      <template #actions>
        <VButton variant="ghost" :disabled="bootstrapping" @click="recipeModalOpen = false">
          {{ $t('common.cancel') }}
        </VButton>
      </template>
    </VModal>

    <VModal
      v-model="showCreateGroup"
      :title="$t('chat.picker.groups.createTitle')"
    >
      <div class="flex flex-col gap-3">
        <VAlert v-if="sessionGroupsState.error.value" variant="error">
          {{ sessionGroupsState.error.value }}
        </VAlert>
        <VInput
          v-model="newGroupName"
          :label="$t('chat.picker.groups.nameLabel')"
          :help="$t('chat.picker.groups.nameHint')"
          @keyup.enter="submitCreateGroup"
        />
        <VInput
          v-model="newGroupTitle"
          :label="$t('chat.picker.groups.titleLabel')"
          @keyup.enter="submitCreateGroup"
        />
      </div>
      <template #actions>
        <VButton variant="ghost" @click="showCreateGroup = false">
          {{ $t('common.cancel') }}
        </VButton>
        <VButton
          variant="primary"
          :disabled="!newGroupName.trim()"
          :loading="sessionGroupsState.busy.value"
          @click="submitCreateGroup"
        >{{ $t('chat.picker.groups.add') }}</VButton>
      </template>
    </VModal>
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
