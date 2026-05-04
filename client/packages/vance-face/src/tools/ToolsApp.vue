<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  CodeEditor,
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
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useAdminServerTools } from '@/composables/useAdminServerTools';
import type {
  ServerToolDto,
  ServerToolWriteRequest,
  ToolTypeDto,
} from '@vance/generated';

const VANCE_PROJECT = '_vance';
const NAME_PATTERN = /^[a-z0-9][a-z0-9_]*$/;

const { t } = useI18n();
const tenantProjects = useTenantProjects();
const toolsState = useAdminServerTools();

// ─── Project selection ──────────────────────────────────────────────────

const selectedProject = ref<string>(VANCE_PROJECT);
const selectedName = ref<string | null>(null);

const projectOptions = computed(() => {
  const list: Array<{ value: string; label: string; group?: string }> = [
    { value: VANCE_PROJECT, label: t('tools.vanceProjectLabel') },
  ];
  for (const p of tenantProjects.projects.value) {
    if (p.name === VANCE_PROJECT) continue;
    list.push({
      value: p.name,
      label: (p.title ? p.title + ' ' : '') + '(' + p.name + ')',
      group: t('tools.projectsGroup'),
    });
  }
  return list;
});

const isVanceProject = computed(() => selectedProject.value === VANCE_PROJECT);

// ─── Form state ─────────────────────────────────────────────────────────

const form = reactive({
  type: '',
  description: '',
  parametersJson: '{}',
  labelsText: '',
  enabled: true,
  primary: false,
});

const banner = ref<string | null>(null);
const formError = ref<string | null>(null);

// ─── New-tool modal ─────────────────────────────────────────────────────

const showNewModal = ref(false);
const newName = ref('');
const newType = ref('');
const newError = ref<string | null>(null);

// ─── Derived ────────────────────────────────────────────────────────────

const selectedTool = computed<ServerToolDto | null>(() =>
  selectedName.value
    ? toolsState.tools.value.find(t => t.name === selectedName.value) ?? null
    : null);

const typeOptions = computed(() => toolsState.types.value.map(t => ({
  value: t.typeId,
  label: t.typeId,
})));

const selectedTypeSchema = computed<ToolTypeDto | null>(() => {
  const id = form.type || selectedTool.value?.type || '';
  if (!id) return null;
  return toolsState.types.value.find(t => t.typeId === id) ?? null;
});

const breadcrumbs = computed<string[]>(() => {
  const projectLabel = isVanceProject.value
    ? t('tools.vanceSystemLabel')
    : t('tools.breadcrumbProjectPrefix', { name: selectedProject.value });
  return selectedName.value
    ? [projectLabel, selectedName.value]
    : [projectLabel];
});

const combinedError = computed<string | null>(() =>
  toolsState.error.value || tenantProjects.error.value);

// ─── Lifecycle ──────────────────────────────────────────────────────────

onMounted(async () => {
  await Promise.all([
    tenantProjects.reload(),
    toolsState.loadTypes(),
    toolsState.loadProject(selectedProject.value),
  ]);
});

watch(selectedProject, async (pid) => {
  selectedName.value = null;
  resetForm();
  await toolsState.loadProject(pid);
});

watch(selectedName, () => {
  banner.value = null;
  formError.value = null;
  if (!selectedTool.value) {
    resetForm();
    return;
  }
  populateForm(selectedTool.value);
});

// ─── Form helpers ───────────────────────────────────────────────────────

function resetForm(): void {
  form.type = '';
  form.description = '';
  form.parametersJson = '{}';
  form.labelsText = '';
  form.enabled = true;
  form.primary = false;
}

function populateForm(t: ServerToolDto): void {
  form.type = t.type;
  form.description = t.description;
  form.parametersJson = formatJson(t.parameters ?? {});
  form.labelsText = (t.labels ?? []).join('\n');
  form.enabled = t.enabled;
  form.primary = t.primary;
}

function formatJson(obj: unknown): string {
  try {
    return JSON.stringify(obj, null, 2);
  } catch {
    return '{}';
  }
}

function buildWriteRequest(): ServerToolWriteRequest | null {
  const type = form.type.trim();
  const description = form.description.trim();
  if (!type) { formError.value = t('tools.errors.typeRequired'); return null; }
  if (!description) { formError.value = t('tools.errors.descriptionRequired'); return null; }

  let parameters: Record<string, unknown>;
  try {
    const parsed = JSON.parse(form.parametersJson || '{}');
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      formError.value = t('tools.errors.parametersMustBeObject');
      return null;
    }
    parameters = parsed as Record<string, unknown>;
  } catch (e) {
    formError.value = t('tools.errors.parametersInvalidJson', {
      message: e instanceof Error ? e.message : 'parse error',
    });
    return null;
  }

  const labels = splitLines(form.labelsText);

  return {
    type,
    description,
    parameters,
    labels,
    enabled: form.enabled,
    primary: form.primary,
  };
}

function splitLines(s: string): string[] {
  return s
    .split(/[\n,]/)
    .map(x => x.trim())
    .filter(x => x.length > 0);
}

// ─── Save / Delete ──────────────────────────────────────────────────────

async function save(): Promise<void> {
  if (!selectedName.value) return;
  formError.value = null;
  banner.value = null;
  const req = buildWriteRequest();
  if (!req) return;
  try {
    await toolsState.upsert(selectedProject.value, selectedName.value, req);
    banner.value = t('tools.banners.saved');
  } catch {
    /* error in toolsState.error */
  }
}

async function deleteTool(): Promise<void> {
  if (!selectedName.value) return;
  if (!confirm(t('tools.confirmDelete', { name: selectedName.value }))) return;
  try {
    await toolsState.remove(selectedProject.value, selectedName.value);
    selectedName.value = null;
    banner.value = t('tools.banners.deleted');
  } catch {
    /* error */
  }
}

// ─── New-tool modal ─────────────────────────────────────────────────────

function openNewTool(): void {
  newName.value = '';
  newType.value = toolsState.types.value[0]?.typeId ?? '';
  newError.value = null;
  showNewModal.value = true;
}

async function submitNewTool(): Promise<void> {
  newError.value = null;
  const name = newName.value.trim();
  const type = newType.value.trim();
  if (!name) { newError.value = t('tools.errors.nameRequired'); return; }
  if (!NAME_PATTERN.test(name)) {
    newError.value = t('tools.errors.namePattern');
    return;
  }
  if (toolsState.tools.value.some(tool => tool.name === name)) {
    newError.value = t('tools.errors.nameAlreadyExists', { name });
    return;
  }
  if (!type) { newError.value = t('tools.errors.typeRequired'); return; }

  const stub: ServerToolWriteRequest = {
    type,
    description: t('tools.stubDescription', { name }),
    parameters: {},
    labels: [],
    enabled: true,
    primary: false,
  };
  try {
    await toolsState.upsert(selectedProject.value, name, stub);
    showNewModal.value = false;
    selectedName.value = name;
    banner.value = t('tools.banners.created', { name });
  } catch (e) {
    newError.value = e instanceof Error ? e.message : t('tools.errors.createFailed');
  }
}

// ─── Sidebar selection ──────────────────────────────────────────────────

function selectTool(name: string): void {
  selectedName.value = name;
}
</script>

<template>
  <EditorShell :title="$t('tools.pageTitle')" :breadcrumbs="breadcrumbs" wide-right-panel>
    <template #topbar-extra>
      <VSelect
        v-model="selectedProject"
        :options="projectOptions"
      />
    </template>

    <!-- ─── Sidebar ─── -->
    <template #sidebar>
      <nav class="flex flex-col gap-1 p-2">
        <div class="flex items-center justify-between px-2 mb-1">
          <span class="text-xs uppercase opacity-50">
            {{ isVanceProject ? $t('tools.vanceSystemLabel') : selectedProject }}
          </span>
          <VButton variant="ghost" size="sm" @click="openNewTool">
            {{ $t('tools.sidebar.addNew') }}
          </VButton>
        </div>

        <div v-if="toolsState.loading.value" class="px-2 text-xs opacity-60">
          {{ $t('tools.loading') }}
        </div>
        <VEmptyState
          v-else-if="toolsState.tools.value.length === 0"
          :headline="$t('tools.sidebar.noToolsHeadline')"
          :body="$t('tools.sidebar.noToolsBody')"
        />

        <button
          v-for="tool in toolsState.tools.value"
          :key="tool.name"
          class="tool-item"
          :class="{ 'tool-item--active': selectedName === tool.name }"
          type="button"
          @click="selectTool(tool.name)"
        >
          <div class="flex items-center justify-between gap-2">
            <span class="font-mono text-sm truncate">{{ tool.name }}</span>
            <span class="text-xs px-1.5 py-0.5 rounded badge-type">{{ tool.type }}</span>
          </div>
          <div class="flex items-center gap-2 text-xs opacity-60">
            <span v-if="!tool.enabled" class="badge-disabled">{{ $t('tools.sidebar.disabled') }}</span>
            <span v-if="tool.primary" class="badge-primary">{{ $t('tools.sidebar.primary') }}</span>
            <span class="truncate">{{ tool.description }}</span>
          </div>
        </button>
      </nav>
    </template>

    <!-- ─── Main: form ─── -->
    <div class="p-6 flex flex-col gap-3 max-w-4xl">
      <VAlert v-if="combinedError" variant="error">
        <span>{{ combinedError }}</span>
      </VAlert>
      <VAlert v-if="banner" variant="success">
        <span>{{ banner }}</span>
      </VAlert>
      <VAlert v-if="formError" variant="error">
        <span>{{ formError }}</span>
      </VAlert>

      <VEmptyState
        v-if="!selectedTool"
        :headline="$t('tools.empty.headline')"
        :body="$t('tools.empty.body')"
      />

      <template v-else>
        <VCard>
          <div class="flex items-center justify-between gap-3">
            <div>
              <div class="font-mono text-lg">{{ selectedTool.name }}</div>
              <div class="text-sm opacity-70">
                {{ $t('tools.detail.projectLabel') }}
                <strong>{{ selectedProject }}</strong>
                <span v-if="selectedTool.updatedAtTimestamp">
                  {{ $t('tools.detail.lastEdit', {
                    at: new Date(selectedTool.updatedAtTimestamp).toLocaleString(),
                  }) }}
                </span>
              </div>
            </div>
            <div class="flex gap-2">
              <VButton
                variant="danger"
                :loading="toolsState.busy.value"
                @click="deleteTool"
              >{{ $t('tools.detail.delete') }}</VButton>
              <VButton
                variant="primary"
                :loading="toolsState.busy.value"
                @click="save"
              >{{ $t('tools.detail.save') }}</VButton>
            </div>
          </div>
          <VAlert v-if="isVanceProject" variant="info" class="mt-3">
            <span>{{ $t('tools.detail.vanceNote') }}</span>
          </VAlert>
        </VCard>

        <VCard :title="$t('tools.cards.identityTitle')">
          <div class="flex flex-col gap-3">
            <VSelect
              v-model="form.type"
              :options="typeOptions"
              :label="$t('tools.fields.type')"
              required
            />
            <VTextarea
              v-model="form.description"
              :label="$t('tools.fields.description')"
              :help="$t('tools.fields.descriptionHelp')"
              :rows="3"
              required
            />
            <div class="grid grid-cols-2 gap-3">
              <VCheckbox v-model="form.enabled" :label="$t('tools.fields.enabled')" />
              <VCheckbox
                v-model="form.primary"
                :label="$t('tools.fields.primary')"
              />
            </div>
          </div>
        </VCard>

        <VCard :title="$t('tools.cards.parametersTitle')">
          <p class="text-xs opacity-70 mb-2">{{ $t('tools.cards.parametersHelp') }}</p>
          <CodeEditor
            v-model="form.parametersJson"
            mime-type="application/json"
            :rows="14"
          />
        </VCard>

        <VCard :title="$t('tools.cards.labelsTitle')">
          <VTextarea
            v-model="form.labelsText"
            :label="$t('tools.fields.labels')"
            :help="$t('tools.fields.labelsHelp')"
            :rows="4"
          />
        </VCard>
      </template>
    </div>

    <!-- ─── Right panel: type schema ─── -->
    <template #right-panel>
      <div class="p-4 flex flex-col gap-4">
        <section>
          <h3 class="text-xs uppercase opacity-60 mb-2">{{ $t('tools.rightPanel.typeSchemaTitle') }}</h3>
          <div v-if="!selectedTypeSchema" class="text-xs opacity-60">
            {{ $t('tools.rightPanel.pickTypeHint') }}
          </div>
          <div v-else>
            <div class="font-mono text-sm mb-1">{{ selectedTypeSchema.typeId }}</div>
            <pre class="text-xs whitespace-pre-wrap break-words bg-base-200 p-2 rounded">{{
              JSON.stringify(selectedTypeSchema.parametersSchema, null, 2)
            }}</pre>
          </div>
        </section>

        <section>
          <h3 class="text-xs uppercase opacity-60 mb-2">{{ $t('tools.rightPanel.cascadeTitle') }}</h3>
          <p class="text-xs opacity-70">{{ $t('tools.rightPanel.cascadeBody') }}</p>
        </section>
      </div>
    </template>

    <!-- ─── New-tool modal ─── -->
    <VModal v-model="showNewModal" :title="$t('tools.newModal.title')">
      <div class="flex flex-col gap-3">
        <VAlert v-if="newError" variant="error">
          <span>{{ newError }}</span>
        </VAlert>
        <VInput
          v-model="newName"
          :label="$t('tools.newModal.nameLabel')"
          required
          :help="$t('tools.newModal.nameHelp')"
        />
        <VSelect
          v-model="newType"
          :options="typeOptions"
          :label="$t('tools.fields.type')"
          required
        />
        <p class="text-xs opacity-70">
          {{ $t('tools.newModal.stubInfo', { project: selectedProject }) }}
        </p>
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showNewModal = false">
            {{ $t('tools.newModal.cancel') }}
          </VButton>
          <VButton
            variant="primary"
            :loading="toolsState.busy.value"
            @click="submitNewTool"
          >{{ $t('tools.newModal.create') }}</VButton>
        </div>
      </div>
    </VModal>
  </EditorShell>
</template>

<style scoped>
.tool-item {
  display: block;
  text-align: left;
  padding: 0.5rem 0.6rem;
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  width: 100%;
  border: 1px solid transparent;
}
.tool-item:hover { background: hsl(var(--bc) / 0.06); }
.tool-item--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
}
.badge-type {
  background: hsl(var(--in) / 0.18);
  color: hsl(var(--inc));
}
.badge-primary {
  background: hsl(var(--su) / 0.18);
  color: hsl(var(--suc));
  padding: 0 0.4rem;
  border-radius: 0.25rem;
}
.badge-disabled {
  background: hsl(var(--bc) / 0.1);
  color: hsl(var(--bc) / 0.6);
  padding: 0 0.4rem;
  border-radius: 0.25rem;
}
</style>
