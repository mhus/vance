<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  EditorShell,
  type FocusZone,
  type PickerNode,
  ProjectListSidebar,
  SettingFormView,
  VAlert,
  VButton,
  VCard,
  VCheckbox,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
  VTextarea,
} from '@/components';
import { listSettingForms, RestError, isEncryptedSettingType } from '@vance/shared';
import { useAdminTenant } from '@/composables/useAdminTenant';
import { useAdminProjectGroups } from '@/composables/useAdminProjectGroups';
import { useAdminProjects } from '@/composables/useAdminProjects';
import { useProjectKitsCatalog } from '@/composables/useProjectKitsCatalog';
import { useScopeSettings } from '@/composables/useScopeSettings';
import { useKitAdmin } from '@/composables/useKitAdmin';
import { useSessionGroups } from '@/composables/useSessionGroups';
import {
  KitImportMode,
  KitPolicyAction,
  SettingType,
  type KitImportRequestDto,
  type KitExportRequestDto,
  type KitConfigDto,
  type KitInstalledRecordDto,
  type KitLibraryEntryDto,
  type KitOriginDto,
  type ProjectCopyReportDto,
  type ProjectDto,
  type ProjectGroupSummary,
  type SessionGroupDto,
  type SettingDto,
  type SettingFormSummaryDto,
} from '@vance/generated';

type KitDialogMode = 'install' | 'update' | 'export';

const ARCHIVED_GROUP = 'archived';

type Selection =
  | { kind: 'tenant' }
  | { kind: 'group'; name: string }
  | { kind: 'project'; name: string };

const { t } = useI18n();
const tenantState = useAdminTenant();
const groupsState = useAdminProjectGroups();
const projectsState = useAdminProjects();
const settingsState = useScopeSettings();
const kitState = useKitAdmin();
const projectKitsCatalog = useProjectKitsCatalog();
const sessionGroupsState = useSessionGroups();

const selection = ref<Selection>({ kind: 'tenant' });
const banner = ref<string | null>(null);

// Focus zone driven by user interaction. Sidebar clicks land in
// 'main' so the detail card grows; right-panel interactions move
// focus to 'right' for inspect/edit work. Mirrors DocumentApp /
// ChatApp wiring — see specification/web-ui.md §7.2.1.
const focusZone = ref<FocusZone>('main');

// ─── Detail-form state ───
// One blob keyed off the selection — re-populated on every selection change.
const form = reactive({
  title: '',
  enabled: true,
  projectGroupId: null as string | null,
});

// ─── Modals ───
// Create-group / create-project modals now live inside the shared
// {@link ProjectListSidebar} component — see template. Scopes only
// reacts to the resulting {@code @data-changed} event to reload its
// own admin composables.

// ─── Kit dialog state ───
const showKitDialog = ref(false);
const kitDialogMode = ref<KitDialogMode>('install');
const kitForm = reactive({
  url: '',
  path: '',
  branch: '',
  commit: '',
  token: '',
  vaultPassword: '',
  prune: false,
  keepPasswords: false,
  // On (default) ⇒ install/update — the kit gets an install record and
  // stays updatable. Off ⇒ apply (one-off splat without tracking), used
  // for tunings that should not be managed at all.
  trackInstall: true,
  // Separate, opt-in role: mark this project as the *source* of the kit
  // so it can be edited here and exported. Off by default — the everyday
  // case is installing a kit, not authoring one.
  writeManifest: false,
  commitMessage: '',
});

// ─── Session-group editor state ───
// Session groups are per-user, per-project. Shown on the project card only
// (the selected project row is the scope). See planning/session-groups.md.
const newSessionGroupName = ref('');
const newSessionGroupTitle = ref('');
const editingSessionGroup = ref<string | null>(null);
const editingSessionGroupTitle = ref('');

// ─── Setting editor state ───
const newSettingKey = ref('');
const newSettingType = ref<SettingType>(SettingType.STRING);
const newSettingValue = ref('');
const newSettingDescription = ref('');
const editingKey = ref<string | null>(null);
const editValue = ref('');
const editDescription = ref('');

// ─── Setting Forms tab state ───
// Right-panel has two tabs for the selected scope: free-form raw settings
// editor (default) and YAML-driven Setting Forms (see specification/setting-forms.md).
type RightTab = 'settings' | 'forms';
const rightTab = ref<RightTab>('settings');
const settingFormsList = ref<SettingFormSummaryDto[]>([]);
const settingFormsLoading = ref(false);
const settingFormsError = ref<string | null>(null);
const selectedSettingForm = ref<string | null>(null);
const settingFormsReloadKey = ref<number>(0);

/**
 * Setting Forms only make sense in project-scoped views — the
 * bundled forms carry {@code availableIn: ["!_*"]} so they're hidden
 * from the tenant context anyway. We still load the listing when a
 * tenant is selected so the empty-state can explain why it's empty.
 */
const settingFormsProjectId = computed<string | undefined>(() => {
  if (settingsScope.value?.type === 'project') return settingsScope.value.id;
  return undefined;
});

async function loadSettingForms(): Promise<void> {
  if (!settingsScope.value) {
    settingFormsList.value = [];
    return;
  }
  settingFormsLoading.value = true;
  settingFormsError.value = null;
  try {
    const res = await listSettingForms(settingFormsProjectId.value);
    settingFormsList.value = res.forms ?? [];
    // Drop a stale selection if the form is no longer in the listing
    // (different project, scope-restricted, …).
    if (selectedSettingForm.value
        && !settingFormsList.value.some((f) => f.name === selectedSettingForm.value)) {
      selectedSettingForm.value = null;
    }
  } catch (err) {
    settingFormsError.value = err instanceof RestError ? err.message : String(err);
    settingFormsList.value = [];
  } finally {
    settingFormsLoading.value = false;
  }
}

function selectSettingForm(name: string): void {
  selectedSettingForm.value = name;
}

function backToSettingFormsList(): void {
  selectedSettingForm.value = null;
}

function onSettingFormApplied(): void {
  // Re-fetch the listing so a fresh apply that touches `availableIn`-relevant
  // settings (e.g. a kit install changing project metadata) re-resolves, and
  // bump the key so the active form refreshes its currentValue display.
  settingFormsReloadKey.value += 1;
  void loadSettingForms();
}

const groupedSettingForms = computed(() => {
  const groups = new Map<string, SettingFormSummaryDto[]>();
  for (const f of settingFormsList.value) {
    const cat = f.category ?? '';
    if (!groups.has(cat)) groups.set(cat, []);
    groups.get(cat)!.push(f);
  }
  for (const list of groups.values()) {
    list.sort((a, b) => a.title.localeCompare(b.title));
  }
  return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));
});

const settingTypeOptions = computed(() => [
  { value: SettingType.STRING, label: t('scopes.settingsPanel.types.string') },
  { value: SettingType.INT, label: t('scopes.settingsPanel.types.int') },
  { value: SettingType.LONG, label: t('scopes.settingsPanel.types.long') },
  { value: SettingType.DOUBLE, label: t('scopes.settingsPanel.types.double') },
  { value: SettingType.BOOLEAN, label: t('scopes.settingsPanel.types.boolean') },
  { value: SettingType.PASSWORD, label: t('scopes.settingsPanel.types.password') },
  { value: SettingType.HIDDEN, label: t('scopes.settingsPanel.types.hidden') },
]);

// ─── Derived state ───

const selectedGroup = computed<ProjectGroupSummary | null>(() => {
  const sel = selection.value;
  if (sel.kind !== 'group') return null;
  return groupsState.groups.value.find(g => g.name === sel.name) ?? null;
});

const selectedProject = computed<ProjectDto | null>(() => {
  const sel = selection.value;
  if (sel.kind !== 'project') return null;
  return projectsState.projects.value.find(p => p.name === sel.name) ?? null;
});

/**
 * The one sentence that explains a non-default lifecycle. AUTO needs none — it
 * is the default and defers to the derived ownerRequired, which the tenant has
 * no lever on either.
 */
const lifecycleNote = computed(() => {
  switch (selectedProject.value?.lifecycleType) {
    case 'PERMANENT':
      return t('scopes.project.lifecyclePermanentNote')
    case 'EPHEMERAL':
      return t('scopes.project.lifecycleEphemeralNote')
    default:
      return ''
  }
})

const groupSelectOptions = computed(() => [
  { value: '', label: t('scopes.common.noGroup') },
  ...groupsState.groups.value.map(g => ({ value: g.name, label: g.title || g.name })),
]);

const settingsScope = computed<{ type: string; id: string } | null>(() => {
  if (selection.value.kind === 'tenant' && tenantState.tenant.value) {
    return { type: 'tenant', id: tenantState.tenant.value.name };
  }
  if (selection.value.kind === 'project') {
    return { type: 'project', id: selection.value.name };
  }
  return null;
});

const isReservedGroup = computed(() =>
  selection.value.kind === 'group' && selection.value.name === ARCHIVED_GROUP);

const isArchivedProject = computed(() =>
  selectedProject.value?.status === 'ARCHIVED');

// ─── Lifecycle ───

onMounted(async () => {
  await Promise.all([
    tenantState.reload(),
    groupsState.reload(),
    projectsState.reload(),
    // Kit catalog feeds the create-project modal's kit-dropdown
    // (rendered inside the shared {@link ProjectListSidebar}).
    // Background load — the modal is rarely opened immediately
    // after mount, so this lands well before it's needed.
    projectKitsCatalog.load(),
  ]);
  // Selection defaults to tenant — populate the form once tenant is loaded.
  applySelectionToForm();
  loadSettingsForSelection();
  loadKitForSelection();
  loadSessionGroupsForSelection();
});

watch(selection, () => {
  applySelectionToForm();
  loadSettingsForSelection();
  loadKitForSelection();
  loadSessionGroupsForSelection();
});

watch(() => tenantState.tenant.value, () => {
  if (selection.value.kind === 'tenant') applySelectionToForm();
});

function applySelectionToForm(): void {
  const sel = selection.value;
  if (sel.kind === 'tenant') {
    const t = tenantState.tenant.value;
    form.title = t?.title ?? '';
    form.enabled = t?.enabled ?? true;
    form.projectGroupId = null;
  } else if (sel.kind === 'group') {
    const g = selectedGroup.value;
    form.title = g?.title ?? '';
    form.enabled = g?.enabled ?? true;
    form.projectGroupId = null;
  } else {
    const p = selectedProject.value;
    form.title = p?.title ?? '';
    form.enabled = p?.enabled ?? true;
    form.projectGroupId = p?.projectGroupId ?? null;
  }
}

function loadSettingsForSelection(): void {
  const scope = settingsScope.value;
  resetSettingEditor();
  // Drop the active Setting-Form when the scope changes — the cascade
  // context shifts, the live values would be misleading.
  selectedSettingForm.value = null;
  if (!scope) {
    settingsState.clear();
    settingFormsList.value = [];
    return;
  }
  void settingsState.load(scope.type, scope.id);
  void loadSettingForms();
}

function loadKitForSelection(): void {
  if (selection.value.kind !== 'project') {
    kitState.clear();
    return;
  }
  void kitState.load(selection.value.name);
}

function loadSessionGroupsForSelection(): void {
  cancelSessionGroupRename();
  newSessionGroupName.value = '';
  newSessionGroupTitle.value = '';
  if (selection.value.kind !== 'project') {
    sessionGroupsState.groups.value = [];
    return;
  }
  void sessionGroupsState.reload(selection.value.name);
}

function resetSettingEditor(): void {
  newSettingKey.value = '';
  newSettingType.value = SettingType.STRING;
  newSettingValue.value = '';
  newSettingDescription.value = '';
  editingKey.value = null;
  editValue.value = '';
  editDescription.value = '';
}

// ─── Selection actions ───
//
// Tenant click stays here because the tenant row lives outside the
// {@link ProjectListSidebar}. Group/project clicks go through the
// shared component which writes back via {@code pickerSelectedNode}.

function selectTenant(): void {
  selection.value = { kind: 'tenant' };
}

// ─── Detail-form submits ───

async function saveTenant(): Promise<void> {
  banner.value = null;
  try {
    await tenantState.save({
      title: form.title,
      enabled: form.enabled,
    });
    banner.value = t('scopes.tenant.saved');
  } catch {
    /* error already in tenantState.error */
  }
}

async function saveGroup(): Promise<void> {
  if (selection.value.kind !== 'group') return;
  banner.value = null;
  try {
    await groupsState.update(selection.value.name, {
      title: form.title,
      enabled: form.enabled,
    });
    banner.value = t('scopes.group.saved');
  } catch {
    /* state.error */
  }
}

async function deleteGroup(): Promise<void> {
  if (selection.value.kind !== 'group') return;
  if (!confirm(t('scopes.group.confirmDelete', { name: selection.value.name }))) return;
  const name = selection.value.name;
  try {
    await groupsState.remove(name);
    selectTenant();
    banner.value = t('scopes.group.deleted', { name });
  } catch {
    /* state.error */
  }
}

async function saveProject(): Promise<void> {
  if (selection.value.kind !== 'project') return;
  banner.value = null;
  const targetGroup = form.projectGroupId ?? '';
  try {
    await projectsState.update(selection.value.name, {
      title: form.title,
      enabled: form.enabled,
      projectGroupId: targetGroup === '' ? undefined : targetGroup,
      clearProjectGroup: targetGroup === '',
    });
    banner.value = t('scopes.project.saved');
  } catch {
    /* state.error */
  }
}

async function archiveProject(): Promise<void> {
  if (selection.value.kind !== 'project') return;
  if (!confirm(t('scopes.project.confirmArchive', { name: selection.value.name }))) return;
  try {
    await projectsState.archive(selection.value.name);
    banner.value = t('scopes.project.archived');
    // Stay on the project — its data is still there, just status=ARCHIVED.
    applySelectionToForm();
  } catch {
    /* state.error */
  }
}

// ─── Project copy ───

const showCopyDialog = ref(false);
const copyReport = ref<ProjectCopyReportDto | null>(null);
const copyForm = reactive({
  name: '',
  title: '',
  projectGroupId: '' as string,
  includeSecrets: false,
});

function openCopyDialog(): void {
  if (selection.value.kind !== 'project') return;
  const source = selectedProject.value;
  copyReport.value = null;
  projectsState.error.value = null;
  copyForm.name = `${selection.value.name}-copy`;
  copyForm.title = source?.title ? `${source.title} (Copy)` : '';
  copyForm.projectGroupId = source?.projectGroupId ?? '';
  copyForm.includeSecrets = false;
  showCopyDialog.value = true;
}

async function submitCopy(): Promise<void> {
  if (selection.value.kind !== 'project') return;
  const name = slugifyGroupName(copyForm.name);
  if (!name) return;
  try {
    copyReport.value = await projectsState.copy(selection.value.name, {
      name,
      title: copyForm.title.trim() === '' ? undefined : copyForm.title.trim(),
      projectGroupId: copyForm.projectGroupId === '' ? undefined : copyForm.projectGroupId,
      includeSecrets: copyForm.includeSecrets,
    });
    // The dialog stays open on purpose: the report is the point of the
    // operation, and closing on success would hide what was left behind.
  } catch {
    /* projectsState.error */
  }
}

/** Leaves the report behind and jumps to the project it describes. */
function openCopiedProject(): void {
  const name = copyReport.value?.project?.name;
  showCopyDialog.value = false;
  copyReport.value = null;
  if (name) selection.value = { kind: 'project', name };
}

// ─── Session-group actions ───

/**
 * Slugify a human-typed group name into the server's identifier shape
 * ({@code ^[a-z0-9][a-z0-9_-]*$}): lowercase, invalid runs → '-', must start
 * with an alphanumeric, no trailing separator. Mirrors the create-project
 * behaviour in {@link ProjectListSidebar}.
 */
function slugifyGroupName(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^[^a-z0-9]+/, '')
    .replace(/[-_]+$/, '');
}

async function createSessionGroupAction(): Promise<void> {
  if (selection.value.kind !== 'project') return;
  const raw = newSessionGroupName.value.trim();
  const name = slugifyGroupName(raw);
  if (!name) return;
  // If the user's original typing didn't survive slugification and they left
  // the title blank, promote the original spelling to the display title.
  const title = newSessionGroupTitle.value.trim() || (raw !== name ? raw : null);
  const projectId = selection.value.name;
  banner.value = null;
  try {
    await sessionGroupsState.create(projectId, name, title);
    newSessionGroupName.value = '';
    newSessionGroupTitle.value = '';
    banner.value = t('scopes.sessionGroups.created', { name });
  } catch {
    /* sessionGroupsState.error */
  }
}

function startEditSessionGroup(group: SessionGroupDto): void {
  editingSessionGroup.value = group.name;
  editingSessionGroupTitle.value = group.title ?? '';
}

function cancelSessionGroupRename(): void {
  editingSessionGroup.value = null;
  editingSessionGroupTitle.value = '';
}

async function saveSessionGroupRename(name: string): Promise<void> {
  if (selection.value.kind !== 'project') return;
  const projectId = selection.value.name;
  banner.value = null;
  try {
    await sessionGroupsState.rename(projectId, name, editingSessionGroupTitle.value.trim() || null);
    cancelSessionGroupRename();
    banner.value = t('scopes.sessionGroups.renamed');
  } catch {
    /* sessionGroupsState.error */
  }
}

async function deleteSessionGroupAction(name: string): Promise<void> {
  if (selection.value.kind !== 'project') return;
  if (!confirm(t('scopes.sessionGroups.confirmDelete', { name }))) return;
  const projectId = selection.value.name;
  banner.value = null;
  try {
    await sessionGroupsState.remove(projectId, name);
    banner.value = t('scopes.sessionGroups.deleted', { name });
  } catch {
    /* sessionGroupsState.error */
  }
}

// ─── Create modals / picker bridge ───
//
// The shared {@link ProjectListSidebar} component owns the create-
// group / create-project modals, the drag-and-drop move, and all the
// {@code admin/*} POST/PUT calls. Scopes only:
//   1. wires its tree selection through a writable computed v-model
//      so the picker can flip between group/project rows while the
//      tenant row (which lives outside the picker) maps to "no
//      picker selection";
//   2. exposes its kit catalog as options for the create-project
//      modal so admins can pick a kit at creation time;
//   3. reloads the admin composables on {@code @data-changed} and
//      auto-selects the freshly created entry.

const pickerSelectedNode = computed<PickerNode | null>({
  get: () => {
    const s = selection.value;
    if (s.kind === 'tenant') return null;
    return { kind: s.kind, name: s.name };
  },
  set: (v) => {
    if (v == null) selection.value = { kind: 'tenant' };
    else selection.value = v;
  },
});

const pickerKitOptions = computed(() => [
  { value: '', label: t('common.projectPicker.createProject.kitNone') },
  ...(projectKitsCatalog.catalog.value?.kits ?? []).map(entry => ({
    value: entry.name,
    label: entry.title || entry.name,
  })),
]);

async function onPickerDataChanged(
  payload: { kind: 'group' | 'project'; name: string },
): Promise<void> {
  if (payload.kind === 'group') {
    await groupsState.reload();
    selection.value = { kind: 'group', name: payload.name };
    banner.value = t('scopes.group.created', { name: payload.name });
  } else {
    await projectsState.reload();
    selection.value = { kind: 'project', name: payload.name };
    // Creation can legitimately end in "accepted, not placed" — a selector no
    // live pod satisfies, or every matching pod full. Saying only "created"
    // there would leave the user waiting for a start that needs a new pod
    // first (planning/project-placement-labels.md §7).
    const created = projectsState.projects.value.find(p => p.name === payload.name);
    banner.value = created?.placementPendingSince
      ? t('scopes.project.createdPendingPlacement', { name: payload.name })
      : t('scopes.project.created', { name: payload.name });
  }
}

// ─── Kit actions ───

const kitDialogTitle = computed(() => {
  switch (kitDialogMode.value) {
    case 'install': return t('scopes.kit.dialog.installTitle');
    case 'update': return t('scopes.kit.dialog.updateTitle');
    case 'export': return t('scopes.kit.dialog.exportTitle');
  }
});

const kitDialogSubmitLabel = computed(() => {
  switch (kitDialogMode.value) {
    case 'install': return t('scopes.kit.dialog.submitInstall');
    case 'update': return t('scopes.kit.dialog.submitUpdate');
    case 'export': return t('scopes.kit.dialog.submitExport');
  }
});

const kitNeedsUrl = computed(() => kitDialogMode.value === 'install');

function openKitDialog(mode: KitDialogMode, origin?: KitOriginDto): void {
  kitDialogMode.value = mode;
  kitForm.url = '';
  kitForm.path = '';
  kitForm.branch = '';
  kitForm.commit = '';
  kitForm.token = '';
  kitForm.vaultPassword = '';
  kitForm.prune = false;
  kitForm.keepPasswords = false;
  kitForm.trackInstall = true;
  kitForm.writeManifest = false;
  kitForm.commitMessage = '';

  // Pre-fill the source: from the kit being updated, or — for export —
  // from the authoring manifest that says what this project is.
  const prefill = origin ?? (mode === 'export' ? kitState.manifest.value?.origin : undefined);
  if (prefill) {
    kitForm.url = prefill.url ?? '';
    kitForm.path = prefill.path ?? '';
    kitForm.branch = prefill.branch ?? '';
  }
  showKitDialog.value = true;
  // Only for install: update and export already know their source. The
  // library answers straight away now — the credential is a server-side
  // setting, so there is nothing to wait for the user to type.
  if (mode === 'install') void loadLibrary();
}

/**
 * Update one installed kit. Goes through the same dialog as any other
 * import because the knobs are the same — a private repo still needs a
 * token, a kit with secrets still needs the vault passphrase — only the
 * source is already known.
 */
function updateInstalledKit(record: KitInstalledRecordDto): void {
  openKitDialog('update', record.origin);
}

/**
 * Update every installed kit in one go. Uses the plain path — no token,
 * no vault passphrase — because those differ per kit and cannot be
 * meaningfully asked for once; a kit that needs them is updated singly.
 */
// ── library picker ───────────────────────────────────────────────────
//
// Only shown when a library actually answers. A tenant with no libraries
// configured — the common case today — sees the plain url form and no
// hint that something is missing.
const libraryEntries = ref<KitLibraryEntryDto[]>([]);
const libraryLoading = ref(false);

async function loadLibrary(): Promise<void> {
  if (selection.value.kind !== 'project') return;
  libraryEntries.value = [];
  libraryLoading.value = true;
  try {
    libraryEntries.value = await kitState.loadLibrary(selection.value.name);
  } catch {
    // Not reachable, not configured, not signed in — all of them mean the
    // same thing here: no picker. The url form is still there.
  } finally {
    libraryLoading.value = false;
  }
}

/** Fill the source fields from a library row, so nothing has to be typed. */
function pickFromLibrary(entry: KitLibraryEntryDto): void {
  kitForm.url = entry.sourceUrl;
  // The entry's path already addresses the kit inside its library —
  // vendor and id together. Rebuilding it here would be a second place
  // to get it wrong.
  kitForm.path = entry.path;
  kitForm.branch = '';
  kitForm.commit = '';
}

async function updateAllKits(): Promise<void> {
  if (selection.value.kind !== 'project') return;
  banner.value = null;
  try {
    const results = await kitState.updateAll(selection.value.name, false);
    banner.value = t('scopes.kit.updatedAll_msg', { count: results.length });
  } catch {
    /* error already in kitState.error */
  }
}

/**
 * Where a purchased kit stands: expired, close to it, or neither.
 *
 * <p>Kits without a licence date — everything from git — return null and
 * render nothing at all.
 */
function licenceExpiry(record: KitInstalledRecordDto): 'expired' | 'soon' | null {
  const raw = record.descriptor?.licenseExpiresAt;
  if (!raw) return null;
  const expires = new Date(raw).getTime();
  if (Number.isNaN(expires)) return null;
  const now = Date.now();
  if (expires <= now) return 'expired';
  // Four weeks: long enough to renew without hurry, short enough that the
  // notice still means something when it appears.
  return expires - now <= 28 * 24 * 60 * 60 * 1000 ? 'soon' : null;
}

async function uninstallKit(record: KitInstalledRecordDto): Promise<void> {
  if (selection.value.kind !== 'project') return;
  if (!confirm(t('scopes.kit.confirmUninstall', { name: record.kit.name }))) return;
  // Asked separately and defaulting to no: forgetting a kit is cheap to
  // undo, deleting the files it brought is not.
  const prune = confirm(t('scopes.kit.confirmUninstallPrune', { name: record.kit.name }));
  banner.value = null;
  try {
    await kitState.uninstall(selection.value.name, record.id, prune);
    banner.value = t('scopes.kit.uninstalled_msg', { name: record.kit.name });
  } catch {
    /* error already in kitState.error */
  }
}

async function promoteKit(record: KitInstalledRecordDto): Promise<void> {
  if (selection.value.kind !== 'project') return;
  if (!confirm(t('scopes.kit.confirmPromote', { name: record.kit.name }))) return;
  banner.value = null;
  try {
    await kitState.promote(selection.value.name, record.id);
    banner.value = t('scopes.kit.promoted_msg', { name: record.kit.name });
  } catch {
    /* error already in kitState.error */
  }
}

// ── per-kit config (update policy + layer order) ──────────────────────

const showKitConfigDialog = ref(false);
const kitConfigRecord = ref<KitInstalledRecordDto | null>(null);
const kitConfigForm = reactive({
  sortIndex: '',
  defaultAction: KitPolicyAction.KEEP as KitPolicyAction,
  rules: [] as { namespace: 'document' | 'setting'; pattern: string; action: KitPolicyAction }[],
});

const kitPolicyActionOptions = computed(() => [
  { value: KitPolicyAction.KEEP, label: t('scopes.kit.config.actionKeep') },
  { value: KitPolicyAction.OVERWRITE, label: t('scopes.kit.config.actionOverwrite') },
  { value: KitPolicyAction.IGNORE, label: t('scopes.kit.config.actionIgnore') },
  { value: KitPolicyAction.MERGE, label: t('scopes.kit.config.actionMerge') },
]);

const kitPolicyNamespaceOptions = computed(() => [
  { value: 'document', label: t('scopes.kit.config.namespaceDocument') },
  { value: 'setting', label: t('scopes.kit.config.namespaceSetting') },
]);

async function openKitConfigDialog(record: KitInstalledRecordDto): Promise<void> {
  if (selection.value.kind !== 'project') return;
  kitConfigRecord.value = record;
  try {
    const config = await kitState.loadConfig(selection.value.name, record.id);
    kitConfigForm.sortIndex = config.sortIndex == null ? '' : String(config.sortIndex);
    kitConfigForm.defaultAction = config.policy?.defaultAction ?? KitPolicyAction.KEEP;
    kitConfigForm.rules = (config.policy?.rules ?? []).map((rule) => ({
      namespace: rule.setting != null ? 'setting' : 'document',
      pattern: rule.setting ?? rule.document ?? '',
      action: rule.action,
    }));
    showKitConfigDialog.value = true;
  } catch {
    /* error already in kitState.error */
  }
}

function addKitPolicyRule(): void {
  kitConfigForm.rules.push({
    namespace: 'document',
    pattern: '',
    action: KitPolicyAction.KEEP,
  });
}

function removeKitPolicyRule(index: number): void {
  kitConfigForm.rules.splice(index, 1);
}

async function submitKitConfig(): Promise<void> {
  if (selection.value.kind !== 'project' || !kitConfigRecord.value) return;
  const trimmed = kitConfigForm.sortIndex.trim();
  const config: KitConfigDto = {
    sortIndex: trimmed === '' ? undefined : Number(trimmed),
    policy: {
      defaultAction: kitConfigForm.defaultAction,
      // Empty patterns would match nothing and only confuse the reader
      // of the resulting YAML.
      rules: kitConfigForm.rules
        .filter((rule) => rule.pattern.trim() !== '')
        .map((rule) => ({
          document: rule.namespace === 'document' ? rule.pattern.trim() : undefined,
          setting: rule.namespace === 'setting' ? rule.pattern.trim() : undefined,
          action: rule.action,
        })),
    },
  };
  banner.value = null;
  try {
    await kitState.saveConfig(selection.value.name, kitConfigRecord.value.id, config);
    showKitConfigDialog.value = false;
    banner.value = t('scopes.kit.config.saved_msg');
  } catch {
    /* error already in kitState.error */
  }
}

async function submitKitDialog(): Promise<void> {
  if (selection.value.kind !== 'project') return;
  const projectId = selection.value.name;
  banner.value = null;
  try {
    if (kitDialogMode.value === 'export') {
      const request: KitExportRequestDto = {
        projectId,
        url: kitForm.url || undefined,
        path: kitForm.path || undefined,
        branch: kitForm.branch || undefined,
        token: kitForm.token || undefined,
        vaultPassword: kitForm.vaultPassword || undefined,
        commitMessage: kitForm.commitMessage || undefined,
      };
      await kitState.export(projectId, request);
      banner.value = t('scopes.kit.exported_msg');
    } else {
      const request: KitImportRequestDto = {
        projectId,
        source: {
          url: kitForm.url,
          path: kitForm.path || undefined,
          branch: kitForm.branch || undefined,
          commit: kitForm.commit || undefined,
        },
        token: kitForm.token || undefined,
        vaultPassword: kitForm.vaultPassword || undefined,
        // Real mode is forced server-side via the URL verb; this is just
        // a placeholder so the DTO type is satisfied.
        mode: KitImportMode.INSTALL,
        prune: kitForm.prune,
        keepPasswords: kitForm.keepPasswords,
        writeManifest: kitForm.writeManifest,
        // Empty by definition here: params carries what a provisioning entry
        // asks its source for, and this dialog is the hand-typed install —
        // there is no provisioning entry behind it. Server-side the field
        // already defaults to an empty map; it is required in the generated
        // type only because the generator cannot see @Builder.Default.
        params: {},
      };
      // trackInstall=false ⇒ the user wants a one-off splat, which is
      // exactly what `apply` does server-side: no record, no diff, no
      // update path.
      if (!kitForm.trackInstall) {
        await kitState.apply(projectId, request);
        banner.value = t('scopes.kit.applied_msg');
      } else if (kitDialogMode.value === 'install') {
        await kitState.install(projectId, request);
        banner.value = t('scopes.kit.installed_msg');
      } else {
        await kitState.update(projectId, request);
        banner.value = t('scopes.kit.updated_msg');
      }
    }
    showKitDialog.value = false;
  } catch {
    /* error already in kitState.error */
  }
}

// ─── Project-level language pickers ───
//
// Two settings, both surfaced via dedicated dropdowns on the project
// card so users don't have to know the key names (chat.language /
// content.language) and don't have to bother with the type=STRING
// row in the generic settings panel. Behaviour:
//
//   "Not set" (empty value) → DELETE the setting → cascade falls
//     through to the tenant (then LanguageResolver.DEFAULT_LANGUAGE).
//   Any concrete code → upsert as STRING.
//
// Cascade scopes (see LanguageResolver):
//   chat.language    : project → user → tenant
//   content.language : project → tenant
//
// The user-level chat.language lives on the profile page; this is
// the project override on top of it.

const LANGUAGE_OPTIONS_KEYS: readonly string[] = ['de', 'en', 'fr', 'es', 'it', 'pl'] as const;
const LANGUAGE_OPTIONS_LABELS: Record<string, string> = {
  de: 'Deutsch',
  en: 'English',
  fr: 'Français',
  es: 'Español',
  it: 'Italiano',
  pl: 'Polski',
};

const projectLanguageOptions = computed(() => [
  { value: '', label: t('scopes.project.languageNotSet') },
  ...LANGUAGE_OPTIONS_KEYS.map(k => ({ value: k, label: LANGUAGE_OPTIONS_LABELS[k] })),
]);

function settingValueByKey(key: string): string {
  const hit = settingsState.settings.value.find(s => s.key === key);
  return hit?.value ?? '';
}

const projectChatLanguage = computed<string>(() => settingValueByKey('chat.language'));
const projectContentLanguage = computed<string>(() => settingValueByKey('content.language'));

async function setProjectLanguageSetting(key: string, value: string | null): Promise<void> {
  const scope = settingsScope.value;
  if (!scope || scope.type !== 'project') return;
  try {
    if (value === null || value === '') {
      // No-op when there's nothing to delete — saves a 404 round-trip
      // and a spurious error in {@link useScopeSettings.remove}.
      if (!settingsState.settings.value.some(s => s.key === key)) return;
      await settingsState.remove(scope.type, scope.id, key);
    } else {
      await settingsState.upsert(scope.type, scope.id, key, value, SettingType.STRING, null);
    }
  } catch {
    /* settingsState.error already surfaces via the panel error banner */
  }
}

function onProjectChatLanguageChanged(value: string | null): void {
  void setProjectLanguageSetting('chat.language', value);
}

function onProjectContentLanguageChanged(value: string | null): void {
  void setProjectLanguageSetting('content.language', value);
}

// ─── Settings actions ───

/**
 * Display label for a setting's value. A null value and an empty string mean
 * different things in the cascade — null keeps falling through to the outer
 * scope, "" stops the cascade here — so they must not render identically.
 */
function settingValueLabel(s: SettingDto): string {
  if (s.value === null || s.value === undefined) return t('scopes.common.empty');
  if (s.value === '') return t('scopes.settingsPanel.explicitEmpty');
  return s.value;
}

async function addSetting(): Promise<void> {
  const scope = settingsScope.value;
  const key = newSettingKey.value.trim();
  if (!scope || !key) return;
  try {
    await settingsState.upsert(
      scope.type, scope.id, key,
      // Only an empty PASSWORD means "no value": it creates the row without
      // storing a secret. For every other type an empty value is a deliberate
      // "explicitly empty here" — it must persist as "" so the cascade stops
      // at this scope instead of falling through to the outer layer. Sending
      // null would store null, which keeps cascading.
      isEncryptedSettingType(newSettingType.value) && newSettingValue.value === ''
        ? null : newSettingValue.value,
      newSettingType.value,
      newSettingDescription.value.trim() || null,
    );
    resetSettingEditor();
  } catch {
    /* state.error */
  }
}

function startEditSetting(s: SettingDto): void {
  editingKey.value = s.key;
  // Password values come back masked as "[set]" — clear the edit field so the
  // operator types a fresh password instead of editing the mask.
  editValue.value = isEncryptedSettingType(s.type) ? '' : (s.value ?? '');
  editDescription.value = s.description ?? '';
}

async function saveEditSetting(s: SettingDto): Promise<void> {
  const scope = settingsScope.value;
  if (!scope) return;
  try {
    await settingsState.upsert(
      scope.type, scope.id, s.key,
      editValue.value === '' && isEncryptedSettingType(s.type) ? null : editValue.value,
      s.type,
      editDescription.value || null,
    );
    editingKey.value = null;
  } catch {
    /* state.error */
  }
}

function cancelEditSetting(): void {
  editingKey.value = null;
}

async function deleteSetting(s: SettingDto): Promise<void> {
  const scope = settingsScope.value;
  if (!scope) return;
  if (!confirm(t('scopes.settingsPanel.confirmDelete', { key: s.key }))) return;
  try {
    await settingsState.remove(scope.type, scope.id, s.key);
  } catch {
    /* state.error */
  }
}

// ─── Helpers for the template ───

function groupTitle(name: string): string {
  const g = groupsState.groups.value.find(x => x.name === name);
  return g?.title || g?.name || name;
}

function isSelected(s: Selection): boolean {
  const cur = selection.value;
  if (cur.kind !== s.kind) return false;
  if (cur.kind === 'tenant') return true;
  if (cur.kind === 'group' && s.kind === 'group') return cur.name === s.name;
  if (cur.kind === 'project' && s.kind === 'project') return cur.name === s.name;
  return false;
}

const breadcrumbs = computed<string[]>(() => {
  const tenantLabel = tenantState.tenant.value?.title || tenantState.tenant.value?.name || '';
  const sel = selection.value;
  if (sel.kind === 'tenant') return [tenantLabel];
  if (sel.kind === 'group') {
    return [tenantLabel, t('scopes.breadcrumbs.groupPrefix', { name: groupTitle(sel.name) })];
  }
  return [
    tenantLabel,
    t('scopes.breadcrumbs.projectPrefix', { name: selectedProject.value?.title || sel.name }),
  ];
});

const combinedError = computed<string | null>(() =>
  tenantState.error.value
  || groupsState.error.value
  || projectsState.error.value
  || settingsState.error.value
  || kitState.error.value
  || sessionGroupsState.error.value);
</script>

<template>
  <EditorShell
    v-model:focus-zone="focusZone"
    :title="$t('scopes.pageTitle')"
    :breadcrumbs="breadcrumbs"
    :full-height="true"
    :show-sidebar="true"
    :show-right-panel="!!settingsScope"
    focus-model="auto"
    title-clickable
    wide-right-panel
    @title-click="focusZone = 'sidebar'"
  >
    <!-- ─── Sidebar tree ───
         Tenant row stays scopes-specific (the picker has no notion
         of a tenant level); group + project rows + create/move
         live in the shared {@link ProjectListSidebar}. -->
    <template #sidebar>
      <div class="flex flex-col">
        <nav class="flex flex-col gap-1 p-2">
          <button
            class="sidebar-item"
            :class="{ 'sidebar-item--active': isSelected({ kind: 'tenant' }) }"
            type="button"
            @pointerdown.stop="focusZone = 'main'"
            @click="selectTenant"
          >
            <span class="opacity-50 mr-1">⌂</span>{{ $t('scopes.sidebar.tenant') }}
            <span v-if="tenantState.tenant.value" class="opacity-60">
              · {{ tenantState.tenant.value.name }}
            </span>
          </button>
        </nav>

        <ProjectListSidebar
          v-model:selected-node="pickerSelectedNode"
          :groups="groupsState.groups.value"
          :projects="projectsState.projects.value"
          :loading="groupsState.loading.value || projectsState.loading.value"
          :error="groupsState.error.value || projectsState.error.value"
          :heading="$t('scopes.sidebar.projectGroups')"
          :ungrouped-label="$t('scopes.sidebar.ungroupedProjects')"
          :kit-options="pickerKitOptions"
          :search-enabled="false"
          edit-enabled
          show-group-rows
          @focus-main="focusZone = 'main'"
          @data-changed="onPickerDataChanged"
        >
          <template #row-suffix="{ kind, item }">
            <span
              v-if="kind === 'group' && !(item as ProjectGroupSummary).enabled"
              class="opacity-60 text-xs"
            >{{ $t('scopes.common.disabled') }}</span>
            <span
              v-else-if="kind === 'project' && (item as ProjectDto).status === 'ARCHIVED'"
              class="opacity-60 text-xs"
            >{{ $t('scopes.common.archived') }}</span>
            <!-- Waiting for a pod. Below ARCHIVED on purpose: an archived
                 project is not waiting for anything, so that label wins. -->
            <span
              v-else-if="kind === 'project' && (item as ProjectDto).placementPendingSince"
              class="text-xs text-warning"
              :title="$t('scopes.project.placementPendingNote')"
            >⏳ {{ $t('scopes.project.placementPendingBadge') }}</span>
          </template>
        </ProjectListSidebar>
      </div>
    </template>

    <!-- ─── Main detail pane ───
         {@code full-height} on EditorShell pins the main cell to
         the viewport height; without an inner overflow-y the long
         setting/kit cards push past the bottom edge. Wrapped in a
         {@code overflow-y-auto} container so the cards scroll
         independently of the sidebar / right panel. -->
    <div class="h-full min-h-0 overflow-y-auto">
      <div class="p-6 max-w-2xl flex flex-col gap-3">
      <VAlert v-if="combinedError" variant="error">
        <span>{{ combinedError }}</span>
      </VAlert>
      <VAlert v-if="banner" variant="success">
        <span>{{ banner }}</span>
      </VAlert>

      <!-- Tenant -->
      <VCard v-if="selection.kind === 'tenant'" :title="$t('scopes.tenant.cardTitle')">
        <div v-if="!tenantState.tenant.value" class="opacity-70">{{ $t('scopes.loading') }}</div>
        <div v-else class="flex flex-col gap-3">
          <VInput
            :model-value="tenantState.tenant.value.name"
            :label="$t('scopes.common.name')"
            disabled
            :help="$t('scopes.tenant.nameImmutable')"
            @update:model-value="() => {}"
          />
          <VInput v-model="form.title" :label="$t('scopes.common.title')" />
          <VCheckbox v-model="form.enabled" :label="$t('scopes.common.enabled')" />
          <div class="flex justify-end">
            <VButton variant="primary" :loading="tenantState.saving.value" @click="saveTenant">
              {{ $t('scopes.common.save') }}
            </VButton>
          </div>
        </div>
      </VCard>

      <!-- Group -->
      <VCard
        v-else-if="selection.kind === 'group'"
        :title="$t('scopes.group.cardTitle', { name: selection.name })"
      >
        <VAlert v-if="isReservedGroup" variant="info" class="mb-3">
          <span>{{ $t('scopes.group.reservedNote') }}</span>
        </VAlert>
        <div v-if="!selectedGroup" class="opacity-70">{{ $t('scopes.loading') }}</div>
        <div v-else class="flex flex-col gap-3">
          <VInput
            :model-value="selectedGroup.name"
            :label="$t('scopes.common.name')"
            disabled
            :help="$t('scopes.group.nameImmutable')"
            @update:model-value="() => {}"
          />
          <VInput v-model="form.title" :label="$t('scopes.common.title')" />
          <VCheckbox v-model="form.enabled" :label="$t('scopes.common.enabled')" />
          <div class="flex justify-between">
            <VButton
              variant="danger"
              :disabled="isReservedGroup"
              :loading="groupsState.busy.value"
              @click="deleteGroup"
            >{{ $t('scopes.group.delete') }}</VButton>
            <VButton variant="primary" :loading="groupsState.busy.value" @click="saveGroup">
              {{ $t('scopes.common.save') }}
            </VButton>
          </div>
        </div>
      </VCard>

      <!-- Project -->
      <VCard
        v-else-if="selection.kind === 'project'"
        :title="$t('scopes.project.cardTitle', { name: selection.name })"
      >
        <VAlert v-if="isArchivedProject" variant="warning" class="mb-3">
          <span>{{ $t('scopes.project.archivedNote') }}</span>
        </VAlert>
        <div v-if="!selectedProject" class="opacity-70">{{ $t('scopes.loading') }}</div>
        <div v-else class="flex flex-col gap-3">
          <VInput
            :model-value="selectedProject.name"
            :label="$t('scopes.common.name')"
            disabled
            :help="$t('scopes.project.nameImmutable')"
            @update:model-value="() => {}"
          />
          <VInput v-model="form.title" :label="$t('scopes.common.title')" />
          <!-- The one place where this state looked like a broken create: the
               project exists, is correct, and simply has nowhere to run yet. -->
          <VAlert v-if="selectedProject.placementPendingSince" variant="warning">
            <span>{{ $t('scopes.project.placementPendingNote') }}</span>
          </VAlert>
          <VSelect
            v-model="form.projectGroupId"
            :label="$t('scopes.project.groupLabel')"
            :options="groupSelectOptions"
          />
          <VCheckbox v-model="form.enabled" :label="$t('scopes.common.enabled')" />
          <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80">
            <dt class="opacity-60">{{ $t('scopes.project.statusLabel') }}</dt>
            <dd>{{ selectedProject.status }}</dd>
            <dt class="opacity-60">{{ $t('scopes.project.podLabel') }}</dt>
            <dd>{{ selectedProject.homeNode ?? $t('scopes.common.none') }}</dd>
            <dt class="opacity-60">{{ $t('scopes.project.claimedLabel') }}</dt>
            <dd>{{ selectedProject.claimedAt ?? $t('scopes.common.none') }}</dd>
            <!-- Read-only, and that is the decision, not a gap: what this
                 changes is capacity in the cluster, not content in the project,
                 so it is written with the operator's token (anus
                 `project lifecycle-type`). Shown because the two non-default
                 values are facts a tenant has to be able to explain — above
                 all EPHEMERAL, which silently stops the project's scheduler. -->
            <dt class="opacity-60">{{ $t('scopes.project.lifecycleLabel') }}</dt>
            <dd :class="{ 'text-warning': selectedProject.lifecycleType === 'EPHEMERAL' }">
              {{ selectedProject.lifecycleType ?? $t('scopes.common.none') }}
              <span v-if="lifecycleNote" class="opacity-70">— {{ lifecycleNote }}</span>
            </dd>
            <template v-if="selectedProject.placementPendingSince">
              <dt class="opacity-60">{{ $t('scopes.project.placementPendingLabel') }}</dt>
              <dd class="text-warning">{{ selectedProject.placementPendingSince }}</dd>
            </template>
            <dt class="opacity-60">{{ $t('scopes.project.createdLabel') }}</dt>
            <dd>{{ selectedProject.createdAt ?? $t('scopes.common.none') }}</dd>
          </dl>
          <div class="flex justify-between">
            <VButton
              variant="danger"
              :disabled="isArchivedProject"
              :loading="projectsState.busy.value"
              @click="archiveProject"
            >{{ $t('scopes.project.archive') }}</VButton>
            <div class="flex gap-2">
              <VButton
                variant="ghost"
                :loading="projectsState.busy.value"
                @click="openCopyDialog"
              >{{ $t('scopes.project.copy.action') }}</VButton>
              <VButton variant="primary" :loading="projectsState.busy.value" @click="saveProject">
                {{ $t('scopes.common.save') }}
              </VButton>
            </div>
          </div>
        </div>
      </VCard>

      <!-- Languages -->
      <VCard
        v-if="selection.kind === 'project' && selectedProject"
        :title="$t('scopes.project.languagesCardTitle')"
      >
        <p class="text-sm opacity-70 mb-3">
          {{ $t('scopes.project.languagesDescription') }}
        </p>
        <div class="flex flex-col gap-3">
          <VSelect
            :model-value="projectChatLanguage"
            :options="projectLanguageOptions"
            :label="$t('scopes.project.chatLanguageLabel')"
            :disabled="settingsState.busy.value || isArchivedProject"
            @update:model-value="onProjectChatLanguageChanged"
          />
          <p class="text-xs opacity-60 -mt-2">
            {{ $t('scopes.project.chatLanguageHelp') }}
          </p>
          <VSelect
            :model-value="projectContentLanguage"
            :options="projectLanguageOptions"
            :label="$t('scopes.project.contentLanguageLabel')"
            :disabled="settingsState.busy.value || isArchivedProject"
            @update:model-value="onProjectContentLanguageChanged"
          />
          <p class="text-xs opacity-60 -mt-2">
            {{ $t('scopes.project.contentLanguageHelp') }}
          </p>
        </div>
      </VCard>

      <!-- Session Groups -->
      <VCard
        v-if="selection.kind === 'project' && selectedProject"
        :title="$t('scopes.sessionGroups.cardTitle')"
      >
        <p class="text-sm opacity-70 mb-3">
          {{ $t('scopes.sessionGroups.description') }}
        </p>

        <VEmptyState
          v-if="!sessionGroupsState.loading.value && sessionGroupsState.groups.value.length === 0"
          :headline="$t('scopes.sessionGroups.empty')"
        />

        <ul v-else class="flex flex-col divide-y divide-base-300">
          <li
            v-for="g in sessionGroupsState.groups.value"
            :key="g.name"
            class="py-2 flex flex-col gap-1"
          >
            <template v-if="editingSessionGroup === g.name">
              <VInput
                v-model="editingSessionGroupTitle"
                :label="$t('scopes.sessionGroups.titleLabel')"
              />
              <div class="flex justify-end gap-2 mt-1">
                <VButton variant="ghost" size="sm" @click="cancelSessionGroupRename">
                  {{ $t('scopes.common.cancel') }}
                </VButton>
                <VButton
                  variant="primary"
                  size="sm"
                  :loading="sessionGroupsState.busy.value"
                  @click="saveSessionGroupRename(g.name)"
                >{{ $t('scopes.common.save') }}</VButton>
              </div>
            </template>
            <template v-else>
              <div class="flex items-center justify-between gap-2">
                <div class="min-w-0">
                  <div class="text-sm font-semibold truncate">{{ g.title || g.name }}</div>
                  <div class="text-xs opacity-60 font-mono truncate">{{ g.name }}</div>
                </div>
                <span class="opacity-60 text-xs whitespace-nowrap">
                  {{ $t('scopes.sessionGroups.sessionCount', { count: g.sessionIds.length }) }}
                </span>
              </div>
              <div class="flex justify-end gap-2 mt-1">
                <VButton variant="ghost" size="sm" @click="startEditSessionGroup(g)">
                  {{ $t('scopes.sessionGroups.rename') }}
                </VButton>
                <VButton
                  variant="ghost"
                  size="sm"
                  :loading="sessionGroupsState.busy.value"
                  @click="deleteSessionGroupAction(g.name)"
                >{{ $t('scopes.sessionGroups.delete') }}</VButton>
              </div>
            </template>
          </li>
        </ul>

        <div class="border-t border-base-300 pt-3 mt-2 flex flex-col gap-2">
          <VInput
            v-model="newSessionGroupName"
            :label="$t('scopes.sessionGroups.nameLabel')"
            :help="$t('scopes.sessionGroups.nameHint')"
          />
          <VInput
            v-model="newSessionGroupTitle"
            :label="$t('scopes.sessionGroups.titleLabel')"
          />
          <VButton
            variant="primary"
            size="sm"
            :disabled="!newSessionGroupName.trim()"
            :loading="sessionGroupsState.busy.value"
            @click="createSessionGroupAction"
          >{{ $t('scopes.sessionGroups.add') }}</VButton>
        </div>
      </VCard>

      <!-- Kit -->
      <VCard
        v-if="selection.kind === 'project' && selectedProject"
        :title="$t('scopes.kit.cardTitle')"
      >
        <div v-if="kitState.loading.value" class="opacity-70 text-sm">
          {{ $t('scopes.kit.loading') }}
        </div>
        <div v-else class="flex flex-col gap-3 text-sm">
          <div v-if="kitState.installed.value.length === 0" class="opacity-70">
            {{ $t('scopes.kit.none') }}
          </div>
          <div
            v-for="record in kitState.installed.value"
            :key="record.id"
            class="flex flex-col gap-1 border-b border-base-300 pb-2 last:border-b-0"
          >
            <div class="flex items-baseline justify-between gap-2">
              <span class="font-semibold">{{ record.kit.name }}</span>
              <div class="flex items-center gap-2 shrink-0">
                <span
                  v-if="record.signatureStatus === 'VERIFIED'"
                  class="text-xs opacity-70"
                  :title="$t('scopes.kit.signature.verifiedHelp')"
                >✓ {{ $t('scopes.kit.signature.verified') }}</span>
                <!-- Only FAILED is called out. `unsigned` is the normal state
                     for kits from git and flagging it would train people to
                     ignore the badge. -->
                <span
                  v-else-if="record.signatureStatus === 'FAILED'"
                  class="text-xs text-warning"
                  :title="$t('scopes.kit.signature.failedHelp')"
                >⚠ {{ $t('scopes.kit.signature.failed') }}</span>
                <span v-if="record.kit.version" class="opacity-60 text-xs">
                  {{ $t('scopes.kit.versionPrefix', { version: record.kit.version }) }}
                </span>
              </div>
            </div>
            <!-- An expired licence stops updates; it does not take anything
                 away, so this is a note and not an error. -->
            <VAlert
              v-if="licenceExpiry(record) === 'expired'"
              variant="warning"
              class="text-xs"
            >
              <span>{{ $t('scopes.kit.licenceExpired') }}</span>
            </VAlert>
            <div
              v-else-if="licenceExpiry(record) === 'soon'"
              class="text-xs opacity-70"
            >{{ $t('scopes.kit.licenceExpiringSoon', {
                date: record.descriptor?.licenseExpiresAt }) }}</div>
            <div v-if="record.kit.description" class="opacity-80">{{ record.kit.description }}</div>
            <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-xs opacity-80">
              <dt v-if="record.descriptor?.vendor" class="opacity-60">
                {{ $t('scopes.kit.vendor') }}
              </dt>
              <dd v-if="record.descriptor?.vendor">{{ record.descriptor.vendor }}</dd>
              <dt v-if="record.descriptor?.license" class="opacity-60">
                {{ $t('scopes.kit.license') }}
              </dt>
              <dd v-if="record.descriptor?.license">{{ record.descriptor.license }}</dd>
              <!-- Only shown for purchased kits; a git kit has none of this. -->
              <dt v-if="record.descriptor?.licensedTo" class="opacity-60">
                {{ $t('scopes.kit.licensedTo') }}
              </dt>
              <dd v-if="record.descriptor?.licensedTo">{{ record.descriptor.licensedTo }}</dd>
              <dt v-if="record.descriptor?.licenseExpiresAt" class="opacity-60">
                {{ $t('scopes.kit.licenseExpires') }}
              </dt>
              <dd v-if="record.descriptor?.licenseExpiresAt">
                {{ record.descriptor.licenseExpiresAt }}
              </dd>
              <dt class="opacity-60">{{ $t('scopes.kit.origin') }}</dt>
              <dd class="break-all font-mono">{{ record.origin.url }}</dd>
              <dt v-if="record.origin.path" class="opacity-60">{{ $t('scopes.kit.path') }}</dt>
              <dd v-if="record.origin.path">{{ record.origin.path }}</dd>
              <dt v-if="record.origin.branch" class="opacity-60">{{ $t('scopes.kit.branch') }}</dt>
              <dd v-if="record.origin.branch">{{ record.origin.branch }}</dd>
              <dt v-if="record.origin.commit" class="opacity-60">{{ $t('scopes.kit.commit') }}</dt>
              <dd v-if="record.origin.commit" class="font-mono">
                {{ record.origin.commit.slice(0, 12) }}
              </dd>
              <dt v-if="record.origin.installedAt" class="opacity-60">
                {{ $t('scopes.kit.installed') }}
              </dt>
              <dd v-if="record.origin.installedAt">{{ record.origin.installedAt }}</dd>
              <dt class="opacity-60">{{ $t('scopes.kit.documents') }}</dt>
              <dd>{{ record.artefacts?.documents?.length ?? 0 }}</dd>
              <dt class="opacity-60">{{ $t('scopes.kit.settings') }}</dt>
              <dd>{{ record.artefacts?.settings?.length ?? 0 }}</dd>
              <dt v-if="(record.descriptor?.inherits?.length ?? 0) > 0" class="opacity-60">
                {{ $t('scopes.kit.inherits') }}
              </dt>
              <dd v-if="(record.descriptor?.inherits?.length ?? 0) > 0">
                {{ record.descriptor?.inherits?.length ?? 0 }}
              </dd>
            </dl>
            <div class="flex flex-wrap justify-end gap-2 pt-1">
              <VButton
                variant="ghost"
                size="sm"
                @click="openKitConfigDialog(record)"
              >{{ $t('scopes.kit.configure') }}</VButton>
              <!-- Promoting is only offered while the project is not
                   already some other kit's source — it can only be one. -->
              <VButton
                v-if="!kitState.manifest.value && !record.descriptor?.artifact"
                variant="ghost"
                size="sm"
                :loading="kitState.busy.value"
                @click="promoteKit(record)"
              >{{ $t('scopes.kit.promote') }}</VButton>
              <VButton
                variant="ghost"
                size="sm"
                :loading="kitState.busy.value"
                @click="uninstallKit(record)"
              >{{ $t('scopes.kit.uninstall') }}</VButton>
              <VButton
                variant="ghost"
                size="sm"
                :loading="kitState.busy.value"
                @click="updateInstalledKit(record)"
              >{{ $t('scopes.kit.update') }}</VButton>
            </div>
          </div>

          <!-- Being a kit *source* is a separate, opt-in role — only shown
               when someone actually turned it on. -->
          <div
            v-if="kitState.manifest.value"
            class="border-t border-base-300 pt-2 text-xs opacity-80"
          >
            <div class="font-semibold opacity-90">
              {{ $t('scopes.kit.isSource', { name: kitState.manifest.value.kit.name }) }}
            </div>
            <div class="flex flex-wrap justify-end gap-2 pt-2">
              <VButton variant="ghost" size="sm" @click="openKitDialog('export')">
                {{ $t('scopes.kit.export') }}
              </VButton>
            </div>
          </div>

          <div class="flex flex-wrap justify-end gap-2 pt-1">
            <VButton
              v-if="kitState.installed.value.length > 1"
              variant="ghost"
              size="sm"
              :loading="kitState.busy.value"
              @click="updateAllKits"
            >{{ $t('scopes.kit.updateAll') }}</VButton>
            <VButton
              variant="primary"
              size="sm"
              :loading="kitState.busy.value"
              @click="openKitDialog('install')"
            >{{ $t('scopes.kit.install') }}</VButton>
          </div>
        </div>
        <div
          v-if="kitState.lastResult.value"
          class="mt-3 border-t border-base-300 pt-2 text-xs opacity-80"
        >
          <div class="font-semibold opacity-90 mb-1">
            {{ kitState.lastResult.value.version
              ? $t('scopes.kit.lastOperationVersion', {
                  mode: kitState.lastResult.value.mode,
                  version: kitState.lastResult.value.version,
                })
              : $t('scopes.kit.lastOperation', { mode: kitState.lastResult.value.mode }) }}
          </div>
          <ul class="flex flex-col gap-0.5">
            <li v-if="(kitState.lastResult.value.documentsAdded?.length ?? 0) > 0">
              {{ $t('scopes.kit.docsAdded', { count: kitState.lastResult.value.documentsAdded.length }) }}
            </li>
            <li v-if="(kitState.lastResult.value.documentsUpdated?.length ?? 0) > 0">
              {{ $t('scopes.kit.docsUpdated', { count: kitState.lastResult.value.documentsUpdated.length }) }}
            </li>
            <li v-if="(kitState.lastResult.value.documentsRemoved?.length ?? 0) > 0">
              {{ $t('scopes.kit.docsRemoved', { count: kitState.lastResult.value.documentsRemoved.length }) }}
            </li>
            <li v-if="(kitState.lastResult.value.settingsAdded?.length ?? 0) > 0
                  || (kitState.lastResult.value.settingsUpdated?.length ?? 0) > 0">
              {{ $t('scopes.kit.settingsTouched', {
                count: (kitState.lastResult.value.settingsAdded?.length ?? 0)
                  + (kitState.lastResult.value.settingsUpdated?.length ?? 0),
              }) }}
            </li>
            <li v-if="(kitState.lastResult.value.toolsAdded?.length ?? 0) > 0
                  || (kitState.lastResult.value.toolsUpdated?.length ?? 0) > 0">
              {{ $t('scopes.kit.toolsTouched', {
                count: (kitState.lastResult.value.toolsAdded?.length ?? 0)
                  + (kitState.lastResult.value.toolsUpdated?.length ?? 0),
              }) }}
            </li>
            <li v-if="(kitState.lastResult.value.skippedPasswords?.length ?? 0) > 0" class="opacity-90">
              {{ $t('scopes.kit.passwordsSkipped', {
                count: kitState.lastResult.value.skippedPasswords.length,
              }) }}
            </li>
            <li
              v-for="(w, i) in (kitState.lastResult.value.warnings ?? [])"
              :key="'kw-' + i"
              class="opacity-90"
            >⚠ {{ w }}</li>
          </ul>
        </div>
      </VCard>
      </div>
    </div>

    <!-- ─── Right panel: settings + setting-forms tabs ───
         {@code v-if} sits on the inner content (not the template)
         so Vue's slot-presence detection stays stable. The
         {@code show-right-panel} prop on EditorShell drives the
         column collapse when no scope is selected. -->
    <template #right-panel>
      <div v-if="settingsScope" class="p-4 flex flex-col gap-3">
        <h3 class="font-semibold text-sm uppercase opacity-60">
          {{ $t('scopes.settingsPanel.title', {
            type: settingsScope.type,
            id: settingsScope.id,
          }) }}
        </h3>

        <!-- Tab switcher -->
        <div role="tablist" class="flex gap-1 border-b border-base-300 -mt-1">
          <button
            type="button"
            role="tab"
            class="px-3 py-1.5 text-sm font-semibold border-b-2 transition-colors"
            :class="rightTab === 'settings'
              ? 'border-primary text-primary'
              : 'border-transparent opacity-60 hover:opacity-100'"
            @click="rightTab = 'settings'"
          >
            {{ $t('scopes.settingsPanel.tabRaw') }}
          </button>
          <button
            type="button"
            role="tab"
            class="px-3 py-1.5 text-sm font-semibold border-b-2 transition-colors"
            :class="rightTab === 'forms'
              ? 'border-primary text-primary'
              : 'border-transparent opacity-60 hover:opacity-100'"
            @click="rightTab = 'forms'"
          >
            {{ $t('scopes.settingsPanel.tabForms') }}
            <span
              v-if="settingFormsList.length > 0"
              class="ml-1 text-xs opacity-70"
            >({{ settingFormsList.length }})</span>
          </button>
        </div>

        <!-- ─── Tab: raw settings ─── -->
        <template v-if="rightTab === 'settings'">

        <VEmptyState
          v-if="!settingsState.loading.value && settingsState.settings.value.length === 0"
          :headline="$t('scopes.settingsPanel.noSettingsHeadline')"
          :body="$t('scopes.settingsPanel.noSettingsBody')"
        />

        <ul class="flex flex-col divide-y divide-base-300">
          <li
            v-for="s in settingsState.settings.value"
            :key="s.key"
            class="setting-row"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="font-mono text-sm truncate">{{ s.key }}</span>
              <span class="opacity-60 text-xs">{{ s.type }}</span>
            </div>
            <template v-if="editingKey === s.key">
              <VInput
                v-if="!isEncryptedSettingType(s.type)"
                v-model="editValue"
                :label="$t('scopes.settingsPanel.valueLabel')"
              />
              <VInput
                v-else
                v-model="editValue"
                type="password"
                :label="$t('scopes.settingsPanel.newPasswordLabel')"
                :placeholder="$t('scopes.settingsPanel.passwordEmptyToClear')"
              />
              <VTextarea
                v-model="editDescription"
                :label="$t('scopes.settingsPanel.descriptionLabel')"
                :rows="2"
              />
              <div class="flex justify-end gap-2 mt-1">
                <VButton variant="ghost" size="sm" @click="cancelEditSetting">
                  {{ $t('scopes.common.cancel') }}
                </VButton>
                <VButton
                  variant="primary"
                  size="sm"
                  :loading="settingsState.busy.value"
                  @click="saveEditSetting(s)"
                >{{ $t('scopes.common.save') }}</VButton>
              </div>
            </template>
            <template v-else>
              <div class="text-sm break-words">
                <span v-if="isEncryptedSettingType(s.type)" class="opacity-70">
                  {{ settingValueLabel(s) }}
                </span>
                <span v-else>{{ settingValueLabel(s) }}</span>
              </div>
              <div v-if="s.description" class="text-xs opacity-60">{{ s.description }}</div>
              <div class="flex justify-end gap-2 mt-1">
                <VButton variant="ghost" size="sm" @click="startEditSetting(s)">
                  {{ $t('scopes.settingsPanel.edit') }}
                </VButton>
                <VButton variant="ghost" size="sm" @click="deleteSetting(s)">
                  {{ $t('scopes.settingsPanel.deleteLabel') }}
                </VButton>
              </div>
            </template>
          </li>
        </ul>

        <div class="border-t border-base-300 pt-3 mt-2 flex flex-col gap-2">
          <h4 class="text-xs uppercase opacity-60">{{ $t('scopes.settingsPanel.addTitle') }}</h4>
          <VInput
            v-model="newSettingKey"
            :label="$t('scopes.settingsPanel.keyLabel')"
            :placeholder="$t('scopes.settingsPanel.keyPlaceholder')"
          />
          <VSelect
            v-model="newSettingType"
            :label="$t('scopes.settingsPanel.typeLabel')"
            :options="settingTypeOptions"
          />
          <VInput
            v-if="!isEncryptedSettingType(newSettingType)"
            v-model="newSettingValue"
            :label="$t('scopes.settingsPanel.valueLabel')"
          />
          <VInput
            v-else
            v-model="newSettingValue"
            type="password"
            :label="$t('scopes.settingsPanel.passwordLabel')"
          />
          <VTextarea
            v-model="newSettingDescription"
            :label="$t('scopes.settingsPanel.descriptionOptional')"
            :rows="2"
          />
          <VButton
            variant="primary"
            size="sm"
            :disabled="!newSettingKey.trim()"
            :loading="settingsState.busy.value"
            @click="addSetting"
          >{{ $t('scopes.settingsPanel.add') }}</VButton>
        </div>

        </template>
        <!-- ─── /Tab: raw settings ─── -->

        <!-- ─── Tab: setting forms ─── -->
        <template v-else-if="rightTab === 'forms'">
          <VAlert v-if="settingFormsError" variant="error">
            {{ settingFormsError }}
          </VAlert>

          <VEmptyState
            v-if="!settingFormsLoading
                  && settingFormsList.length === 0
                  && !selectedSettingForm"
            :headline="$t('scopes.settingFormsPanel.emptyHeadline')"
            :body="settingFormsProjectId
              ? $t('scopes.settingFormsPanel.emptyBodyProject')
              : $t('scopes.settingFormsPanel.emptyBodyTenant')"
          />

          <!-- Form-Listing -->
          <template v-if="!selectedSettingForm">
            <div
              v-for="[cat, group] in groupedSettingForms"
              :key="cat"
              class="flex flex-col gap-1"
            >
              <div
                v-if="cat"
                class="text-[10px] uppercase tracking-wide opacity-50 font-semibold px-1 mt-1"
              >
                {{ cat }}
              </div>
              <button
                v-for="f in group"
                :key="f.name"
                type="button"
                class="text-left px-2.5 py-2 text-sm rounded transition-colors bg-base-200 hover:bg-base-300"
                @click="selectSettingForm(f.name)"
              >
                <div class="flex items-center gap-1.5">
                  <span class="font-semibold truncate">{{ f.title }}</span>
                </div>
                <div class="text-xs opacity-70 mt-0.5 line-clamp-2">
                  {{ f.description }}
                </div>
              </button>
            </div>
          </template>

          <!-- Selected form -->
          <template v-else>
            <div class="flex items-center gap-2 -mt-1">
              <VButton variant="ghost" size="sm" @click="backToSettingFormsList">
                {{ $t('scopes.settingFormsPanel.backToList') }}
              </VButton>
            </div>
            <SettingFormView
              :name="selectedSettingForm"
              :project-id="settingFormsProjectId"
              :reload-key="settingFormsReloadKey"
              @applied="onSettingFormApplied"
            />
          </template>
        </template>
        <!-- ─── /Tab: setting forms ─── -->
      </div>
    </template>

    <!-- Create-Group / Create-Project modals now live inside the
         shared {@link ProjectListSidebar} component — see template. -->

    <!-- ─── Kit modal (install / update / apply / export) ─── -->
    <VModal
      v-model="showCopyDialog"
      :title="$t('scopes.project.copy.title', {
        name: selection.kind === 'project' ? selection.name : '' })"
      :close-on-backdrop="false"
    >
      <div class="flex flex-col gap-3">
        <VAlert v-if="projectsState.error.value" variant="error">
          <span>{{ projectsState.error.value }}</span>
        </VAlert>

        <!-- Form until the copy ran; report afterwards. Both in one dialog so
             what was left behind is read in the same place it was decided. -->
        <template v-if="!copyReport">
          <p class="text-sm opacity-80">{{ $t('scopes.project.copy.description') }}</p>
          <VInput
            v-model="copyForm.name"
            :label="$t('scopes.project.copy.nameLabel')"
            :help="$t('scopes.project.copy.nameHelp')"
          />
          <VInput v-model="copyForm.title" :label="$t('scopes.common.title')" />
          <VSelect
            v-model="copyForm.projectGroupId"
            :label="$t('scopes.project.groupLabel')"
            :options="groupSelectOptions"
          />
          <VCheckbox
            v-model="copyForm.includeSecrets"
            :label="$t('scopes.project.copy.includeSecrets')"
          />
          <p class="text-xs opacity-70">{{ $t('scopes.project.copy.includeSecretsHelp') }}</p>
          <VAlert variant="info">
            <span>{{ $t('scopes.project.copy.notCopiedHint') }}</span>
          </VAlert>
          <div class="flex justify-end gap-2 pt-2">
            <VButton variant="ghost" @click="showCopyDialog = false">
              {{ $t('scopes.common.cancel') }}
            </VButton>
            <VButton
              variant="primary"
              :disabled="copyForm.name.trim() === ''"
              :loading="projectsState.busy.value"
              @click="submitCopy"
            >{{ $t('scopes.project.copy.submit') }}</VButton>
          </div>
        </template>

        <template v-else>
          <VAlert :variant="copyReport.documentsFailed > 0 ? 'warning' : 'success'">
            <span>{{ $t('scopes.project.copy.done', {
              name: copyReport.project?.name ?? copyForm.name }) }}</span>
          </VAlert>
          <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
            <dt class="opacity-60">{{ $t('scopes.project.copy.documentsCopied') }}</dt>
            <dd>{{ copyReport.documentsCopied }}</dd>
            <dt class="opacity-60">{{ $t('scopes.project.copy.documentsExcluded') }}</dt>
            <dd>{{ copyReport.documentsExcluded }}</dd>
            <template v-if="copyReport.documentsFailed > 0">
              <dt class="opacity-60">{{ $t('scopes.project.copy.documentsFailed') }}</dt>
              <dd class="text-error">{{ copyReport.documentsFailed }}</dd>
            </template>
            <dt class="opacity-60">{{ $t('scopes.project.copy.settingsCopied') }}</dt>
            <dd>{{ copyReport.settingsCopied }}</dd>
            <dt class="opacity-60">{{ $t('scopes.project.copy.secretsCopied') }}</dt>
            <dd>{{ copyReport.secretsCopied }}</dd>
          </dl>

          <VAlert v-if="copyReport.statusNote" variant="info">
            <span>{{ copyReport.statusNote }}</span>
          </VAlert>

          <div v-if="copyReport.secretsSkipped.length > 0" class="flex flex-col gap-1">
            <span class="text-sm font-semibold">
              {{ $t('scopes.project.copy.secretsSkipped') }}
            </span>
            <p class="text-xs opacity-70">{{ $t('scopes.project.copy.secretsSkippedHelp') }}</p>
            <ul class="list-disc pl-5 text-xs font-mono">
              <li v-for="key in copyReport.secretsSkipped" :key="key">{{ key }}</li>
            </ul>
          </div>

          <div v-if="copyReport.failures.length > 0" class="flex flex-col gap-1">
            <span class="text-sm font-semibold text-error">
              {{ $t('scopes.project.copy.failures') }}
            </span>
            <ul class="list-disc pl-5 text-xs">
              <li v-for="(line, index) in copyReport.failures" :key="index">{{ line }}</li>
            </ul>
          </div>

          <div class="flex flex-col gap-1">
            <span class="text-sm font-semibold">{{ $t('scopes.project.copy.notCopied') }}</span>
            <ul class="list-disc pl-5 text-xs opacity-70">
              <li v-for="(line, index) in copyReport.notCopied" :key="index">{{ line }}</li>
            </ul>
          </div>

          <div class="flex justify-end gap-2 pt-2">
            <VButton variant="ghost" @click="showCopyDialog = false">
              {{ $t('scopes.common.close') }}
            </VButton>
            <VButton variant="primary" @click="openCopiedProject">
              {{ $t('scopes.project.copy.open') }}
            </VButton>
          </div>
        </template>
      </div>
    </VModal>

    <VModal
      v-model="showKitConfigDialog"
      :title="$t('scopes.kit.config.title', { name: kitConfigRecord?.kit.name ?? '' })"
      :close-on-backdrop="false"
    >
      <div class="flex flex-col gap-3">
        <VAlert v-if="kitState.error.value" variant="error">
          <span>{{ kitState.error.value }}</span>
        </VAlert>
        <VSelect
          v-model="kitConfigForm.defaultAction"
          :label="$t('scopes.kit.config.defaultAction')"
          :help="$t('scopes.kit.config.defaultActionHelp')"
          :options="kitPolicyActionOptions"
        />
        <VInput
          v-model="kitConfigForm.sortIndex"
          type="number"
          :label="$t('scopes.kit.config.sortIndex')"
          :help="$t('scopes.kit.config.sortIndexHelp')"
        />

        <div class="flex flex-col gap-2">
          <div class="flex items-baseline justify-between">
            <span class="text-sm font-semibold">{{ $t('scopes.kit.config.rules') }}</span>
            <VButton variant="ghost" size="sm" @click="addKitPolicyRule">
              {{ $t('scopes.kit.config.addRule') }}
            </VButton>
          </div>
          <p class="text-xs opacity-70">{{ $t('scopes.kit.config.rulesHelp') }}</p>
          <div v-if="kitConfigForm.rules.length === 0" class="text-xs opacity-60">
            {{ $t('scopes.kit.config.noRules') }}
          </div>
          <div
            v-for="(rule, index) in kitConfigForm.rules"
            :key="index"
            class="flex flex-wrap items-end gap-2"
          >
            <!-- Width goes on a wrapper: VSelect/VInput carry `w-full`
                 themselves, and a merged `w-40` loses at equal specificity. -->
            <div class="w-40">
              <VSelect
                v-model="rule.namespace"
                :label="$t('scopes.kit.config.namespace')"
                :options="kitPolicyNamespaceOptions"
              />
            </div>
            <div class="flex-1 min-w-48">
              <VInput
                v-model="rule.pattern"
                :label="$t('scopes.kit.config.pattern')"
                :placeholder="rule.namespace === 'setting' ? 'ai.alias.*' : 'recipes/*.yaml'"
              />
            </div>
            <div class="w-40">
              <VSelect
                v-model="rule.action"
                :label="$t('scopes.kit.config.action')"
                :options="kitPolicyActionOptions"
              />
            </div>
            <VButton variant="ghost" size="sm" @click="removeKitPolicyRule(index)">
              {{ $t('scopes.common.delete') }}
            </VButton>
          </div>
        </div>

        <div class="flex justify-end gap-2 pt-2">
          <VButton variant="ghost" @click="showKitConfigDialog = false">
            {{ $t('scopes.common.cancel') }}
          </VButton>
          <VButton
            variant="primary"
            :loading="kitState.busy.value"
            @click="submitKitConfig"
          >{{ $t('scopes.common.save') }}</VButton>
        </div>
      </div>
    </VModal>

    <VModal v-model="showKitDialog" :title="kitDialogTitle" :close-on-backdrop="false">
      <div class="flex flex-col gap-3">
        <VAlert v-if="kitState.error.value" variant="error">
          <span>{{ kitState.error.value }}</span>
        </VAlert>
        <!-- Library picker: only rendered when a library answered. With
             none configured there is nothing to show and nothing to
             explain. -->
        <div
          v-if="kitDialogMode === 'install' && (libraryLoading || libraryEntries.length > 0)"
          class="flex flex-col gap-2 border border-base-300 rounded p-3"
        >
          <div class="text-sm font-semibold">{{ $t('scopes.kit.library.title') }}</div>
          <div v-if="libraryLoading" class="text-xs opacity-70">
            {{ $t('scopes.kit.library.loading') }}
          </div>
          <div
            v-for="entry in libraryEntries"
            :key="entry.sourceId + '/' + entry.kitId"
            class="flex items-center gap-2 text-sm"
          >
            <div class="flex-1 min-w-0">
              <div class="flex items-baseline gap-2">
                <span class="font-medium truncate">{{ entry.displayName }}</span>
                <span v-if="entry.version" class="text-xs opacity-60">
                  {{ $t('scopes.kit.versionPrefix', { version: entry.version }) }}
                </span>
                <span v-if="entry.installed" class="text-xs opacity-60">
                  {{ $t('scopes.kit.library.alreadyInstalled') }}
                </span>
              </div>
              <div v-if="entry.vendor || entry.license" class="text-xs opacity-60 truncate">
                {{ [entry.vendor, entry.license].filter(Boolean).join(' · ') }}
              </div>
            </div>
            <!-- Owned but not deliverable stays visible and disabled: hiding
                 it would look like the entitlement vanished. -->
            <VButton
              variant="ghost"
              size="sm"
              :disabled="!entry.downloadable"
              :title="entry.downloadable
                ? undefined : $t('scopes.kit.library.notDeliverable')"
              @click="pickFromLibrary(entry)"
            >{{ $t('scopes.kit.library.choose') }}</VButton>
          </div>
        </div>

        <VInput
          v-model="kitForm.url"
          :label="$t('scopes.kit.dialog.repoUrl')"
          :required="kitNeedsUrl"
          :help="kitDialogMode === 'update' || kitDialogMode === 'export'
            ? $t('scopes.kit.dialog.repoUrlReuseHelp')
            : $t('scopes.kit.dialog.repoUrlHelp')"
        />
        <div class="grid grid-cols-2 gap-3">
          <VInput
            v-model="kitForm.path"
            :label="$t('scopes.kit.dialog.subPath')"
            :help="$t('scopes.kit.dialog.subPathHelp')"
          />
          <VInput
            v-model="kitForm.branch"
            :label="$t('scopes.kit.dialog.branchLabel')"
            :help="$t('scopes.kit.dialog.branchHelp')"
          />
        </div>
        <VInput
          v-if="kitDialogMode !== 'export'"
          v-model="kitForm.commit"
          :label="$t('scopes.kit.dialog.commitSha')"
          :help="$t('scopes.kit.dialog.commitShaHelp')"
        />
        <VInput
          v-model="kitForm.token"
          type="password"
          :label="$t('scopes.kit.dialog.authToken')"
          :help="$t('scopes.kit.dialog.authTokenHelp')"
        />
        <VInput
          v-model="kitForm.vaultPassword"
          type="password"
          :label="$t('scopes.kit.dialog.vaultPassword')"
          :help="kitDialogMode === 'export'
            ? $t('scopes.kit.dialog.vaultPasswordExportHelp')
            : $t('scopes.kit.dialog.vaultPasswordImportHelp')"
        />
        <VInput
          v-if="kitDialogMode === 'export'"
          v-model="kitForm.commitMessage"
          :label="$t('scopes.kit.dialog.commitMessage')"
          :help="$t('scopes.kit.dialog.commitMessageHelp')"
        />
        <VCheckbox
          v-if="kitDialogMode !== 'export'"
          v-model="kitForm.trackInstall"
          :label="$t('scopes.kit.dialog.trackInstall')"
          :help="$t('scopes.kit.dialog.trackInstallHelp')"
        />
        <VCheckbox
          v-if="kitDialogMode !== 'export' && kitForm.trackInstall"
          v-model="kitForm.writeManifest"
          :label="$t('scopes.kit.dialog.writeManifest')"
          :help="$t('scopes.kit.dialog.writeManifestHelp')"
        />
        <VCheckbox
          v-if="kitDialogMode === 'update' && kitForm.trackInstall"
          v-model="kitForm.prune"
          :label="$t('scopes.kit.dialog.prune')"
          :help="$t('scopes.kit.dialog.pruneHelp')"
        />
        <VCheckbox
          v-if="kitDialogMode !== 'export' && !kitForm.trackInstall"
          v-model="kitForm.keepPasswords"
          :label="$t('scopes.kit.dialog.keepPasswords')"
          :help="$t('scopes.kit.dialog.keepPasswordsHelp')"
        />
        <div class="flex justify-end gap-2 pt-2">
          <VButton variant="ghost" @click="showKitDialog = false">
            {{ $t('scopes.common.cancel') }}
          </VButton>
          <VButton
            variant="primary"
            :disabled="kitNeedsUrl && !kitForm.url.trim()"
            :loading="kitState.busy.value"
            @click="submitKitDialog"
          >{{ kitDialogSubmitLabel }}</VButton>
        </div>
      </div>
    </VModal>

  </EditorShell>
</template>

<style scoped>
.sidebar-item {
  display: block;
  text-align: left;
  padding: 0.4rem 0.6rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  background: transparent;
  cursor: pointer;
  width: 100%;
}
.sidebar-item:hover {
  background: color-mix(in oklab, var(--color-base-content) 8%, transparent);
}
.sidebar-item--active {
  background: color-mix(in oklab, var(--color-primary) 15%, transparent);
  color: var(--color-primary);
  font-weight: 600;
}
.sidebar-item--child {
  padding-left: 1.5rem;
  font-size: 0.8125rem;
}
.setting-row {
  padding: 0.6rem 0.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
</style>
