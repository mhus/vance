<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
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
import {
  SettingType,
  type ProjectDto,
  type ProjectGroupSummary,
  type SettingDto,
} from '@vance/generated';

const ARCHIVED_GROUP = 'archived';

type Selection =
  | { kind: 'tenant' }
  | { kind: 'group'; name: string }
  | { kind: 'project'; name: string };

const tenantState = useAdminTenant();
const groupsState = useAdminProjectGroups();
const projectsState = useAdminProjects();
const settingsState = useScopeSettings();

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

// ─── Setting editor state ───
const newSettingKey = ref('');
const newSettingType = ref<SettingType>(SettingType.STRING);
const newSettingValue = ref('');
const newSettingDescription = ref('');
const editingKey = ref<string | null>(null);
const editValue = ref('');
const editDescription = ref('');

const settingTypeOptions = [
  { value: SettingType.STRING, label: 'String' },
  { value: SettingType.INT, label: 'Int' },
  { value: SettingType.LONG, label: 'Long' },
  { value: SettingType.DOUBLE, label: 'Double' },
  { value: SettingType.BOOLEAN, label: 'Boolean' },
  { value: SettingType.PASSWORD, label: 'Password' },
];

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
  { value: '', label: '(no group)' },
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
});

watch(selection, () => {
  applySelectionToForm();
  loadSettingsForSelection();
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
    banner.value = 'Tenant saved.';
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
    banner.value = 'Group saved.';
  } catch {
    /* state.error */
  }
}

async function deleteGroup(): Promise<void> {
  if (selection.value.kind !== 'group') return;
  if (!confirm(`Delete group "${selection.value.name}"? This is only possible if the group is empty.`)) return;
  const name = selection.value.name;
  try {
    await groupsState.remove(name);
    selectTenant();
    banner.value = `Group "${name}" deleted.`;
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
    banner.value = 'Project saved.';
  } catch {
    /* state.error */
  }
}

async function archiveProject(): Promise<void> {
  if (selection.value.kind !== 'project') return;
  if (!confirm(`Archive project "${selection.value.name}"? It will be moved to the "archived" group.`)) return;
  try {
    await projectsState.archive(selection.value.name);
    banner.value = `Project archived.`;
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
    banner.value = `Group "${name}" created.`;
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
    banner.value = `Project "${name}" created.`;
  } catch {
    /* state.error */
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
  if (!confirm(`Delete setting "${s.key}"?`)) return;
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
    return [tenantLabel, `Group: ${groupTitle(sel.name)}`];
  }
  return [tenantLabel, `Project: ${selectedProject.value?.title || sel.name}`];
});

const combinedError = computed<string | null>(() =>
  tenantState.error.value
  || groupsState.error.value
  || projectsState.error.value
  || settingsState.error.value);
</script>

<template>
  <EditorShell title="Scopes" :breadcrumbs="breadcrumbs" wide-right-panel>
    <!-- ─── Sidebar tree ─── -->
    <template #sidebar>
      <nav class="flex flex-col gap-1 p-2">
        <button
          class="sidebar-item"
          :class="{ 'sidebar-item--active': isSelected({ kind: 'tenant' }) }"
          type="button"
          @click="selectTenant"
        >
          <span class="opacity-50 mr-1">⌂</span>Tenant
          <span v-if="tenantState.tenant.value" class="opacity-60">
            · {{ tenantState.tenant.value.name }}
          </span>
        </button>

        <div class="mt-3 flex items-center justify-between px-2">
          <span class="text-xs uppercase opacity-50">Project Groups</span>
          <VButton variant="ghost" size="sm" @click="openCreateGroup">+ Group</VButton>
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
            <span v-if="!group.enabled" class="opacity-60 text-xs">(disabled)</span>
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
            <span v-if="p.status === 'ARCHIVED'" class="opacity-60 text-xs">(archived)</span>
          </button>
        </template>

        <div v-if="ungroupedProjects.length > 0" class="mt-3 px-2 text-xs uppercase opacity-50">
          Ungrouped Projects
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
          <VButton variant="ghost" size="sm" block @click="openCreateProject">+ Project</VButton>
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
      <VCard v-if="selection.kind === 'tenant'" title="Tenant">
        <div v-if="!tenantState.tenant.value" class="opacity-70">Loading…</div>
        <div v-else class="flex flex-col gap-3">
          <VInput
            :model-value="tenantState.tenant.value.name"
            label="Name"
            disabled
            help="Tenant name is immutable."
            @update:model-value="() => {}"
          />
          <VInput v-model="form.title" label="Title" />
          <VCheckbox v-model="form.enabled" label="Enabled" />
          <div class="flex justify-end">
            <VButton variant="primary" :loading="tenantState.saving.value" @click="saveTenant">
              Save
            </VButton>
          </div>
        </div>
      </VCard>

      <!-- Group -->
      <VCard v-else-if="selection.kind === 'group'" :title="`Group: ${selection.name}`">
        <VAlert v-if="isReservedGroup" variant="info" class="mb-3">
          <span>This group is reserved for archived projects and cannot be deleted.</span>
        </VAlert>
        <div v-if="!selectedGroup" class="opacity-70">Loading…</div>
        <div v-else class="flex flex-col gap-3">
          <VInput
            :model-value="selectedGroup.name"
            label="Name"
            disabled
            help="Group name is immutable."
            @update:model-value="() => {}"
          />
          <VInput v-model="form.title" label="Title" />
          <VCheckbox v-model="form.enabled" label="Enabled" />
          <div class="flex justify-between">
            <VButton
              variant="danger"
              :disabled="isReservedGroup"
              :loading="groupsState.busy.value"
              @click="deleteGroup"
            >Delete</VButton>
            <VButton variant="primary" :loading="groupsState.busy.value" @click="saveGroup">
              Save
            </VButton>
          </div>
        </div>
      </VCard>

      <!-- Project -->
      <VCard v-else-if="selection.kind === 'project'" :title="`Project: ${selection.name}`">
        <VAlert v-if="isArchivedProject" variant="warning" class="mb-3">
          <span>This project is archived. It is read-only for runtime claims but its metadata can still be edited.</span>
        </VAlert>
        <div v-if="!selectedProject" class="opacity-70">Loading…</div>
        <div v-else class="flex flex-col gap-3">
          <VInput
            :model-value="selectedProject.name"
            label="Name"
            disabled
            help="Project name is immutable."
            @update:model-value="() => {}"
          />
          <VInput v-model="form.title" label="Title" />
          <VSelect
            v-model="form.projectGroupId"
            label="Group"
            :options="groupSelectOptions"
          />
          <VCheckbox v-model="form.enabled" label="Enabled" />
          <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80">
            <dt class="opacity-60">Status</dt><dd>{{ selectedProject.status }}</dd>
            <dt class="opacity-60">Pod</dt><dd>{{ selectedProject.podIp ?? '—' }}</dd>
            <dt class="opacity-60">Claimed</dt><dd>{{ selectedProject.claimedAt ?? '—' }}</dd>
            <dt class="opacity-60">Created</dt><dd>{{ selectedProject.createdAt ?? '—' }}</dd>
          </dl>
          <div class="flex justify-between">
            <VButton
              variant="danger"
              :disabled="isArchivedProject"
              :loading="projectsState.busy.value"
              @click="archiveProject"
            >Archive</VButton>
            <VButton variant="primary" :loading="projectsState.busy.value" @click="saveProject">
              Save
            </VButton>
          </div>
        </div>
      </VCard>
    </div>

    <!-- ─── Right panel: settings ─── -->
    <template v-if="settingsScope" #right-panel>
      <div class="p-4 flex flex-col gap-3">
        <h3 class="font-semibold text-sm uppercase opacity-60">
          Settings · {{ settingsScope.type }} / {{ settingsScope.id }}
        </h3>

        <VEmptyState
          v-if="!settingsState.loading.value && settingsState.settings.value.length === 0"
          headline="No settings"
          body="Add a key/value below to configure this scope."
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
                label="Value"
              />
              <VInput
                v-else
                v-model="editValue"
                type="password"
                label="New password"
                placeholder="(leave empty to clear)"
              />
              <VTextarea v-model="editDescription" label="Description" :rows="2" />
              <div class="flex justify-end gap-2 mt-1">
                <VButton variant="ghost" size="sm" @click="cancelEditSetting">Cancel</VButton>
                <VButton
                  variant="primary"
                  size="sm"
                  :loading="settingsState.busy.value"
                  @click="saveEditSetting(s)"
                >Save</VButton>
              </div>
            </template>
            <template v-else>
              <div class="text-sm break-words">
                <span v-if="s.type === SettingType.PASSWORD" class="opacity-70">{{ s.value ?? '(empty)' }}</span>
                <span v-else>{{ s.value ?? '(empty)' }}</span>
              </div>
              <div v-if="s.description" class="text-xs opacity-60">{{ s.description }}</div>
              <div class="flex justify-end gap-2 mt-1">
                <VButton variant="ghost" size="sm" @click="startEditSetting(s)">Edit</VButton>
                <VButton variant="ghost" size="sm" @click="deleteSetting(s)">Delete</VButton>
              </div>
            </template>
          </li>
        </ul>

        <div class="border-t border-base-300 pt-3 mt-2 flex flex-col gap-2">
          <h4 class="text-xs uppercase opacity-60">Add setting</h4>
          <VInput v-model="newSettingKey" label="Key" placeholder="e.g. ai.default.model" />
          <VSelect v-model="newSettingType" label="Type" :options="settingTypeOptions" />
          <VInput
            v-if="newSettingType !== SettingType.PASSWORD"
            v-model="newSettingValue"
            label="Value"
          />
          <VInput
            v-else
            v-model="newSettingValue"
            type="password"
            label="Password"
          />
          <VTextarea v-model="newSettingDescription" label="Description (optional)" :rows="2" />
          <VButton
            variant="primary"
            size="sm"
            :disabled="!newSettingKey.trim()"
            :loading="settingsState.busy.value"
            @click="addSetting"
          >Add</VButton>
        </div>
      </div>
    </template>

    <!-- ─── Create-Group modal ─── -->
    <VModal v-model="showCreateGroup" title="New project group">
      <div class="flex flex-col gap-3">
        <VInput
          v-model="newGroupName"
          label="Name"
          required
          help="Lower-case alphanumerics, '-' or '_' allowed."
        />
        <VInput v-model="newGroupTitle" label="Title" />
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showCreateGroup = false">Cancel</VButton>
          <VButton
            variant="primary"
            :disabled="!newGroupName.trim()"
            :loading="groupsState.busy.value"
            @click="submitCreateGroup"
          >Create</VButton>
        </div>
      </div>
    </VModal>

    <!-- ─── Create-Project modal ─── -->
    <VModal v-model="showCreateProject" title="New project">
      <div class="flex flex-col gap-3">
        <VInput
          v-model="newProjectName"
          label="Name"
          required
          help="Lower-case alphanumerics, '-' or '_' allowed."
        />
        <VInput v-model="newProjectTitle" label="Title" />
        <VSelect
          v-model="newProjectGroupId"
          label="Group"
          :options="groupSelectOptions"
        />
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showCreateProject = false">Cancel</VButton>
          <VButton
            variant="primary"
            :disabled="!newProjectName.trim()"
            :loading="projectsState.busy.value"
            @click="submitCreateProject"
          >Create</VButton>
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
