<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  CodeEditor,
  EditorShell,
  type FocusZone,
  ProjectListSidebar,
  VAlert,
  VButton,
  VCard,
  VCheckbox,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
  VTextarea,
  VIconButton,
  useConfirm,
} from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useAdminServerTools } from '@/composables/useAdminServerTools';
import { rememberProject } from '@/platform/lastProject';
import type {
  ServerToolDto,
  ServerToolWriteRequest,
  ToolTypeDto,
} from '@vance/generated';

// Tenant-default project — system-wide tool overrides land here.
// Matches {@code HomeBootstrapService.TENANT_PROJECT_NAME} on the
// server. Tool resolution cascade: project → _tenant → bundled
// classpath defaults.
const TENANT_PROJECT = '_tenant';
const NAME_PATTERN = /^[a-z0-9][a-z0-9_]*$/;

const { t } = useI18n();
const confirmAction = useConfirm();
const tenantProjects = useTenantProjects();
const toolsState = useAdminServerTools();

// ─── Project selection ──────────────────────────────────────────────────

const selectedProject = ref<string | null>(TENANT_PROJECT);
const selectedName = ref<string | null>(null);
const focusZone = ref<FocusZone>('main');

const projectTitle = computed<string>(() => {
  const id = selectedProject.value;
  if (!id) return '';
  if (id === TENANT_PROJECT) return t('tools.tenantSystemLabel');
  const p = tenantProjects.projects.value.find((x) => x.name === id);
  return p?.title || id;
});

const inDetailMode = computed<boolean>(() => !!selectedName.value);

async function onProjectListDataChanged(
  payload: { kind: 'group' | 'project'; name: string },
): Promise<void> {
  await tenantProjects.reload();
  if (payload.kind === 'project') {
    selectedProject.value = payload.name;
  }
}

// ─── Form state ─────────────────────────────────────────────────────────

const form = reactive({
  type: '',
  description: '',
  parametersJson: '{}',
  labelsText: '',
  enabled: true,
  primary: false,
  defaultDeferred: false,
  disabledSubToolsText: '',
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
  if (!selectedProject.value) return [t('tools.pageTitle')];
  const projectLabel = projectTitle.value;
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
    selectedProject.value
      ? toolsState.loadProject(selectedProject.value)
      : Promise.resolve(),
  ]);
});

watch(selectedProject, async (pid) => {
  // Contribute to the tab's project memory, but do not open on it: this page
  // starts on {@code _tenant} and the sidebar has no row for it, so a recalled
  // project would put the tenant-wide tool defaults out of reach entirely.
  // The sentinel is not remembered either — it is the defaults layer, not a
  // project anyone works in, and storing it would evict the real one.
  if (pid && pid !== TENANT_PROJECT) rememberProject(pid);
  selectedName.value = null;
  resetForm();
  if (pid) await toolsState.loadProject(pid);
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
  form.defaultDeferred = false;
  form.disabledSubToolsText = '';
}

function populateForm(t: ServerToolDto): void {
  form.type = t.type;
  form.description = t.description;
  form.parametersJson = formatJson(t.parameters ?? {});
  form.labelsText = (t.labels ?? []).join('\n');
  form.enabled = t.enabled;
  form.primary = t.primary;
  form.defaultDeferred = t.defaultDeferred;
  form.disabledSubToolsText = (t.disabledSubTools ?? []).join('\n');
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
  const disabledSubTools = splitLines(form.disabledSubToolsText);

  return {
    type,
    description,
    parameters,
    labels,
    enabled: form.enabled,
    primary: form.primary,
    disabledSubTools,
    defaultDeferred: form.defaultDeferred,
    promptHint: '',
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
  if (!selectedName.value || !selectedProject.value) return;
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
  if (!selectedName.value || !selectedProject.value) return;
  if (!await confirmAction({
    message: t('tools.confirmDelete', { name: selectedName.value }),
    confirmLabel: t('common.delete'),
  })) return;
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
  if (!selectedProject.value) return;
  const project = selectedProject.value;
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
    disabledSubTools: [],
    defaultDeferred: false,
    promptHint: '',
  };
  try {
    await toolsState.upsert(project, name, stub);
    showNewModal.value = false;
    selectedName.value = name;
    banner.value = t('tools.banners.created', { name });
  } catch (e) {
    newError.value = e instanceof Error ? e.message : t('tools.errors.createFailed');
  }
}

// ─── Tool selection ─────────────────────────────────────────────────────

function selectTool(name: string): void {
  selectedName.value = name;
}

function backToList(): void {
  selectedName.value = null;
  banner.value = null;
  formError.value = null;
}

/** CSS-class for the per-row source tag — one color per cascade
 *  tier so the user can spot at-a-glance where a tool came from.
 *  PROJECT = success-green, TENANT = info-blue, BUNDLED = neutral. */
function sourceBadgeClass(source: string | undefined): string {
  switch (source) {
    case 'PROJECT': return 'badge-source--project';
    case 'TENANT':  return 'badge-source--tenant';
    case 'BUNDLED': return 'badge-source--bundled';
    default: return '';
  }
}
</script>

<template>
  <EditorShell
    v-model:focus-zone="focusZone"
    :title="$t('tools.pageTitle')"
    :breadcrumbs="breadcrumbs"
    :full-height="true"
    :show-sidebar="true"
    :show-right-panel="inDetailMode"
    :show-footer="inDetailMode"
    focus-model="auto"
    title-clickable
    @title-click="focusZone = 'sidebar'"
  >
    <!-- ─── Sidebar: shared project picker ─── -->
    <template #sidebar>
      <ProjectListSidebar
        v-model:selected-project="selectedProject"
        :groups="tenantProjects.groups.value"
        :projects="tenantProjects.projects.value"
        :loading="tenantProjects.loading.value"
        :error="tenantProjects.error.value"
        :heading="$t('tools.sidebar.projectsHeading')"
        edit-enabled
        @focus-main="focusZone = 'main'"
        @data-changed="onProjectListDataChanged"
      />
    </template>

    <!-- ─── Main: master (tool list) → detail (tool form) ─── -->
    <div class="h-full min-h-0 flex flex-col">
      <!-- Sub-header A: list mode. Project label on the left, Add
           Tool button on the right. Visible when a project is
           selected but no tool is opened. -->
      <div
        v-if="selectedProject && !inDetailMode"
        class="px-6 pt-4 pb-3 border-b border-(--v-separator) bg-(--v-surface) flex items-center gap-3"
      >
        <div class="flex-1 min-w-0 flex items-baseline gap-2 truncate">
          <span class="font-semibold truncate">{{ projectTitle }}</span>
          <span class="font-mono text-sm text-(--v-label-tertiary) truncate">{{ selectedProject }}</span>
        </div>
        <VIconButton icon="add" :label="$t('tools.sidebar.addNew')" size="sm"
          @click="openNewTool"
         />
      </div>

      <!-- Sub-header B: detail mode. Back-button + tool name +
           type badge. Mirrors the documents detail sub-header. -->
      <div
        v-else-if="selectedProject && selectedTool"
        class="px-6 pt-4 pb-3 border-b border-(--v-separator) bg-(--v-surface) flex items-center gap-x-3 gap-y-1 flex-wrap"
      >
        <div class="flex items-center gap-3 min-w-0 flex-1 basis-[16rem]">
          <VIconButton icon="back" :label="$t('tools.detail.backToList')" size="sm"
            @click="backToList"
           />
          <span class="font-semibold font-mono truncate">{{ selectedTool.name }}</span>
        </div>
        <div class="text-xs text-(--v-label-secondary) flex items-center gap-3 shrink-0">
          <span
            v-if="selectedTool.source"
            :class="['badge-source', sourceBadgeClass(selectedTool.source)]"
            :title="$t('tools.sourceTooltip.' + selectedTool.source.toLowerCase())"
          >{{ $t('tools.source.' + selectedTool.source.toLowerCase()) }}</span>
          <span class="badge-type px-1.5 py-0.5 rounded text-xs">{{ selectedTool.type }}</span>
          <span
            v-if="selectedTool.updatedAtTimestamp"
          >{{ $t('tools.detail.lastEdit', {
            at: new Date(selectedTool.updatedAtTimestamp).toLocaleString(),
          }) }}</span>
        </div>
      </div>

      <!-- Scrollable content area. Banner / error alerts sit at the
           top of the scroll body; list rows or form cards follow. -->
      <div class="flex-1 min-h-0 overflow-y-auto">
        <div class="container mx-auto px-4 py-4 max-w-4xl flex flex-col gap-3">
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
            v-if="!selectedProject"
            :headline="$t('tools.pickAProjectHeadline')"
            :body="$t('tools.pickAProjectBody')"
          />

          <!-- ── List view ── -->
          <template v-else-if="!inDetailMode">
            <div v-if="toolsState.loading.value" class="text-xs text-(--v-label-secondary) px-2">
              {{ $t('tools.loading') }}
            </div>
            <VEmptyState
              v-else-if="toolsState.tools.value.length === 0"
              :headline="$t('tools.sidebar.noToolsHeadline')"
              :body="$t('tools.sidebar.noToolsBody')"
            />
            <ul v-else class="flex flex-col gap-1">
              <li
                v-for="tool in toolsState.tools.value"
                :key="tool.name"
                class="tool-item"
                @click="selectTool(tool.name)"
              >
                <div class="flex items-center justify-between gap-2">
                  <span class="flex items-center gap-2 min-w-0">
                    <span class="font-mono text-sm truncate">{{ tool.name }}</span>
                    <span
                      v-if="tool.source"
                      :class="['badge-source shrink-0', sourceBadgeClass(tool.source)]"
                      :title="$t('tools.sourceTooltip.' + tool.source.toLowerCase())"
                    >{{ $t('tools.source.' + tool.source.toLowerCase()) }}</span>
                  </span>
                  <span class="text-xs px-1.5 py-0.5 rounded badge-type shrink-0">{{ tool.type }}</span>
                </div>
                <div class="flex items-center gap-2 text-xs text-(--v-label-secondary) mt-0.5">
                  <span v-if="!tool.enabled" class="badge-disabled">{{ $t('tools.sidebar.disabled') }}</span>
                  <span v-if="tool.primary" class="tag-primary">{{ $t('tools.sidebar.primary') }}</span>
                  <span v-if="tool.defaultDeferred" class="badge-deferred">{{ $t('tools.sidebar.deferred') }}</span>
                  <span class="truncate">{{ tool.description }}</span>
                </div>
              </li>
            </ul>
          </template>

          <!-- ── Detail view (existing form cards) ── -->
          <template v-else-if="selectedTool">
            <VAlert v-if="selectedProject === TENANT_PROJECT" variant="info">
              <span>{{ $t('tools.detail.tenantNote') }}</span>
            </VAlert>
            <VAlert
              v-else-if="selectedTool.source === 'TENANT'"
              variant="warning"
            >
              <span>{{ $t('tools.detail.cascadedFromTenant') }}</span>
            </VAlert>
            <VAlert
              v-else-if="selectedTool.source === 'BUNDLED'"
              variant="warning"
            >
              <span>{{ $t('tools.detail.cascadedFromBundled') }}</span>
            </VAlert>

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

            <VCard :title="$t('tools.cards.packTitle')">
              <div class="flex flex-col gap-3">
                <p class="text-xs text-(--v-label-secondary)">{{ $t('tools.cards.packHelp') }}</p>
                <VCheckbox
                  v-model="form.defaultDeferred"
                  :label="$t('tools.fields.defaultDeferred')"
                  :help="$t('tools.fields.defaultDeferredHelp')"
                />
                <VTextarea
                  v-model="form.disabledSubToolsText"
                  :label="$t('tools.fields.disabledSubTools')"
                  :help="$t('tools.fields.disabledSubToolsHelp')"
                  :rows="4"
                />
              </div>
            </VCard>

            <VCard :title="$t('tools.cards.parametersTitle')">
              <p class="text-xs text-(--v-label-secondary) mb-2">{{ $t('tools.cards.parametersHelp') }}</p>
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
      </div>
    </div>

    <!-- ─── Right panel: type schema. {@code v-if} on the inner
         content + {@code show-right-panel} prop above guards
         against Vue's slot-presence reactivity bug. ─── -->
    <template #right-panel>
      <div v-if="inDetailMode" class="p-4 flex flex-col gap-4">
        <section>
          <h3 class="text-xs uppercase text-(--v-label-secondary) mb-2">{{ $t('tools.rightPanel.typeSchemaTitle') }}</h3>
          <div v-if="!selectedTypeSchema" class="text-xs text-(--v-label-secondary)">
            {{ $t('tools.rightPanel.pickTypeHint') }}
          </div>
          <div v-else>
            <div class="font-mono text-sm mb-1">{{ selectedTypeSchema.typeId }}</div>
            <pre class="text-xs whitespace-pre-wrap break-words bg-(--v-fill) p-2 rounded">{{
              JSON.stringify(selectedTypeSchema.parametersSchema, null, 2)
            }}</pre>
          </div>
        </section>

        <section>
          <h3 class="text-xs uppercase text-(--v-label-secondary) mb-2">{{ $t('tools.rightPanel.cascadeTitle') }}</h3>
          <p class="text-xs text-(--v-label-secondary)">{{ $t('tools.rightPanel.cascadeBody') }}</p>
        </section>
      </div>
    </template>

    <!-- ─── Footer rail: tool actions in detail mode. Delete on the
         left (destructive), Save on the right. Mirrors the document
         editor's footer pattern. ─── -->
    <template #footer>
      <div
        v-if="inDetailMode"
        class="px-6 py-3 flex items-center gap-2 bg-(--v-surface)"
      >
        <VButton
          variant="danger"
          :loading="toolsState.busy.value"
          @click="deleteTool"
        >{{ $t('tools.detail.delete') }}</VButton>
        <span class="flex-1"></span>
        <VButton
          variant="primary"
          :loading="toolsState.busy.value"
          @click="save"
        >{{ $t('tools.detail.save') }}</VButton>
      </div>
    </template>

    <!-- ─── New-tool modal ─── -->
    <VModal
      v-model="showNewModal"
      size="sm"
      :title="$t('tools.newModal.title')"
      :description="$t('tools.newModal.stubInfo', { project: selectedProject })"
    >
      <form id="tool-new-form" class="flex flex-col gap-4" @submit.prevent="submitNewTool">
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
      </form>
      <template #actions>
        <VButton variant="secondary" @click="showNewModal = false">
          {{ $t('tools.newModal.cancel') }}
        </VButton>
        <VButton
          type="submit"
          form="tool-new-form"
          variant="primary"
          :loading="toolsState.busy.value"
        >{{ $t('tools.newModal.create') }}</VButton>
      </template>
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
.tool-item:hover { background: var(--v-fill); }
.tool-item--active {
  background: var(--v-selection);
  color: var(--v-accent-text);
}
.badge-type {
  background: var(--v-fill);
  color: var(--v-label-secondary);
}
.tag-primary { color: var(--v-accent-text); }
.badge-disabled { color: var(--v-label-tertiary); }
.badge-deferred { color: var(--v-warning); }
.badge-source {
  font-size: 0.65rem;
  padding: 0.05rem 0.5rem;
  border-radius: 9999px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  font-weight: 600;
  line-height: 1.2;
  border: 1px solid var(--v-separator);
  white-space: nowrap;
}
.badge-source--project { color: var(--v-success); }
.badge-source--tenant { color: var(--v-accent-text); }
.badge-source--bundled { color: var(--v-label-secondary); }
</style>
