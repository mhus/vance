<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  EditorShell,
  ProjectListSidebar,
  SettingFormView,
  VAlert,
  VBadge,
  VButton,
  VEmptyState,
  VIcon,
  VGroup,
  VGroupRow,
  VIconButton,
  VInput,
  VModal,
  type FocusZone,
  type PickerNode,
  useConfirm,
} from '@/components';
import RawSettingsPanel from './RawSettingsPanel.vue';
import ActionsTab from './ActionsTab.vue';
import AreaEntryView from './AreaEntryView.vue';
import ProjectTab from './ProjectTab.vue';
import GroupCard from './GroupCard.vue';
import TenantCard from './TenantCard.vue';
import ToolTemplatesSection from './ToolTemplatesSection.vue';
import { resolveSettingsHelpPath } from './settingsHelp';
import { useProfile } from '@/composables/useProfile';
import { useAdminProjectGroups } from '@/composables/useAdminProjectGroups';
import { useAdminProjects } from '@/composables/useAdminProjects';
import { useProjectKitsCatalog } from '@/composables/useProjectKitsCatalog';
import { recallProject, rememberProject } from '@/platform/lastProject';
import { ensureAddonRemotesRegistered, ensureKindLoaded } from '@/platform/addonRegistry';
import { getTenantId, listSettingForms, RestError } from '@vance/shared';
import { brainFetch } from '@vance/shared';
import {
  listSettingsKinds,
  type SettingsAreaRow,
  type SettingsDocRow,
  type SettingsInheritedRow,
  type SettingsScope,
} from '@vance/kind-registry';
import type { SettingFormSummaryDto, ProjectDto, ProjectGroupSummary } from '@vance/generated';
import {
  parseSettingsView,
  pushSettingsView,
  replaceSettingsView,
  scopeKeyword,
  TENANT_SCOPE,
  USER_SCOPE,
  type SettingsView,
} from './settingsUrl';

/**
 * The Settings surface — one guided place for everything configurable in
 * a scope, in four tabs:
 *
 * <ul>
 *   <li><b>Bereiche</b> — settings-doc kinds (Research, Server tools, …) as
 *     areas: entry inventory with add/delete; an entry opens in its kind's
 *     own view right here, inline (no Cortex round-trip). Cascading kinds
 *     also list what the scope only inherits, with "override here".
 *   <li><b>Settings</b> — the guided Setting Forms, grouped by category.
 *   <li><b>Projekt/Mandant</b> (scope-gated, not for user scopes) — the
 *     management pane port from the Scopes page: tenant and project
 *     properties, project-group and session-group admin, project copy,
 *     languages, the kit administration and the tool templates. Sidebar
 *     edit mode provides create + drag-and-drop move.
 *   <li><b>Erweitert</b> — the free-form key/value editor.
 * </ul>
 *
 * <p>See {@code specification/public/settings-panel.md} and
 * {@code planning/settings-panel.md} (Strangler: the Scopes page stays
 * untouched until the cutover; this page reuses the same backends).
 * Navigation follows the Cortex contract: the URL is the state
 * ({@link ./settingsUrl.ts}).
 */

const { t } = useI18n();
const confirmAction = useConfirm();
const adminGroups = useAdminProjectGroups();
const adminProjects = useAdminProjects();
const profile = useProfile();
const projectKitsCatalog = useProjectKitsCatalog();

/** Success/info banner above the tab content (created, saved, …). */
const banner = ref<string | null>(null);

/** Tenant project name backing the tenant scope (settings-system.md §3). */
const TENANT_PROJECT = '_tenant';

/* Focus-driven zone model (spec web-ui.md §7.2.1) — the same wiring
 * every EditorShell page uses: small viewports collapse the rails to 0
 * and the reclaim handles bring them back. */
const focusZone = ref<FocusZone>('main');
const view = ref<SettingsView>({
  scope: TENANT_SCOPE,
  tab: 'forms',
  form: null,
  area: null,
  entry: null,
  group: null,
});

// ─── Forms tab state ───

const forms = ref<SettingFormSummaryDto[]>([]);
const formsLoading = ref(false);
const formsError = ref<string | null>(null);
/** Bumped after apply/reset so an open form re-fetches its cascade values. */
const formReloadKey = ref(0);

// ─── Bereiche tab state ───

/** Areas = settings-doc kinds, in registration order. */
const areas = ref<ReturnType<typeof listSettingsKinds>>(listSettingsKinds());

/**
 * Re-snapshots the area list. The kind registry is a plain {@code globalThis}
 * map — not reactive — so an addon's {@code register()} arriving after boot
 * cannot move a computed by itself. Refreshed once the boot sweep has run
 * the addon registers, which is the only way the list can grow mid-session.
 */
function refreshSettingsKinds(): void {
  areas.value = listSettingsKinds();
}
const areaRows = ref<SettingsAreaRow[]>([]);
const areaLoading = ref(false);
const areaError = ref<string | null>(null);
/** Add-dialog state, opened from the entry list of the open area. */
const showAddDialog = ref(false);
const newEntryName = ref('');
const addBusy = ref(false);
const addError = ref<string | null>(null);

// ─── Scope plumbing ───

/** Current user login — the user-scope wire referenceId. */
const login = computed<string>(() => profile.profile.value?.name ?? '');

/** The user project backing the user scope. */
const userProjectId = computed<string>(() => `_user_${login.value}`);

/** Wire scope descriptor the backends are called with. */
const scopeInfo = computed<{ kind: 'tenant' | 'user' | 'project'; projectId?: string; referenceId: string }>(() => {
  const kw = scopeKeyword(view.value.scope);
  if (kw === 'tenant') {
    return { kind: 'tenant', projectId: TENANT_PROJECT, referenceId: getTenantId() ?? '' };
  }
  if (kw === 'user') {
    return { kind: 'user', projectId: userProjectId.value, referenceId: login.value };
  }
  return { kind: 'project', projectId: view.value.scope, referenceId: view.value.scope };
});

/** The projectId for the Setting-Forms listing (undefined ⇒ tenant context). */
const formsProjectId = computed<string | undefined>(() => {
  if (scopeKeyword(view.value.scope) === 'tenant') return undefined;
  return scopeInfo.value.projectId;
});

/** The registry scope the area providers are asked with. */
const registryScope = computed<SettingsScope>(() => ({
  kind: scopeInfo.value.kind,
  projectId: scopeInfo.value.projectId ?? TENANT_PROJECT,
  login: login.value || undefined,
}));

/** The properties tab exists for tenant and project scopes, not for users. */
const isProjectScope = computed(() => scopeKeyword(view.value.scope) === null);
const isTenantScope = computed(() => scopeKeyword(view.value.scope) === 'tenant');
const showPropertiesTab = computed(() => isProjectScope.value || isTenantScope.value);

/**
 * The group row selected in the sidebar (properties tab, tenant scope).
 * Rendered only there — a stale URL `group` on other scopes is ignored.
 */
const activeGroup = computed(() => {
  if (!(isTenantScope.value && effectiveTab.value === 'properties')) return null;
  return view.value.group
    ? (adminGroups.groups.value.find((g) => g.name === view.value.group) ?? null)
    : null;
});

/** The actions tab runs operator commands — tenant and project scopes only. */
const showActionsTab = computed(() => isProjectScope.value || isTenantScope.value);

/**
 * The tab the content renders: `properties` and `actions` are scope-gated —
 * a URL that carries them for a scope where they don't apply (stale link,
 * back-button) falls back to the forms tab instead of rendering an empty shell.
 */
const effectiveTab = computed<SettingsView['tab']>(() =>
  (view.value.tab === 'properties' && !showPropertiesTab.value)
      || (view.value.tab === 'actions' && !showActionsTab.value)
    ? 'forms'
    : view.value.tab);

/** Properties-tab label: the scope it is on, not a generic word. */
const propertiesTabLabel = computed(() =>
  isProjectScope.value
    ? t('settings.tab.project')
    : t('settings.tab.tenant'));

const tabDefs = computed(() => {
  const defs: Array<{ id: SettingsView['tab']; label: string }> = [
    { id: 'areas', label: t('settings.tab.areas') },
    { id: 'forms', label: t('settings.tab.forms') },
  ];
  if (showPropertiesTab.value) {
    defs.push({ id: 'properties', label: propertiesTabLabel.value });
  }
  if (showActionsTab.value) {
    defs.push({ id: 'actions', label: t('settings.tab.actions') });
  }
  defs.push({ id: 'raw', label: t('settings.tab.raw') });
  return defs;
});
const activeForm = computed<SettingFormSummaryDto | null>(() => {
  if (!view.value.form) return null;
  return forms.value.find((f) => f.name === view.value.form) ?? null;
});

const activeArea = computed(() => {
  if (!view.value.area) return null;
  return areas.value.find((a) => a.id === view.value.area) ?? null;
});

/** Rows the scope owns — the only ones the host opens, edits or deletes. */
const ownedRows = computed<SettingsDocRow[]>(() =>
  areaRows.value.filter((r): r is SettingsDocRow => !r.inherited));

const activeEntry = computed<SettingsDocRow | null>(() => {
  if (!view.value.entry) return null;
  return ownedRows.value.find((r) => r.documentId === view.value.entry) ?? null;
});

/** Help path for the shell's help drawer (topbar "?") — flips with the open context. */
const helpPath = computed<string>(() =>
  resolveSettingsHelpPath(view.value, activeEntry.value?.kindId ?? null));

/** Breadcrumbs: scope label, then whatever level is open below it. */
const breadcrumbs = computed<string[]>(() => {
  const crumbs = [scopeLabel.value];
  if (activeArea.value && view.value.tab === 'areas') {
    crumbs.push(t(activeArea.value.settingsProvider!.titleKey));
    if (activeEntry.value) crumbs.push(activeEntry.value.title);
  } else if (activeForm.value && view.value.tab === 'forms') {
    crumbs.push(activeForm.value.title);
  }
  return crumbs;
});

/** Projects selectable in the sidebar — the hub project is not a settings scope. */
const selectableProjects = computed(() =>
  adminProjects.projects.value.filter((p) => !p.name.startsWith('_')));

const scopeLabel = computed<string>(() => {
  const kw = scopeKeyword(view.value.scope);
  if (kw === 'tenant') return t('settings.scope.tenant');
  if (kw === 'user') return t('settings.scope.user');
  const p = selectableProjects.value.find((x) => x.name === view.value.scope);
  return p ? (p.title || p.name) : view.value.scope;
});

/**
 * Sidebar selection for the shared ProjectListSidebar. The write side
 * turns a sidebar click into a scope navigation — ProjectListSidebar owns
 * no notion of scope keywords, it only knows project and group names:
 * a project row navigates to the project scope, a group row (edit mode)
 * opens the group card in the tenant scope's properties tab, and a null
 * selection is the tenant row.
 */
const sidebarNode = computed<PickerNode | null, PickerNode | null>({
  get: (): PickerNode | null => {
    if (activeGroup.value) return { kind: 'group', name: activeGroup.value.name };
    if (scopeKeyword(view.value.scope)) return null;
    return { kind: 'project', name: view.value.scope };
  },
  set: (node: PickerNode | null) => {
    if (node?.kind === 'group') selectGroup(node.name);
    else if (node?.kind === 'project') selectScope(node.name);
    else selectScope(TENANT_SCOPE);
  },
});

/**
 * Kit options for the create-project modal the sidebar renders in edit
 * mode — the tenant catalog, so admins can pick a kit at creation time
 * (project-kits-catalog.md).
 */
const pickerKitOptions = computed(() => [
  { value: '', label: t('common.projectPicker.createProject.kitNone') },
  ...(projectKitsCatalog.catalog.value?.kits ?? []).map(entry => ({
    value: entry.name,
    label: entry.title || entry.name,
  })),
]);

// ─── Loaders ───

async function loadForms(): Promise<void> {
  formsLoading.value = true;
  formsError.value = null;
  try {
    const res = await listSettingForms(formsProjectId.value);
    forms.value = res.forms ?? [];
    // A remembered form that is not in this scope's listing is stale —
    // drop it from the view instead of rendering a dead panel.
    if (view.value.form && !forms.value.some((f) => f.name === view.value.form)) {
      view.value = { ...view.value, form: null };
      replaceSettingsView(view.value);
    }
  } catch (err) {
    formsError.value = err instanceof RestError ? err.message : String(err);
    forms.value = [];
  } finally {
    formsLoading.value = false;
  }
}

async function loadAreaRows(): Promise<void> {
  if (!activeArea.value) {
    areaRows.value = [];
    return;
  }
  areaLoading.value = true;
  areaError.value = null;
  try {
    // Providers resolve failures themselves (empty list) — a rejection
    // here is a host-side bug, but it still must not kill the tab.
    areaRows.value = await activeArea.value.settingsProvider!.list(registryScope.value);
    if (view.value.entry
        && !ownedRows.value.some((r) => r.documentId === view.value.entry)) {
      view.value = { ...view.value, entry: null };
      replaceSettingsView(view.value);
    }
  } catch (err) {
    areaError.value = err instanceof RestError ? err.message : String(err);
    areaRows.value = [];
  } finally {
    areaLoading.value = false;
  }
}

async function loadForScope(): Promise<void> {
  const jobs: Array<Promise<void>> = [loadForms()];
  if (view.value.area) jobs.push(loadAreaRows());
  await Promise.all(jobs);
}

// ─── View transitions (each one pushes history) ───

function navigate(next: SettingsView): void {
  banner.value = null;
  view.value = next;
  pushSettingsView(next);
  void loadForScope();
}

function selectScope(scope: string): void {
  // Keep the tab (and the open area — it exists per layer) when hopping
  // scopes; never a stale form, entry or group — their context shifts with
  // the scope. The properties tab is scope-gated: leaving tenant/project
  // with it open lands on the forms tab, not on a guarded fallback.
  const nextTab = view.value.tab === 'properties' && !showPropertiesTabFor(scope)
    ? 'forms'
    : view.value.tab;
  navigate({
    scope,
    tab: nextTab,
    form: null,
    area: view.value.area,
    entry: null,
    group: null,
  });
  if (scopeKeyword(scope) === null) rememberProject(scope);
}

/** Scope-gating for a target scope (the computed answers for the current one). */
function showPropertiesTabFor(scope: string): boolean {
  return scopeKeyword(scope) !== 'user';
}

/** Opens a project-group row in the properties tab (tenant scope). */
function selectGroup(name: string): void {
  navigate({
    scope: TENANT_SCOPE,
    tab: 'properties',
    form: null,
    area: view.value.area,
    entry: null,
    group: name,
  });
}

/**
 * Create/move feedback from the sidebar's edit mode ({@code @data-changed})
 * — ported from the Scopes page: reload, select the fresh entry, and say
 * more than „created“ when placement is pending.
 */
async function onPickerDataChanged(
  payload: { kind: 'group' | 'project'; name: string },
): Promise<void> {
  await Promise.all([adminGroups.reload(), adminProjects.reload()]);
  if (payload.kind === 'group') {
    selectGroup(payload.name);
    banner.value = t('scopes.group.created', { name: payload.name });
  } else {
    selectScope(payload.name);
    // Creation can legitimately end in "accepted, not placed" — a selector
    // no live pod satisfies, or every matching pod full. Saying only
    // "created" there would leave the user waiting for a start that
    // needs a new pod first.
    const created = adminProjects.projects.value.find(p => p.name === payload.name);
    banner.value = created?.placementPendingSince
      ? t('scopes.project.createdPendingPlacement', { name: payload.name })
      : t('scopes.project.created', { name: payload.name });
  }
}

/** GroupCard: the group is gone — stay on tenant properties, no history hole. */
function onGroupDeleted(): void {
  view.value = { ...view.value, group: null };
  replaceSettingsView(view.value);
  void adminGroups.reload();
}

/** ProjectTab: jump to the project a copy report describes. */
function onOpenProject(name: string): void {
  void adminProjects.reload().then(() => selectScope(name));
}

function selectTab(tab: SettingsView['tab']): void {
  navigate({ ...view.value, tab });
}

function selectForm(name: string): void {
  navigate({ ...view.value, form: name });
}

function selectArea(kindId: string): void {
  navigate({ ...view.value, area: kindId, entry: null });
}

function selectEntry(documentId: string): void {
  navigate({ ...view.value, entry: documentId });
}

function backToFormsListing(): void {
  navigate({ ...view.value, form: null });
}

function backToAreas(): void {
  // From the entry list: up to the area list (area goes away too).
  // From an open entry use backToEntryList — one level at a time.
  navigate({ ...view.value, area: null, entry: null });
}

function backToEntryList(): void {
  navigate({ ...view.value, entry: null });
}

function onFormApplied(): void {
  formReloadKey.value += 1;
  void loadForms();
}

// ─── Bereiche actions ───

function openAddDialog(): void {
  newEntryName.value = '';
  addError.value = null;
  showAddDialog.value = true;
}

async function addEntry(): Promise<void> {
  const provider = activeArea.value?.settingsProvider;
  const name = newEntryName.value.trim();
  if (!provider?.create || !name) return;
  addBusy.value = true;
  addError.value = null;
  try {
    const row = await provider.create(registryScope.value, name);
    showAddDialog.value = false;
    await loadAreaRows();
    selectEntry(row.documentId);
  } catch (err) {
    addError.value = err instanceof RestError ? err.message : String(err);
  } finally {
    addBusy.value = false;
  }
}

/**
 * Copies an inherited entry into the scope and opens the copy — the one
 * thing the scope can do with an entry it does not own.
 */
async function overrideEntry(row: SettingsInheritedRow): Promise<void> {
  const provider = activeArea.value?.settingsProvider;
  if (!provider?.override) return;
  if (!await confirmAction({
    message: t('settings.areas.confirmOverride', {
      name: row.title,
      origin: t(`settings.areas.inherited.${row.inherited}`),
    }),
    confirmLabel: t('settings.areas.override'),
  })) return;
  areaLoading.value = true;
  areaError.value = null;
  try {
    const local = await provider.override(registryScope.value, row);
    await loadAreaRows();
    selectEntry(local.documentId);
  } catch (err) {
    areaError.value = err instanceof RestError ? err.message : String(err);
  } finally {
    areaLoading.value = false;
  }
}

async function deleteEntry(row: SettingsDocRow): Promise<void> {
  if (!await confirmAction({
    message: t('settings.areas.confirmDeleteEntry', { name: row.title }),
    confirmLabel: t('common.delete'),
  })) return;
  areaLoading.value = true;
  try {
    await brainFetch<void>(
      'DELETE', `documents/${encodeURIComponent(row.documentId)}`);
    if (view.value.entry === row.documentId) {
      navigate({ ...view.value, entry: null });
      return;
    }
    await loadAreaRows();
  } catch (err) {
    areaError.value = err instanceof RestError ? err.message : String(err);
  } finally {
    areaLoading.value = false;
  }
}

// ─── Browser history (back/forward re-parses the URL) ───

function onPopState(): void {
  view.value = parseSettingsView(window.location.search, view.value.scope);
  void loadForScope();
}

/**
 * Runs every addon register the manifest declares — the settings areas
 * ride along with the kind entries, so the sweep is what makes addon areas
 * appear. Failures are non-fatal by contract ({@code ensureKindLoaded}
 * logs and moves on); the epoch bump re-evaluates the area list either
 * way, so a failed addon simply contributes nothing.
 */
async function loadAddonAreaKinds(): Promise<void> {
  const index = await ensureAddonRemotesRegistered();
  const kindIds = [...index.keys()];
  if (kindIds.length === 0) return;
  await Promise.all(kindIds.map((id) => ensureKindLoaded(id)));
  refreshSettingsKinds();
}

onMounted(async () => {
  window.addEventListener('popstate', onPopState);
  await Promise.all([
    adminGroups.reload(),
    adminProjects.reload(),
    profile.load().catch(() => undefined),
    projectKitsCatalog.load().catch(() => undefined),
    // The area inventory needs the settings contributions of the addons, and
    // those live in the lazily loaded `./register` exposes. This is the one
    // surface that cannot wait for a kind to be opened first — so it loads
    // every manifest-declared kind owner once at boot, this page only.
    loadAddonAreaKinds(),
  ]);
  // URL first; a remembered project is the fallback; the tenant row is
  // the built-in default one click away at the top of the sidebar.
  const urlView = parseSettingsView(window.location.search, '');
  const remembered = recallProject(selectableProjects.value.map((p) => p.name));
  const initialScope =
    urlView.scope
    || (remembered && selectableProjects.value.some((p) => p.name === remembered)
      ? remembered
      : TENANT_SCOPE);
  view.value = { ...urlView, scope: initialScope };
  replaceSettingsView(view.value);
  await loadForScope();
});

onUnmounted(() => {
  window.removeEventListener('popstate', onPopState);
});

// A scope switch may invalidate the remembered area (e.g. an area whose
// provider went empty is fine, but a deleted kind entry is not) — the
// reload prunes stale state; this watcher only covers the area-kind list
// changing after profile load made the user row selectable.
watch(areas, () => {
  if (view.value.area && !areas.value.some((a) => a.id === view.value.area)) {
    view.value = { ...view.value, area: null, entry: null };
    replaceSettingsView(view.value);
  }
});

// ─── Forms inventory grouped by category ───

const groupedForms = computed<[string, SettingFormSummaryDto[]][]>(() => {
  const groups = new Map<string, SettingFormSummaryDto[]>();
  for (const f of forms.value) {
    const cat = f.category ?? '';
    if (!groups.has(cat)) groups.set(cat, []);
    groups.get(cat)!.push(f);
  }
  for (const list of groups.values()) {
    list.sort((a, b) => a.title.localeCompare(b.title));
  }
  return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));
});
</script>

<template>
  <EditorShell
    v-model:focus-zone="focusZone"
    focus-model="auto"
    :title="t('settings.pageTitle')"
    :breadcrumbs="breadcrumbs"
    :show-sidebar="true"
    :full-height="true"
    :help-path="helpPath"
  >
    <!-- ─── Sidebar: the three scope rows ─── -->
    <template #sidebar>
      <div class="flex flex-col">
        <nav class="flex flex-col gap-1 p-2">
          <button
            type="button"
            class="sidebar-item flex items-center gap-2"
            :class="{ 'sidebar-item--active': scopeKeyword(view.scope) === 'user' }"
            :disabled="!login"
            @click="selectScope(USER_SCOPE)"
          >
            <VIcon name="user" class="opacity-50" />{{ t('settings.sidebar.user') }}
          </button>
          <button
            type="button"
            class="sidebar-item flex items-center gap-2"
            :class="{ 'sidebar-item--active': scopeKeyword(view.scope) === 'tenant' }"
            @click="selectScope(TENANT_SCOPE)"
          >
            <VIcon name="tenant" class="opacity-50" />{{ t('settings.sidebar.tenant') }}
          </button>
        </nav>

        <ProjectListSidebar
          v-model:selected-node="sidebarNode"
          :groups="adminGroups.groups.value"
          :projects="selectableProjects"
          :loading="adminGroups.loading.value || adminProjects.loading.value"
          :error="adminGroups.error.value || adminProjects.error.value"
          :heading="t('settings.sidebar.projects')"
          :ungrouped-label="t('settings.sidebar.ungrouped')"
          :empty-headline="t('settings.sidebar.emptyHeadline')"
          :empty-body="t('settings.sidebar.emptyBody')"
          :kit-options="pickerKitOptions"
          search-enabled
          edit-enabled
          show-group-rows
          @focus-main="focusZone = 'main'"
          @data-changed="onPickerDataChanged"
        >
          <template #row-suffix="{ kind, item }">
            <span
              v-if="kind === 'group' && !(item as ProjectGroupSummary).enabled"
              class="opacity-60 text-xs"
            >{{ t('scopes.common.disabled') }}</span>
            <span
              v-else-if="kind === 'project' && (item as ProjectDto).status === 'ARCHIVED'"
              class="opacity-60 text-xs"
            >{{ t('scopes.common.archived') }}</span>
            <!-- Waiting for a pod. Below ARCHIVED on purpose: an archived
                 project is not waiting for anything, so that label wins. -->
            <span
              v-else-if="kind === 'project' && (item as ProjectDto).placementPendingSince"
              class="inline-flex items-center gap-1 text-xs text-(--v-warning)"
              :title="t('scopes.project.placementPendingNote')"
            ><VIcon name="waiting" />{{ t('scopes.project.placementPendingBadge') }}</span>
          </template>
        </ProjectListSidebar>
      </div>
    </template>

    <!-- ─── Main: scope header + tabs ─── -->
    <!-- full-height contract (EditorShell): the zone itself never
         scrolls — the page carries its own scroll body, so the rails
         keep their viewport height and the responsive collapse works. -->
    <div class="h-full min-h-0 flex flex-col settings-ground">
    <div class="flex-1 min-h-0 overflow-y-auto">
    <div class="settings-column">
      <div class="flex items-baseline justify-between gap-2">
        <h2 class="settings-title">{{ scopeLabel }}</h2>
        <span class="settings-ref">{{ scopeInfo.referenceId }}</span>
      </div>

      <VAlert v-if="banner" variant="success">
        <span>{{ banner }}</span>
      </VAlert>
      <div role="tablist" class="settings-tabs">
        <button
          v-for="tabDef in tabDefs"
          :key="tabDef.id"
          type="button"
          role="tab"
          :aria-selected="effectiveTab === tabDef.id"
          :class="['settings-tab', { 'settings-tab--active': effectiveTab === tabDef.id }]"
          @click="selectTab(tabDef.id)"
        >
          {{ tabDef.label }}
        </button>
      </div>

      <!-- ─── Tab: Bereiche (settings-doc kinds) ─── -->
      <template v-if="effectiveTab === 'areas'">
        <!-- Level 3: open entry, inline view -->
        <template v-if="activeArea && activeEntry">
          <div class="flex items-center gap-2">
            <VButton variant="plain" size="sm" icon="back" @click="backToEntryList">
              {{ t('settings.areas.backToEntries') }}
            </VButton>
          </div>
          <AreaEntryView
            :document-id="activeEntry.documentId"
            :kind-id="activeEntry.kindId"
            @close="backToEntryList"
          />
        </template>

        <!-- Level 2: entry list of the open area -->
        <template v-else-if="activeArea">
          <div class="flex items-center justify-between gap-2">
            <VButton variant="plain" size="sm" icon="back" @click="backToAreas">
              {{ t('settings.areas.backToAreas') }}
            </VButton>
            <VButton
              v-if="activeArea.settingsProvider?.create"
              variant="primary"
              size="sm"
              icon="add"
              @click="openAddDialog"
            >{{ t('settings.areas.addEntry') }}</VButton>
          </div>

          <h3 class="settings-section">
            {{ t(activeArea.settingsProvider!.titleKey) }}
          </h3>

          <VAlert v-if="areaError" variant="error">{{ areaError }}</VAlert>

          <VEmptyState
            v-else-if="!areaLoading && areaRows.length === 0"
            :headline="t('settings.areas.emptyHeadline')"
            :body="t('settings.areas.emptyBody')"
          />

          <VGroup v-if="areaRows.length > 0">
            <template v-for="row in areaRows" :key="row.inherited ? `inherited:${row.name}` : row.documentId">
              <!-- Inherited: seen through the cascade, owned by an outer
                   layer. Shown with its origin; the only action is a local
                   copy that shadows it in this scope. -->
              <VGroupRow v-if="row.inherited">
                <template #label>
                  <div class="flex items-center gap-2 min-w-0">
                    <span class="truncate font-medium">{{ row.title }}</span>
                    <VBadge size="sm">{{ t(`settings.areas.inherited.${row.inherited}`) }}</VBadge>
                  </div>
                  <div v-if="row.description" class="settings-row-sub truncate">{{ row.description }}</div>
                </template>
                <VButton
                  v-if="activeArea.settingsProvider?.override"
                  variant="secondary"
                  size="sm"
                  @click="overrideEntry(row)"
                >{{ t('settings.areas.override') }}</VButton>
              </VGroupRow>
              <VGroupRow
                v-else
                navigates
                @click="selectEntry(row.documentId)"
              >
                <template #label>
                  <div class="truncate font-medium">{{ row.title }}</div>
                  <div v-if="row.description" class="settings-row-sub truncate">{{ row.description }}</div>
                  <div v-if="row.path" class="settings-row-sub font-mono truncate">{{ row.path }}</div>
                </template>
                <VIconButton
                  icon="delete"
                  :label="t('settings.areas.deleteEntry')"
                  size="sm"
                  danger
                  @click.stop="deleteEntry(row)"
                />
              </VGroupRow>
            </template>
          </VGroup>
        </template>

        <!-- Level 1: area list -->
        <template v-else>
          <VEmptyState
            v-if="areas.length === 0"
            :headline="t('settings.areas.noneHeadline')"
            :body="t('settings.areas.noneBody')"
          />
          <VGroup v-if="areas.length > 0">
            <VGroupRow
              v-for="area in areas"
              :key="area.id"
              :label="t(area.settingsProvider!.titleKey)"
              navigates
              @click="selectArea(area.id)"
            />
          </VGroup>
        </template>
      </template>

      <!-- ─── Tab: Settings (guided forms) ─── -->
      <template v-else-if="effectiveTab === 'forms'">
        <template v-if="activeForm">
          <div class="flex items-center gap-2">
            <VButton variant="plain" size="sm" icon="back" @click="backToFormsListing">
              {{ t('settings.backToListing') }}
            </VButton>
          </div>
          <SettingFormView
            :name="activeForm.name"
            :project-id="formsProjectId"
            :reload-key="formReloadKey"
            @applied="onFormApplied"
          />
        </template>

        <template v-else>
          <VAlert v-if="formsError" variant="error">{{ formsError }}</VAlert>

          <VEmptyState
            v-else-if="!formsLoading && forms.length === 0"
            :headline="t('settings.emptyHeadline')"
            :body="t('settings.emptyBody')"
          />

          <VGroup
            v-for="[cat, group] in groupedForms"
            :key="cat"
            :label="cat || undefined"
          >
            <VGroupRow
              v-for="f in group"
              :key="f.name"
              navigates
              @click="selectForm(f.name)"
            >
              <template #label>
                <div class="truncate font-medium">{{ f.title }}</div>
                <div class="settings-row-sub line-clamp-2">{{ f.description }}</div>
              </template>
            </VGroupRow>
          </VGroup>
        </template>
      </template>

      <!-- ─── Tab: Projekt / Mandant / Gruppe (management pane port) ─── -->
      <template v-else-if="effectiveTab === 'properties' && showPropertiesTab">
        <GroupCard
          v-if="activeGroup"
          :name="activeGroup.name"
          :groups="adminGroups.groups.value"
          @banner="banner = $event"
          @deleted="onGroupDeleted"
        />
        <template v-else-if="isTenantScope">
          <TenantCard @banner="banner = $event" />
          <ToolTemplatesSection :project-name="TENANT_PROJECT" />
        </template>
        <ProjectTab
          v-else
          :project-name="view.scope"
          :groups="adminGroups.groups.value"
          @open-project="onOpenProject"
        />
      </template>

      <!-- ─── Tab: Aktionen (operator actions) ─── -->
      <template v-else-if="effectiveTab === 'actions'">
        <ActionsTab
          :scope-kind="scopeInfo.kind"
          :project-id="isProjectScope ? (scopeInfo.projectId ?? null) : null"
        />
      </template>

      <!-- ─── Tab: Erweitert (raw editor) ─── -->
      <template v-else>
        <p class="settings-row-sub">{{ t('settings.raw.hint') }}</p>
        <RawSettingsPanel
          :reference-type="scopeInfo.kind"
          :reference-id="scopeInfo.referenceId"
        />
      </template>
    </div>
    </div>
    </div>

    <!-- ─── Bereiche: add-entry dialog ─── -->
    <VModal
      v-model="showAddDialog"
      size="sm"
      :title="t(activeArea?.settingsProvider?.titleKey ?? 'settings.pageTitle')"
    >
      <form id="settings-add-entry-form" class="flex flex-col gap-4" @submit.prevent="addEntry">
        <VAlert v-if="addError" variant="error">{{ addError }}</VAlert>
        <VInput
          v-model="newEntryName"
          :label="t('settings.areas.nameLabel')"
          :placeholder="t('settings.areas.namePlaceholder')"
          :help="activeArea?.settingsProvider?.createHintKey
            ? t(activeArea.settingsProvider.createHintKey)
            : undefined"
        />
      </form>
      <template #actions>
        <VButton variant="secondary" @click="showAddDialog = false">
          {{ t('settings.raw.cancel') }}
        </VButton>
        <VButton
          type="submit"
          form="settings-add-entry-form"
          variant="primary"
          :disabled="!newEntryName.trim()"
          :loading="addBusy"
        >{{ t('settings.areas.addEntry') }}</VButton>
      </template>
    </VModal>
  </EditorShell>
</template>

<style scoped>
/* corporate-design §7 — grey ground, centred column, grouped lists. */
.settings-ground { background: var(--v-bg); }
.settings-column {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 720px;
  margin: 0 auto;
  padding: 28px 24px 48px;
}
.settings-title { margin: 0; font-size: 22px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em; }
.settings-ref { font-family: var(--v-font-mono); font-size: 12px; color: var(--v-label-tertiary); }
.settings-section { margin: 0; font-size: 15px; line-height: 20px; font-weight: 600; }
.settings-row-sub { font-size: 12px; line-height: 16px; color: var(--v-label-secondary); }
.settings-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--v-separator); }
.settings-tab {
  padding: 6px 10px;
  margin-bottom: -1px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  color: var(--v-label-secondary);
  cursor: pointer;
}
.settings-tab:hover { color: var(--v-label); }
.settings-tab--active { color: var(--v-label); border-bottom-color: var(--v-accent); }
.sidebar-item {
  text-align: left;
  padding: 0.375rem 0.5rem;
  border-radius: var(--v-radius-control);
  font-size: 0.875rem;
  transition: background-color var(--v-duration) ease-out;
}

.sidebar-item:hover {
  background-color: var(--v-fill);
}

.sidebar-item--active {
  background-color: var(--v-selection);
  color: var(--v-accent-text);
  font-weight: 500;
}

.sidebar-item:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
