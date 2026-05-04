<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  EditorShell,
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
import { useAdminTenant } from '@/composables/useAdminTenant';
import { useAdminProjectGroups } from '@/composables/useAdminProjectGroups';
import { useAdminProjects } from '@/composables/useAdminProjects';
import { useScopeSettings } from '@/composables/useScopeSettings';
import { useKitAdmin } from '@/composables/useKitAdmin';
import {
  KitImportMode,
  SettingType,
  type KitImportRequestDto,
  type KitExportRequestDto,
  type ProjectDto,
  type ProjectGroupSummary,
  type SettingDto,
} from '@vance/generated';

type KitDialogMode = 'install' | 'update' | 'apply' | 'export';

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

const selection = ref<Selection>({ kind: 'tenant' });
const banner = ref<string | null>(null);

// ─── Detail-form state ───
// One blob keyed off the selection — re-populated on every selection change.
const form = reactive({
  title: '',
  enabled: true,
  projectGroupId: null as string | null,
});

// ─── Modals ───
const showCreateGroup = ref(false);
const newGroupName = ref('');
const newGroupTitle = ref('');

const showCreateProject = ref(false);
const newProjectName = ref('');
const newProjectTitle = ref('');
const newProjectGroupId = ref<string | null>(null);

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
  commitMessage: '',
});

// ─── Setting editor state ───
const newSettingKey = ref('');
const newSettingType = ref<SettingType>(SettingType.STRING);
const newSettingValue = ref('');
const newSettingDescription = ref('');
const editingKey = ref<string | null>(null);
const editValue = ref('');
const editDescription = ref('');

const settingTypeOptions = computed(() => [
  { value: SettingType.STRING, label: t('scopes.settingsPanel.types.string') },
  { value: SettingType.INT, label: t('scopes.settingsPanel.types.int') },
  { value: SettingType.LONG, label: t('scopes.settingsPanel.types.long') },
  { value: SettingType.DOUBLE, label: t('scopes.settingsPanel.types.double') },
  { value: SettingType.BOOLEAN, label: t('scopes.settingsPanel.types.boolean') },
  { value: SettingType.PASSWORD, label: t('scopes.settingsPanel.types.password') },
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

const projectsByGroup = computed<Map<string, ProjectDto[]>>(() => {
  const map = new Map<string, ProjectDto[]>();
  for (const p of projectsState.projects.value) {
    const key = p.projectGroupId ?? '';
    if (!map.has(key)) map.set(key, []);
    map.get(key)!.push(p);
  }
  return map;
});

const ungroupedProjects = computed<ProjectDto[]>(() =>
  projectsByGroup.value.get('') ?? []);

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
  ]);
  // Selection defaults to tenant — populate the form once tenant is loaded.
  applySelectionToForm();
  loadSettingsForSelection();
  loadKitForSelection();
});

watch(selection, () => {
  applySelectionToForm();
  loadSettingsForSelection();
  loadKitForSelection();
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
  if (!scope) {
    settingsState.clear();
    return;
  }
  void settingsState.load(scope.type, scope.id);
}

function loadKitForSelection(): void {
  if (selection.value.kind !== 'project') {
    kitState.clear();
    return;
  }
  void kitState.load(selection.value.name);
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

function selectTenant(): void {
  selection.value = { kind: 'tenant' };
}

function selectGroup(name: string): void {
  selection.value = { kind: 'group', name };
}

function selectProject(name: string): void {
  selection.value = { kind: 'project', name };
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

// ─── Create modals ───

function openCreateGroup(): void {
  newGroupName.value = '';
  newGroupTitle.value = '';
  showCreateGroup.value = true;
}

async function submitCreateGroup(): Promise<void> {
  const name = newGroupName.value.trim();
  if (!name) return;
  try {
    await groupsState.create({
      name,
      title: newGroupTitle.value.trim() || undefined,
    });
    showCreateGroup.value = false;
    selectGroup(name);
    banner.value = t('scopes.group.created', { name });
  } catch {
    /* state.error */
  }
}

function openCreateProject(): void {
  newProjectName.value = '';
  newProjectTitle.value = '';
  newProjectGroupId.value = selection.value.kind === 'group' ? selection.value.name : null;
  showCreateProject.value = true;
}

async function submitCreateProject(): Promise<void> {
  const name = newProjectName.value.trim();
  if (!name) return;
  try {
    await projectsState.create({
      name,
      title: newProjectTitle.value.trim() || undefined,
      projectGroupId: newProjectGroupId.value || undefined,
      teamIds: [],
    });
    showCreateProject.value = false;
    selectProject(name);
    banner.value = t('scopes.project.created', { name });
  } catch {
    /* state.error */
  }
}

// ─── Kit actions ───

const kitDialogTitle = computed(() => {
  switch (kitDialogMode.value) {
    case 'install': return t('scopes.kit.dialog.installTitle');
    case 'update': return t('scopes.kit.dialog.updateTitle');
    case 'apply': return t('scopes.kit.dialog.applyTitle');
    case 'export': return t('scopes.kit.dialog.exportTitle');
  }
});

const kitDialogSubmitLabel = computed(() => {
  switch (kitDialogMode.value) {
    case 'install': return t('scopes.kit.dialog.submitInstall');
    case 'update': return t('scopes.kit.dialog.submitUpdate');
    case 'apply': return t('scopes.kit.dialog.submitApply');
    case 'export': return t('scopes.kit.dialog.submitExport');
  }
});

const kitNeedsUrl = computed(() =>
  kitDialogMode.value === 'install' || kitDialogMode.value === 'apply');

function openKitDialog(mode: KitDialogMode): void {
  kitDialogMode.value = mode;
  kitForm.url = '';
  kitForm.path = '';
  kitForm.branch = '';
  kitForm.commit = '';
  kitForm.token = '';
  kitForm.vaultPassword = '';
  kitForm.prune = false;
  kitForm.keepPasswords = false;
  kitForm.commitMessage = '';

  // Pre-fill from manifest origin when available (update / export).
  const m = kitState.manifest.value;
  if (m && (mode === 'update' || mode === 'export')) {
    kitForm.url = m.origin?.url ?? '';
    kitForm.path = m.origin?.path ?? '';
    kitForm.branch = m.origin?.branch ?? '';
  }
  showKitDialog.value = true;
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
      };
      if (kitDialogMode.value === 'install') {
        await kitState.install(projectId, request);
        banner.value = t('scopes.kit.installed_msg');
      } else if (kitDialogMode.value === 'update') {
        await kitState.update(projectId, request);
        banner.value = t('scopes.kit.updated_msg');
      } else {
        await kitState.apply(projectId, request);
        banner.value = t('scopes.kit.applied_msg');
      }
    }
    showKitDialog.value = false;
  } catch {
    /* error already in kitState.error */
  }
}

// ─── Settings actions ───

async function addSetting(): Promise<void> {
  const scope = settingsScope.value;
  const key = newSettingKey.value.trim();
  if (!scope || !key) return;
  try {
    await settingsState.upsert(
      scope.type, scope.id, key,
      newSettingValue.value === '' ? null : newSettingValue.value,
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
  editValue.value = s.type === SettingType.PASSWORD ? '' : (s.value ?? '');
  editDescription.value = s.description ?? '';
}

async function saveEditSetting(s: SettingDto): Promise<void> {
  const scope = settingsScope.value;
  if (!scope) return;
  try {
    await settingsState.upsert(
      scope.type, scope.id, s.key,
      editValue.value === '' && s.type === SettingType.PASSWORD ? null : editValue.value,
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
  || kitState.error.value);
</script>

<template>
  <EditorShell :title="$t('scopes.pageTitle')" :breadcrumbs="breadcrumbs" wide-right-panel>
    <!-- ─── Sidebar tree ─── -->
    <template #sidebar>
      <nav class="flex flex-col gap-1 p-2">
        <button
          class="sidebar-item"
          :class="{ 'sidebar-item--active': isSelected({ kind: 'tenant' }) }"
          type="button"
          @click="selectTenant"
        >
          <span class="opacity-50 mr-1">⌂</span>{{ $t('scopes.sidebar.tenant') }}
          <span v-if="tenantState.tenant.value" class="opacity-60">
            · {{ tenantState.tenant.value.name }}
          </span>
        </button>

        <div class="mt-3 flex items-center justify-between px-2">
          <span class="text-xs uppercase opacity-50">{{ $t('scopes.sidebar.projectGroups') }}</span>
          <VButton variant="ghost" size="sm" @click="openCreateGroup">
            {{ $t('scopes.sidebar.addGroup') }}
          </VButton>
        </div>

        <template v-for="group in groupsState.groups.value" :key="'g-' + group.name">
          <button
            class="sidebar-item"
            :class="{ 'sidebar-item--active': isSelected({ kind: 'group', name: group.name }) }"
            type="button"
            @click="selectGroup(group.name)"
          >
            <span class="opacity-50 mr-1">▸</span>
            {{ group.title || group.name }}
            <span v-if="!group.enabled" class="opacity-60 text-xs">
              {{ $t('scopes.common.disabled') }}
            </span>
          </button>
          <button
            v-for="p in projectsByGroup.get(group.name) ?? []"
            :key="'p-' + p.name"
            class="sidebar-item sidebar-item--child"
            :class="{ 'sidebar-item--active': isSelected({ kind: 'project', name: p.name }) }"
            type="button"
            @click="selectProject(p.name)"
          >
            {{ p.title || p.name }}
            <span v-if="p.status === 'ARCHIVED'" class="opacity-60 text-xs">
              {{ $t('scopes.common.archived') }}
            </span>
          </button>
        </template>

        <div v-if="ungroupedProjects.length > 0" class="mt-3 px-2 text-xs uppercase opacity-50">
          {{ $t('scopes.sidebar.ungroupedProjects') }}
        </div>
        <button
          v-for="p in ungroupedProjects"
          :key="'pu-' + p.name"
          class="sidebar-item sidebar-item--child"
          :class="{ 'sidebar-item--active': isSelected({ kind: 'project', name: p.name }) }"
          type="button"
          @click="selectProject(p.name)"
        >
          {{ p.title || p.name }}
        </button>

        <div class="mt-3 px-2">
          <VButton variant="ghost" size="sm" block @click="openCreateProject">
            {{ $t('scopes.sidebar.addProject') }}
          </VButton>
        </div>
      </nav>
    </template>

    <!-- ─── Main detail pane ─── -->
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
            <dd>{{ selectedProject.podIp ?? $t('scopes.common.none') }}</dd>
            <dt class="opacity-60">{{ $t('scopes.project.claimedLabel') }}</dt>
            <dd>{{ selectedProject.claimedAt ?? $t('scopes.common.none') }}</dd>
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
            <VButton variant="primary" :loading="projectsState.busy.value" @click="saveProject">
              {{ $t('scopes.common.save') }}
            </VButton>
          </div>
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
        <div v-else-if="kitState.manifest.value" class="flex flex-col gap-2 text-sm">
          <div class="flex items-baseline justify-between">
            <span class="font-semibold">{{ kitState.manifest.value.kit.name }}</span>
            <span v-if="kitState.manifest.value.kit.version" class="opacity-60 text-xs">
              {{ $t('scopes.kit.versionPrefix', { version: kitState.manifest.value.kit.version }) }}
            </span>
          </div>
          <div v-if="kitState.manifest.value.kit.description" class="opacity-80">
            {{ kitState.manifest.value.kit.description }}
          </div>
          <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-xs opacity-80">
            <dt class="opacity-60">{{ $t('scopes.kit.origin') }}</dt>
            <dd class="break-all font-mono">{{ kitState.manifest.value.origin.url }}</dd>
            <dt v-if="kitState.manifest.value.origin.path" class="opacity-60">
              {{ $t('scopes.kit.path') }}
            </dt>
            <dd v-if="kitState.manifest.value.origin.path">{{ kitState.manifest.value.origin.path }}</dd>
            <dt v-if="kitState.manifest.value.origin.branch" class="opacity-60">
              {{ $t('scopes.kit.branch') }}
            </dt>
            <dd v-if="kitState.manifest.value.origin.branch">{{ kitState.manifest.value.origin.branch }}</dd>
            <dt v-if="kitState.manifest.value.origin.commit" class="opacity-60">
              {{ $t('scopes.kit.commit') }}
            </dt>
            <dd v-if="kitState.manifest.value.origin.commit" class="font-mono">
              {{ kitState.manifest.value.origin.commit.slice(0, 12) }}
            </dd>
            <dt v-if="kitState.manifest.value.origin.installedAt" class="opacity-60">
              {{ $t('scopes.kit.installed') }}
            </dt>
            <dd v-if="kitState.manifest.value.origin.installedAt">
              {{ kitState.manifest.value.origin.installedAt }}
            </dd>
            <dt class="opacity-60">{{ $t('scopes.kit.documents') }}</dt>
            <dd>{{ kitState.manifest.value.documents?.length ?? 0 }}</dd>
            <dt class="opacity-60">{{ $t('scopes.kit.settings') }}</dt>
            <dd>{{ kitState.manifest.value.settings?.length ?? 0 }}</dd>
            <dt class="opacity-60">{{ $t('scopes.kit.tools') }}</dt>
            <dd>{{ kitState.manifest.value.tools?.length ?? 0 }}</dd>
            <dt v-if="(kitState.manifest.value.resolvedInherits?.length ?? 0) > 0" class="opacity-60">
              {{ $t('scopes.kit.inherits') }}
            </dt>
            <dd v-if="(kitState.manifest.value.resolvedInherits?.length ?? 0) > 0">
              {{ kitState.manifest.value.resolvedInherits.join(', ') }}
            </dd>
          </dl>
          <div class="flex flex-wrap justify-end gap-2 pt-2">
            <VButton variant="ghost" size="sm" @click="openKitDialog('apply')">
              {{ $t('scopes.kit.apply') }}
            </VButton>
            <VButton variant="ghost" size="sm" @click="openKitDialog('export')">
              {{ $t('scopes.kit.export') }}
            </VButton>
            <VButton
              variant="primary"
              size="sm"
              :loading="kitState.busy.value"
              @click="openKitDialog('update')"
            >{{ $t('scopes.kit.update') }}</VButton>
          </div>
        </div>
        <div v-else class="flex flex-col gap-2 text-sm">
          <div class="opacity-70">{{ $t('scopes.kit.none') }}</div>
          <div class="flex flex-wrap justify-end gap-2 pt-2">
            <VButton variant="ghost" size="sm" @click="openKitDialog('apply')">
              {{ $t('scopes.kit.apply') }}
            </VButton>
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
            {{ $t('scopes.kit.lastOperation', { mode: kitState.lastResult.value.mode }) }}
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

    <!-- ─── Right panel: settings ─── -->
    <template v-if="settingsScope" #right-panel>
      <div class="p-4 flex flex-col gap-3">
        <h3 class="font-semibold text-sm uppercase opacity-60">
          {{ $t('scopes.settingsPanel.title', {
            type: settingsScope.type,
            id: settingsScope.id,
          }) }}
        </h3>

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
                v-if="s.type !== SettingType.PASSWORD"
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
                <span v-if="s.type === SettingType.PASSWORD" class="opacity-70">
                  {{ s.value ?? $t('scopes.common.empty') }}
                </span>
                <span v-else>{{ s.value ?? $t('scopes.common.empty') }}</span>
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
            v-if="newSettingType !== SettingType.PASSWORD"
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
      </div>
    </template>

    <!-- ─── Create-Group modal ─── -->
    <VModal v-model="showCreateGroup" :title="$t('scopes.createGroup.title')">
      <div class="flex flex-col gap-3">
        <VInput
          v-model="newGroupName"
          :label="$t('scopes.common.name')"
          required
          :help="$t('scopes.createGroup.nameHelp')"
        />
        <VInput v-model="newGroupTitle" :label="$t('scopes.common.title')" />
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showCreateGroup = false">
            {{ $t('scopes.common.cancel') }}
          </VButton>
          <VButton
            variant="primary"
            :disabled="!newGroupName.trim()"
            :loading="groupsState.busy.value"
            @click="submitCreateGroup"
          >{{ $t('scopes.common.create') }}</VButton>
        </div>
      </div>
    </VModal>

    <!-- ─── Kit modal (install / update / apply / export) ─── -->
    <VModal v-model="showKitDialog" :title="kitDialogTitle" :close-on-backdrop="false">
      <div class="flex flex-col gap-3">
        <VAlert v-if="kitState.error.value" variant="error">
          <span>{{ kitState.error.value }}</span>
        </VAlert>
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
          v-if="kitDialogMode === 'update'"
          v-model="kitForm.prune"
          :label="$t('scopes.kit.dialog.prune')"
          :help="$t('scopes.kit.dialog.pruneHelp')"
        />
        <VCheckbox
          v-if="kitDialogMode === 'apply'"
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

    <!-- ─── Create-Project modal ─── -->
    <VModal v-model="showCreateProject" :title="$t('scopes.createProject.title')">
      <div class="flex flex-col gap-3">
        <VInput
          v-model="newProjectName"
          :label="$t('scopes.common.name')"
          required
          :help="$t('scopes.createProject.nameHelp')"
        />
        <VInput v-model="newProjectTitle" :label="$t('scopes.common.title')" />
        <VSelect
          v-model="newProjectGroupId"
          :label="$t('scopes.project.groupLabel')"
          :options="groupSelectOptions"
        />
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showCreateProject = false">
            {{ $t('scopes.common.cancel') }}
          </VButton>
          <VButton
            variant="primary"
            :disabled="!newProjectName.trim()"
            :loading="projectsState.busy.value"
            @click="submitCreateProject"
          >{{ $t('scopes.common.create') }}</VButton>
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
  background: hsl(var(--bc) / 0.08);
}
.sidebar-item--active {
  background: hsl(var(--p) / 0.15);
  color: hsl(var(--pf));
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
