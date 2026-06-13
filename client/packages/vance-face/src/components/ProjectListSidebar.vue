<script setup lang="ts">
import { VAlert, VButton, VEmptyState, VInput, VModal, VSelect } from '@vance/components';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type {
  ProjectGroupSummary,
  ProjectSummary,
  SidebarUiStateDto,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';
import { useProjectKitsCatalog } from '@/composables/useProjectKitsCatalog';

/**
 * Reusable project picker for editor sidebars. Renders the tenant's
 * projects grouped by their {@link ProjectSummary.projectGroupId},
 * with an optional filter input and selection-highlight. Hosts use
 * this in their {@code #sidebar} slot to avoid re-implementing the
 * same project list across chat, documents, scopes, …
 *
 * <p>Read-only labels (heading, filter placeholder, …) come in as
 * plain string props so each host can wire its own translation
 * namespace. The edit-mode strings (Add Group / Add Project modals)
 * use vue-i18n directly under {@code common.projectPicker.*} so the
 * same form behaviour ships to every host without per-host label
 * boilerplate. Mirrors the {@code SessionHeader} / {@code SettingFormView}
 * pattern for domain widgets.
 *
 * <p>Drag-and-drop reordering between groups will land in a
 * follow-up via a {@code move-project} emit.
 */
/**
 * Tree-selection variant. Hosts in tree mode (scopes admin) use
 * {@link Props.showGroupRows} + the {@code selectedNode} v-model
 * to track which node — group or project — is currently chosen.
 * The simpler {@code selectedProject} v-model still works for
 * project-only flows (chat, documents).
 */
export interface PickerNode {
  kind: 'group' | 'project';
  name: string;
}

interface Props {
  groups: ProjectGroupSummary[];
  projects: ProjectSummary[];
  loading?: boolean;
  error?: string | null;
  /** When true, render a filter input above the list. */
  searchEnabled?: boolean;
  /** When true, render Add-Group / Add-Project buttons. Server still
   *  enforces tenant-admin permission; non-admins get a 403 surfaced
   *  via the modal's inline error. */
  editEnabled?: boolean;
  /** Render group rows as clickable buttons (tree mode). Without
   *  this the group label is just a dim divider and only projects
   *  are selectable. Pairs with the {@code selectedNode} v-model. */
  showGroupRows?: boolean;
  /** Kit dropdown options for the create-project modal. When set,
   *  the component renders exactly these — first entry should be a
   *  "no kit" sentinel (blank value). When left unset the component
   *  loads the tenant's project-kits catalog on mount and builds
   *  the dropdown itself; pass {@link hideKitField}=true to opt out
   *  of the kit field entirely. */
  kitOptions?: { value: string; label: string }[];
  /** Explicitly hide the kit field even when a catalog exists.
   *  Useful for hosts where project creation is meant to be
   *  intentionally minimal. */
  hideKitField?: boolean;
  /** Heading shown above the list (e.g. "Projekte"). When blank
   *  the heading row is suppressed entirely — useful when the host
   *  already paints its own section label. */
  heading?: string;
  /** Placeholder for the filter input. */
  filterPlaceholder?: string;
  /** Label used for the "Ohne Gruppe" / "Ungrouped" group block. */
  ungroupedLabel?: string;
  /** Message when {@link projects} is empty (no filter active).
   *  Pass an empty string to suppress the empty state. */
  emptyHeadline?: string;
  emptyBody?: string;
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  error: null,
  searchEnabled: true,
  editEnabled: false,
  showGroupRows: false,
  kitOptions: () => [],
  hideKitField: false,
  heading: '',
  filterPlaceholder: '',
  ungroupedLabel: '',
  emptyHeadline: '',
  emptyBody: '',
});

const { t } = useI18n();

/**
 * Two-way bound project selection — used by hosts that only track
 * project selection (chat, documents). Writes from the host
 * (popstate, URL hydration, …) flow in; user clicks flow out.
 */
const selectedProject = defineModel<string | null>('selectedProject', { default: null });

/**
 * Two-way bound tree selection — used by hosts that need to track
 * group + project selection together (scopes). Mutually exclusive
 * in practice with {@link selectedProject}, but the component
 * keeps both v-models so a host doesn't have to bridge them.
 */
const selectedNode = defineModel<PickerNode | null>('selectedNode', { default: null });

const emit = defineEmits<{
  /** User clicked a project row — distinct from a v-model write
   *  (which can also fire on URL hydration). Hosts use this to
   *  push browser history per click. */
  (e: 'project-pick', payload: { name: string; title: string }): void;
  /** User clicked a group row (only fires when {@link Props.showGroupRows}
   *  is set). Hosts use this analogously to {@link project-pick}. */
  (e: 'group-pick', payload: { name: string; title: string }): void;
  /** Pointerdown on a project row — sidebars typically want to
   *  shift focus to the main zone before the click selects.
   *  Wire this to whatever moves focus in the host (focus-zone
   *  ref, internal flag, etc.). */
  (e: 'focus-main'): void;
  /** A new group or project was created. Host should re-fetch its
   *  {@code useTenantProjects} so the new entry shows up. Payload
   *  is the technical name of the freshly created entity — host
   *  may auto-select it. */
  (e: 'data-changed', payload: { kind: 'group' | 'project'; name: string }): void;
}>();

const projectFilter = ref('');

interface GroupBlock {
  group: ProjectGroupSummary | null;
  groupLabel: string;
  projects: ProjectSummary[];
}

const projectsByGroup = computed<GroupBlock[]>(() => {
  const byKey = new Map<string | null, ProjectSummary[]>();
  // In edit mode keep an empty {@code null} bucket so the "ungrouped"
  // drop zone is always visible — without it, dragging the last
  // project out of "no group" would remove its target entirely on
  // the next reload and the inverse (dropping back into "no group")
  // would have nowhere to land.
  if (props.editEnabled) {
    byKey.set(null, []);
  }
  for (const p of props.projects) {
    const key = p.projectGroupId ?? null;
    const list = byKey.get(key) ?? [];
    list.push(p);
    byKey.set(key, list);
  }
  // In edit mode also surface every named group, even when empty,
  // so users can drag a project into a freshly created group that
  // has no projects yet.
  if (props.editEnabled) {
    for (const g of props.groups) {
      if (!byKey.has(g.name)) byKey.set(g.name, []);
    }
  }
  const groupByName = new Map(props.groups.map((g) => [g.name, g] as const));
  const result: GroupBlock[] = [];
  for (const [groupName, list] of byKey.entries()) {
    const group = groupName ? groupByName.get(groupName) ?? null : null;
    const groupLabel = group ? group.title || group.name : props.ungroupedLabel;
    // Project order inside a group follows the same display-label
    // alphabetical rule as the groups themselves — the API order
    // is otherwise arbitrary and felt random in the sidebar.
    const sortedProjects = [...list].sort((a, b) =>
      projectLabel(a).localeCompare(projectLabel(b), undefined, { sensitivity: 'base' }));
    result.push({ group, groupLabel, projects: sortedProjects });
  }
  // Stable order: ungrouped first, then groups alphabetically by
  // their displayed label (title || name) — sorting by the
  // technical {@code name} mismatched the rendered order whenever
  // a group's {@code title} disagreed with its slug.
  result.sort((a, b) => {
    if (a.group === null && b.group !== null) return -1;
    if (a.group !== null && b.group === null) return 1;
    if (!a.group || !b.group) return 0;
    return a.groupLabel.localeCompare(b.groupLabel, undefined, { sensitivity: 'base' });
  });
  return result;
});

function projectLabel(p: ProjectSummary): string {
  return p.title || p.name;
}

const filteredProjectsByGroup = computed<GroupBlock[]>(() => {
  const needle = projectFilter.value.trim().toLowerCase();
  if (!needle) return projectsByGroup.value;
  const result: GroupBlock[] = [];
  for (const block of projectsByGroup.value) {
    const matching = block.projects.filter((p) => {
      const title = (p.title ?? '').toLowerCase();
      const name = p.name.toLowerCase();
      return title.includes(needle) || name.includes(needle);
    });
    if (matching.length > 0) {
      result.push({ ...block, projects: matching });
    }
  }
  return result;
});

const filteredProjectsCount = computed<number>(() =>
  filteredProjectsByGroup.value.reduce((n, b) => n + b.projects.length, 0));

function isProjectSelected(p: ProjectSummary): boolean {
  if (selectedNode.value) {
    return selectedNode.value.kind === 'project' && selectedNode.value.name === p.name;
  }
  return selectedProject.value === p.name;
}

function isGroupSelected(g: ProjectGroupSummary): boolean {
  return selectedNode.value?.kind === 'group' && selectedNode.value.name === g.name;
}

function selectProject(p: ProjectSummary): void {
  selectedProject.value = p.name;
  selectedNode.value = { kind: 'project', name: p.name };
  emit('project-pick', { name: p.name, title: p.title || p.name });
}

function selectGroup(g: ProjectGroupSummary): void {
  selectedNode.value = { kind: 'group', name: g.name };
  emit('group-pick', { name: g.name, title: g.title || g.name });
}

// ────────────────── Collapse state (per-user, persisted) ──────────────────
//
// Collapse state lives on the per-user {@code _user_<login>} project
// behind {@code /brain/{tenant}/me/ui-state/sidebar}. The Set holds
// group names; missing = expanded (the default for new groups). When
// a filter is active we override and always show matches — otherwise
// the user types a query and gets nothing back because the parent is
// collapsed.

const collapsedGroups = ref<Set<string>>(new Set());
let collapsedLoaded = false;
let saveTimer: number | null = null;
const SAVE_DEBOUNCE_MS = 300;

/**
 * Reserved key for the "ungrouped" pseudo-block. The block has no
 * project-group document and therefore no name; we still want users
 * to be able to collapse it, so we persist it under a sentinel.
 * {@code "_"} alone is extremely unlikely as a real group name —
 * groups in this tenant tend to use longer slugs ({@code _home},
 * {@code marketing}, …). If a tenant admin ever creates an actual
 * group named {@code "_"}, the two would share collapse state; we
 * accept that trade-off for the simpler wire format.
 */
const UNGROUPED_KEY = '_';

function groupCollapseKey(g: ProjectGroupSummary | null): string {
  return g ? g.name : UNGROUPED_KEY;
}

function isGroupCollapsed(g: ProjectGroupSummary | null): boolean {
  if (projectFilter.value.trim()) return false;
  return collapsedGroups.value.has(groupCollapseKey(g));
}

function toggleGroupCollapsed(g: ProjectGroupSummary | null): void {
  const key = groupCollapseKey(g);
  const next = new Set(collapsedGroups.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  collapsedGroups.value = next;
  scheduleSaveCollapsed();
}

function scheduleSaveCollapsed(): void {
  if (!collapsedLoaded) return;
  if (saveTimer !== null) window.clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => {
    saveTimer = null;
    void saveCollapsedNow();
  }, SAVE_DEBOUNCE_MS);
}

async function saveCollapsedNow(): Promise<void> {
  try {
    await brainFetch<SidebarUiStateDto>('PUT', 'me/ui-state/sidebar', {
      body: {
        collapsedProjectGroups: Array.from(collapsedGroups.value),
      } satisfies SidebarUiStateDto,
    });
  } catch (e) {
    // UI-state persistence is non-critical — swallow the error so a
    // transient failure doesn't surface as an alert. Worst case: the
    // next toggle retries the PUT.
    console.warn('Failed to save sidebar UI state', e);
  }
}

onMounted(async () => {
  try {
    const state = await brainFetch<SidebarUiStateDto>('GET', 'me/ui-state/sidebar');
    collapsedGroups.value = new Set(state.collapsedProjectGroups ?? []);
  } catch (e) {
    // Same rationale as saveCollapsedNow — UI state is best-effort.
    console.warn('Failed to load sidebar UI state', e);
  } finally {
    collapsedLoaded = true;
  }

  // Self-load the project-kits catalog so the create-project modal
  // offers a kit picker by default in every editor sidebar. Hosts
  // that pass their own {@code kitOptions} (scopes admin) or that
  // disable the field via {@code hideKitField} get a no-op here.
  if (!props.hideKitField && props.kitOptions.length === 0) {
    void internalKitsCatalog.load();
  }
});

onBeforeUnmount(() => {
  if (saveTimer !== null) {
    // Flush any pending debounced write so the user's last toggle
    // doesn't get lost when they navigate away immediately after.
    window.clearTimeout(saveTimer);
    saveTimer = null;
    void saveCollapsedNow();
  }
});

// ────────────────── Edit mode: create group / project ──────────────────

const showCreateGroup = ref(false);
const newGroupName = ref('');
const newGroupTitle = ref('');
const showCreateProject = ref(false);
const newProjectName = ref('');
const newProjectTitle = ref('');
const newProjectGroupId = ref<string | null>(null);
const newProjectKitName = ref<string>('');
const creating = ref(false);
const creationError = ref<string | null>(null);

/**
 * Self-loaded project-kits catalog — only used when the host did not
 * supply its own {@code kitOptions} prop. Keeps the dropdown available
 * by default in every editor sidebar without each host having to wire
 * the catalog explicitly. Hosts that already own the catalog (scopes
 * admin) keep passing {@code kitOptions} directly and this composable
 * stays idle.
 */
const internalKitsCatalog = useProjectKitsCatalog();

const effectiveKitOptions = computed<{ value: string; label: string }[]>(() => {
  if (props.hideKitField) return [];
  if (props.kitOptions.length > 0) return props.kitOptions;
  const catalogKits = internalKitsCatalog.catalog.value?.kits ?? [];
  if (catalogKits.length === 0) return [];
  return [
    { value: '', label: t('common.projectPicker.createProject.kitNone') },
    ...catalogKits.map((entry) => ({
      value: entry.name,
      label: entry.title || entry.name,
    })),
  ];
});

const showKitField = computed<boolean>(() => effectiveKitOptions.value.length > 0);

function openCreateGroup(): void {
  newGroupName.value = '';
  newGroupTitle.value = '';
  creationError.value = null;
  showCreateGroup.value = true;
}

/** Group dropdown for the create-project modal — same options
 *  as the rendered project list. {@code null} value maps to
 *  "Ohne Gruppe" / "No group". */
const groupSelectOptions = computed(() => [
  { value: '', label: t('common.projectPicker.createProject.groupNone') },
  ...props.groups.map((g) => ({
    value: g.name,
    label: g.title || g.name,
  })),
]);

function openCreateProject(groupId: string | null = null): void {
  newProjectName.value = '';
  newProjectTitle.value = '';
  newProjectGroupId.value = groupId;
  newProjectKitName.value = '';
  creationError.value = null;
  showCreateProject.value = true;
}

async function submitCreateGroup(): Promise<void> {
  const name = newGroupName.value.trim();
  if (!name) return;
  creating.value = true;
  creationError.value = null;
  try {
    await brainFetch('POST', 'admin/project-groups', {
      body: {
        name,
        title: newGroupTitle.value.trim() || undefined,
      },
    });
    showCreateGroup.value = false;
    emit('data-changed', { kind: 'group', name });
  } catch (e) {
    creationError.value = describeError(e);
  } finally {
    creating.value = false;
  }
}

async function submitCreateProject(): Promise<void> {
  const name = newProjectName.value.trim();
  if (!name) return;
  creating.value = true;
  creationError.value = null;
  try {
    await brainFetch('POST', 'admin/projects', {
      body: {
        name,
        title: newProjectTitle.value.trim() || undefined,
        projectGroupId: newProjectGroupId.value || undefined,
        teamIds: [],
        // Only included when the host opted in via {@link Props.kitOptions}.
        // Blank string maps to "no kit" — server treats null/blank the same.
        kitName: showKitField.value
          ? (newProjectKitName.value.trim() || undefined)
          : undefined,
      },
    });
    showCreateProject.value = false;
    emit('data-changed', { kind: 'project', name });
  } catch (e) {
    creationError.value = describeError(e);
  } finally {
    creating.value = false;
  }
}

function describeError(e: unknown): string {
  const msg = e instanceof Error ? e.message : String(e);
  if (msg.toLowerCase().includes('forbidden') || msg.includes('403')) {
    return t('common.projectPicker.error.forbidden');
  }
  return t('common.projectPicker.error.generic', { message: msg });
}

// ────────────────── Edit mode: drag-and-drop to move ──────────────────
//
// Project buttons are {@code draggable="true"} when {@link editEnabled}
// is set; group blocks (and the synthetic "ungrouped" block) accept
// drops. On a successful drop we PUT
// {@code admin/projects/{name}} with the target group — empty payload
// means "leave as is", so we send either {@code projectGroupId:<name>}
// or {@code clearProjectGroup: true} for the "no group" target.

const draggingProject = ref<string | null>(null);
/** Identifier of the group block the dragged project is hovering over —
 *  {@code 'g:<name>'} for a named group, {@code 'ungrouped'} for the
 *  null-group bucket, {@code null} when not over any drop zone.
 *  Drives the highlight class on the drop-target. */
const dragHoverKey = ref<string | null>(null);
const moving = ref(false);
const moveError = ref<string | null>(null);

// Auto-scroll during drag — when the pointer hovers within
// AUTOSCROLL_EDGE pixels of the scrollable ancestor's top/bottom
// edge, we kick a rAF loop that scrolls the container so the user
// can drop on rows currently below/above the fold without having
// to abort the drag.
const AUTOSCROLL_EDGE = 48; // px from the edge where scroll kicks in
const AUTOSCROLL_MAX = 16; // px per frame at the very edge
let scrollContainer: HTMLElement | null = null;
let scrollRaf: number | null = null;
let lastPointerY = 0;

function findScrollableAncestor(el: HTMLElement | null): HTMLElement | null {
  let cur = el;
  while (cur && cur !== document.body && cur !== document.documentElement) {
    const overflowY = window.getComputedStyle(cur).overflowY;
    if (overflowY === 'auto' || overflowY === 'scroll') {
      // Only useful if the container actually overflows; otherwise
      // climb past it (the dialog body sometimes has overflow-auto
      // but no overflowing content).
      if (cur.scrollHeight > cur.clientHeight) return cur;
    }
    cur = cur.parentElement;
  }
  return null;
}

function onDocumentDragOver(ev: DragEvent): void {
  lastPointerY = ev.clientY;
}

function scrollLoop(): void {
  if (!scrollContainer || !draggingProject.value) {
    scrollRaf = null;
    return;
  }
  const rect = scrollContainer.getBoundingClientRect();
  const fromTop = lastPointerY - rect.top;
  const fromBottom = rect.bottom - lastPointerY;
  let delta = 0;
  if (fromTop >= 0 && fromTop < AUTOSCROLL_EDGE) {
    const ratio = 1 - fromTop / AUTOSCROLL_EDGE; // 1 at edge, 0 at threshold
    delta = -Math.max(2, Math.round(ratio * AUTOSCROLL_MAX));
  } else if (fromBottom >= 0 && fromBottom < AUTOSCROLL_EDGE) {
    const ratio = 1 - fromBottom / AUTOSCROLL_EDGE;
    delta = Math.max(2, Math.round(ratio * AUTOSCROLL_MAX));
  }
  if (delta !== 0) {
    scrollContainer.scrollTop += delta;
  }
  scrollRaf = requestAnimationFrame(scrollLoop);
}

function startAutoScroll(originEl: HTMLElement | null): void {
  scrollContainer = findScrollableAncestor(originEl);
  if (!scrollContainer) return;
  document.addEventListener('dragover', onDocumentDragOver);
  if (scrollRaf === null) {
    scrollRaf = requestAnimationFrame(scrollLoop);
  }
}

function stopAutoScroll(): void {
  document.removeEventListener('dragover', onDocumentDragOver);
  if (scrollRaf !== null) {
    cancelAnimationFrame(scrollRaf);
    scrollRaf = null;
  }
  scrollContainer = null;
}

// Component teardown mid-drag (rare — route change, dialog close)
// has to release the global listener + rAF so the browser doesn't
// keep them alive past the picker's lifetime.
onBeforeUnmount(stopAutoScroll);

function blockKey(block: GroupBlock): string {
  return block.group ? `g:${block.group.name}` : 'ungrouped';
}

function onProjectDragStart(p: ProjectSummary, ev: DragEvent): void {
  if (!props.editEnabled) return;
  draggingProject.value = p.name;
  if (ev.dataTransfer) {
    ev.dataTransfer.effectAllowed = 'move';
    // Use a vendored MIME so other draggables on the page can't
    // accidentally trigger our drop handler.
    ev.dataTransfer.setData('application/x-vance-project', p.name);
  }
  // Track pointer Y at the document level + tick an animation loop
  // so the user can drag over rows that are currently outside the
  // scroll viewport.
  lastPointerY = ev.clientY;
  startAutoScroll(ev.target as HTMLElement | null);
}

function onProjectDragEnd(): void {
  draggingProject.value = null;
  dragHoverKey.value = null;
  stopAutoScroll();
}

function onBlockDragOver(block: GroupBlock, ev: DragEvent): void {
  if (!props.editEnabled || !draggingProject.value) return;
  ev.preventDefault();
  if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'move';
  dragHoverKey.value = blockKey(block);
}

function onBlockDragLeave(block: GroupBlock): void {
  if (dragHoverKey.value === blockKey(block)) {
    dragHoverKey.value = null;
  }
}

async function onBlockDrop(block: GroupBlock, ev: DragEvent): Promise<void> {
  if (!props.editEnabled) return;
  ev.preventDefault();
  const projectName =
      draggingProject.value
    ?? ev.dataTransfer?.getData('application/x-vance-project')
    ?? '';
  draggingProject.value = null;
  dragHoverKey.value = null;
  stopAutoScroll();
  if (!projectName) return;

  const project = props.projects.find((p) => p.name === projectName);
  if (!project) return;

  const targetGroup = block.group ? block.group.name : null;
  const currentGroup = project.projectGroupId ?? null;
  if (currentGroup === targetGroup) return;

  moving.value = true;
  moveError.value = null;
  try {
    await brainFetch('PUT', `admin/projects/${encodeURIComponent(projectName)}`, {
      body: targetGroup
        ? { projectGroupId: targetGroup }
        : { clearProjectGroup: true },
    });
    emit('data-changed', { kind: 'project', name: projectName });
  } catch (e) {
    moveError.value = describeError(e);
    // Auto-dismiss after 5s — leaves the sidebar clean once the
    // user has seen the message (or hits the dismiss button below).
    window.setTimeout(() => {
      moveError.value = null;
    }, 5000);
  } finally {
    moving.value = false;
  }
}
</script>

<template>
  <div class="p-4 flex flex-col gap-4">
    <!-- Header row: section label on the left, optional host
         controls (e.g. a 🔍 search button) plus Add-Group button on
         the right. The row is suppressed only when {@code heading}
         is empty AND there are no extras. -->
    <div
      v-if="heading || $slots['header-extra'] || editEnabled"
      class="flex items-center justify-between gap-2"
    >
      <div
        v-if="heading"
        class="text-xs uppercase tracking-wide opacity-60 font-semibold px-2"
      >
        {{ heading }}
      </div>
      <span v-else />
      <div class="flex items-center gap-1">
        <slot name="header-extra" />
        <VButton
          v-if="editEnabled"
          variant="ghost"
          size="sm"
          :title="t('common.projectPicker.addGroup')"
          @pointerdown.stop
          @click="openCreateGroup"
        >📂+</VButton>
      </div>
    </div>

    <VInput
      v-if="searchEnabled && !loading && !error && projects.length > 0"
      v-model="projectFilter"
      :placeholder="filterPlaceholder"
    />

    <div v-if="loading" class="text-sm opacity-60 px-2">
      <slot name="loading">…</slot>
    </div>

    <VAlert v-else-if="error" variant="error">
      {{ error }}
    </VAlert>

    <template v-else>
      <VAlert
        v-if="moveError"
        variant="error"
        class="cursor-pointer"
        @click="moveError = null"
      >
        <span>{{ moveError }}</span>
      </VAlert>

      <div
        v-for="block in filteredProjectsByGroup"
        :key="block.group?.name ?? '_ungrouped'"
        class="flex flex-col gap-1 rounded transition-colors"
        :class="dragHoverKey === blockKey(block)
          ? 'bg-primary/5 outline outline-2 outline-primary/40 outline-offset-2'
          : ''"
        @dragover="onBlockDragOver(block, $event)"
        @dragleave="onBlockDragLeave(block)"
        @drop="onBlockDrop(block, $event)"
      >
        <!-- Group row. In tree mode ({@code showGroupRows}) the named
             groups become clickable selectable rows; the ungrouped
             pseudo-block stays a dim divider regardless. The chevron
             is its own button so collapse-toggle and group-select
             stay distinct actions in tree mode. -->
        <template v-if="block.group && showGroupRows">
          <div class="flex items-center gap-1">
            <button
              type="button"
              class="px-1 py-1.5 text-xs opacity-60 hover:opacity-100"
              :title="isGroupCollapsed(block.group)
                ? t('common.projectPicker.expandGroup')
                : t('common.projectPicker.collapseGroup')"
              @pointerdown.stop
              @click.stop="toggleGroupCollapsed(block.group)"
            >{{ isGroupCollapsed(block.group) ? '▸' : '▾' }}</button>
            <button
              type="button"
              class="flex-1 text-left px-2 py-1.5 rounded text-sm transition-colors flex items-center gap-2"
              :class="isGroupSelected(block.group)
                ? 'bg-primary/10 text-primary font-medium'
                : 'hover:bg-base-200'"
              @pointerdown.stop
              @click="selectGroup(block.group); emit('focus-main')"
            >
              <span class="flex-1 truncate">{{ block.group.title || block.group.name }}</span>
              <slot name="row-suffix" :kind="'group'" :item="block.group" />
            </button>
            <button
              v-if="editEnabled"
              type="button"
              class="text-xs opacity-50 hover:opacity-100 px-1"
              :title="t('common.projectPicker.addProjectToGroup')"
              @pointerdown.stop
              @click="openCreateProject(block.group.name)"
            >+</button>
          </div>
        </template>
        <!-- Label row. Both named groups and the ungrouped
             pseudo-block become a clickable toggle for the whole
             row; the ungrouped block persists under the reserved
             {@code UNGROUPED_KEY} sentinel. -->
        <button
          v-else-if="block.groupLabel"
          type="button"
          class="flex items-center justify-between px-2 py-1 rounded text-left hover:bg-base-200 transition-colors w-full"
          :title="isGroupCollapsed(block.group)
            ? t('common.projectPicker.expandGroup')
            : t('common.projectPicker.collapseGroup')"
          @pointerdown.stop
          @click="toggleGroupCollapsed(block.group)"
        >
          <span class="flex items-center gap-1.5 min-w-0">
            <span class="text-xs opacity-50 w-3 inline-block text-center">{{
              isGroupCollapsed(block.group) ? '▸' : '▾'
            }}</span>
            <span class="text-xs opacity-50 truncate">{{ block.groupLabel }}</span>
          </span>
          <span
            v-if="editEnabled"
            class="text-xs opacity-50 hover:opacity-100 px-1"
            :title="t('common.projectPicker.addProjectToGroup')"
            role="button"
            @pointerdown.stop
            @click.stop="openCreateProject(block.group?.name ?? null)"
          >+</span>
        </button>
        <button
          v-for="p in block.projects"
          v-show="!isGroupCollapsed(block.group)"
          :key="p.name"
          type="button"
          :draggable="editEnabled"
          class="text-left px-2 py-1.5 rounded text-sm transition-colors flex items-center gap-2"
          :class="[
            isProjectSelected(p)
              ? 'bg-primary/10 text-primary font-medium'
              : 'hover:bg-base-200',
            showGroupRows ? 'pl-6' : '',
            editEnabled ? 'cursor-grab active:cursor-grabbing' : '',
            draggingProject === p.name ? 'opacity-50' : '',
          ]"
          @pointerdown.stop
          @click="selectProject(p); emit('focus-main')"
          @dragstart="onProjectDragStart(p, $event)"
          @dragend="onProjectDragEnd"
        >
          <span class="flex-1 truncate">{{ p.title || p.name }}</span>
          <slot name="row-suffix" :kind="'project'" :item="p" />
        </button>
      </div>

      <VEmptyState
        v-if="projects.length === 0 && emptyHeadline"
        :headline="emptyHeadline"
        :body="emptyBody"
      />
      <div
        v-else-if="projectFilter && filteredProjectsCount === 0"
        class="text-xs opacity-60 px-2"
      >
        <slot name="filter-no-match" :filter="projectFilter" />
      </div>

      <VButton
        v-if="editEnabled && !loading && !error"
        variant="ghost"
        size="sm"
        block
        @pointerdown.stop
        @click="openCreateProject(null)"
      >+ {{ t('common.projectPicker.addProject') }}</VButton>
    </template>

    <!-- ── Create-group modal ── -->
    <VModal
      v-model="showCreateGroup"
      :title="t('common.projectPicker.createGroup.title')"
      :close-on-backdrop="!creating"
    >
      <form class="flex flex-col gap-3" @submit.prevent="submitCreateGroup">
        <VAlert v-if="creationError" variant="error">
          <span>{{ creationError }}</span>
        </VAlert>
        <VInput
          v-model="newGroupName"
          :label="t('common.projectPicker.createGroup.name')"
          :help="t('common.projectPicker.createGroup.nameHelp')"
          required
          :disabled="creating"
        />
        <VInput
          v-model="newGroupTitle"
          :label="t('common.projectPicker.createGroup.titleLabel')"
          :disabled="creating"
        />
      </form>
      <template #actions>
        <VButton
          variant="ghost"
          :disabled="creating"
          @click="showCreateGroup = false"
        >{{ t('common.cancel') }}</VButton>
        <VButton
          variant="primary"
          :loading="creating"
          :disabled="!newGroupName.trim()"
          @click="submitCreateGroup"
        >{{ t('common.projectPicker.createGroup.submit') }}</VButton>
      </template>
    </VModal>

    <!-- ── Create-project modal ── -->
    <VModal
      v-model="showCreateProject"
      :title="t('common.projectPicker.createProject.title')"
      :close-on-backdrop="!creating"
    >
      <form class="flex flex-col gap-3" @submit.prevent="submitCreateProject">
        <VAlert v-if="creationError" variant="error">
          <span>{{ creationError }}</span>
        </VAlert>
        <VInput
          v-model="newProjectName"
          :label="t('common.projectPicker.createProject.name')"
          :help="t('common.projectPicker.createProject.nameHelp')"
          required
          :disabled="creating"
        />
        <VInput
          v-model="newProjectTitle"
          :label="t('common.projectPicker.createProject.titleLabel')"
          :disabled="creating"
        />
        <VSelect
          v-model="newProjectGroupId"
          :label="t('common.projectPicker.createProject.group')"
          :options="groupSelectOptions"
          :disabled="creating"
        />
        <VSelect
          v-if="showKitField"
          v-model="newProjectKitName"
          :label="t('common.projectPicker.createProject.kit')"
          :help="t('common.projectPicker.createProject.kitHelp')"
          :options="effectiveKitOptions"
          :disabled="creating"
        />
      </form>
      <template #actions>
        <VButton
          variant="ghost"
          :disabled="creating"
          @click="showCreateProject = false"
        >{{ t('common.cancel') }}</VButton>
        <VButton
          variant="primary"
          :loading="creating"
          :disabled="!newProjectName.trim()"
          @click="submitCreateProject"
        >{{ t('common.projectPicker.createProject.submit') }}</VButton>
      </template>
    </VModal>
  </div>
</template>
