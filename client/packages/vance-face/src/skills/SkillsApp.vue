<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import {
  CodeEditor,
  EditorShell,
  MarkdownView,
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
import { useAdminSkills } from '@/composables/useAdminSkills';
import { useHelp } from '@/composables/useHelp';
import { getUsername } from '@vance/shared';
import {
  SkillReferenceDocLoadMode,
  SkillScope,
  SkillScriptTarget,
  SkillTriggerType,
  type SkillDto,
  type SkillReferenceDocDto,
  type SkillScriptDto,
  type SkillTriggerDto,
  type SkillWriteRequest,
} from '@vance/generated';

const tenantProjects = useTenantProjects();
const skillsState = useAdminSkills();
const help = useHelp();

const currentUsername = getUsername() ?? '';

/**
 * Selected scope for the editor. Exactly one of projectId / userId is
 * non-null when scope = project / user. {@code kind} drives the UI;
 * the cascade walks BUNDLED → TENANT → PROJECT? → USER?.
 */
type Scope =
  | { kind: 'tenant' }
  | { kind: 'project'; projectId: string }
  | { kind: 'user' };  // own user only — teammate-view is future work

const scope = ref<Scope>({ kind: 'tenant' });

const selectedName = ref<string | null>(null);
const banner = ref<string | null>(null);
const formError = ref<string | null>(null);

// ─── Form state ──────────────────────────────────────────────────────────
interface TriggerForm {
  type: SkillTriggerType;
  pattern: string;
  keywordsText: string;
}
interface RefDocForm {
  title: string;
  content: string;
  loadMode: SkillReferenceDocLoadMode;
}
interface ScriptForm {
  name: string;
  description: string;
  target: SkillScriptTarget;
  content: string;
}
const form = reactive({
  title: '',
  description: '',
  version: '1.0.0',
  enabled: true,
  triggers: [] as TriggerForm[],
  promptExtension: '',
  toolsText: '',
  tagsText: '',
  refDocs: [] as RefDocForm[],
  scripts: [] as ScriptForm[],
});

// ─── New-skill modal ────────────────────────────────────────────────────
const showNewModal = ref(false);
const newName = ref('');
const newTitle = ref('');
const newDescription = ref('');
const newError = ref<string | null>(null);
const NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;

// ─── Derived state ──────────────────────────────────────────────────────

const projectId = computed<string | null>(() =>
  scope.value.kind === 'project' ? scope.value.projectId : null);

const userId = computed<string | null>(() =>
  scope.value.kind === 'user' ? currentUsername : null);

const currentScopeEnum = computed<SkillScope>(() => {
  if (scope.value.kind === 'project') return SkillScope.PROJECT;
  if (scope.value.kind === 'user') return SkillScope.USER;
  return SkillScope.TENANT;
});

const scopeOptions = computed(() => {
  const list: Array<{ value: string; label: string; group?: string }> = [
    { value: 't:', label: 'Tenant' },
  ];
  for (const p of tenantProjects.projects.value) {
    list.push({
      value: 'p:' + p.name,
      label: 'Project: ' + (p.title || p.name),
      group: 'Projects',
    });
  }
  if (currentUsername) {
    list.push({
      value: 'u:' + currentUsername,
      label: 'User: ' + currentUsername + ' (you)',
      group: 'Users',
    });
  }
  return list;
});

const scopeValue = computed<string>(() => {
  if (scope.value.kind === 'project') return 'p:' + scope.value.projectId;
  if (scope.value.kind === 'user') return 'u:' + currentUsername;
  return 't:';
});

function onScopeChange(v: string | number | null): void {
  const s = v == null ? '' : String(v);
  if (s.startsWith('p:')) {
    scope.value = { kind: 'project', projectId: s.slice(2) };
  } else if (s.startsWith('u:')) {
    scope.value = { kind: 'user' };
  } else {
    scope.value = { kind: 'tenant' };
  }
}

const selectedSkill = computed<SkillDto | null>(() =>
  selectedName.value
    ? skillsState.skills.value.find(s => s.name === selectedName.value) ?? null
    : null);

const isOwnedAtCurrentScope = computed(() =>
  selectedSkill.value?.scope === currentScopeEnum.value);

const isReadOnly = computed(() =>
  selectedSkill.value?.scope === SkillScope.BUNDLED);

const breadcrumbs = computed<string[]>(() => {
  const scopeLabel =
    scope.value.kind === 'project' ? `Project: ${scope.value.projectId}` :
    scope.value.kind === 'user' ? `User: ${currentUsername}` :
    'Tenant';
  return selectedName.value ? [scopeLabel, selectedName.value] : [scopeLabel];
});

const triggerTypeOptions = [
  { value: SkillTriggerType.PATTERN, label: 'PATTERN (regex)' },
  { value: SkillTriggerType.KEYWORDS, label: 'KEYWORDS (≥50% match)' },
];

const loadModeOptions = [
  { value: SkillReferenceDocLoadMode.INLINE, label: 'INLINE' },
  { value: SkillReferenceDocLoadMode.ON_DEMAND, label: 'ON_DEMAND' },
];

const scriptTargetOptions = [
  { value: SkillScriptTarget.BRAIN, label: 'BRAIN (server)' },
  { value: SkillScriptTarget.FOOT, label: 'FOOT (client)' },
];

const SCRIPT_NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;

// ─── Lifecycle ──────────────────────────────────────────────────────────

onMounted(async () => {
  await Promise.all([
    tenantProjects.reload(),
    skillsState.loadEffective(null, null),
    help.load('skill-field-docs.md'),
  ]);
});

watch(scope, async (s) => {
  selectedName.value = null;
  resetForm();
  await skillsState.loadEffective(
    s.kind === 'project' ? s.projectId : null,
    s.kind === 'user' ? currentUsername : null,
  );
}, { deep: true });

watch(selectedName, () => {
  banner.value = null;
  formError.value = null;
  if (!selectedSkill.value) {
    resetForm();
    return;
  }
  populateForm(selectedSkill.value);
});

// ─── Form helpers ───────────────────────────────────────────────────────

function resetForm(): void {
  form.title = '';
  form.description = '';
  form.version = '1.0.0';
  form.enabled = true;
  form.triggers = [];
  form.promptExtension = '';
  form.toolsText = '';
  form.tagsText = '';
  form.refDocs = [];
  form.scripts = [];
}

function populateForm(s: SkillDto): void {
  form.title = s.title;
  form.description = s.description;
  form.version = s.version;
  form.enabled = s.enabled;
  form.triggers = (s.triggers ?? []).map(t => ({
    type: t.type,
    pattern: t.pattern ?? '',
    keywordsText: (t.keywords ?? []).join('\n'),
  }));
  form.promptExtension = s.promptExtension ?? '';
  form.toolsText = (s.tools ?? []).join('\n');
  form.tagsText = (s.tags ?? []).join('\n');
  form.refDocs = (s.referenceDocs ?? []).map(d => ({
    title: d.title,
    content: d.content,
    loadMode: d.loadMode,
  }));
  form.scripts = (s.scripts ?? []).map(x => ({
    name: x.name,
    description: x.description ?? '',
    target: x.target,
    content: x.content,
  }));
}

function buildWriteRequest(): SkillWriteRequest | null {
  const title = form.title.trim();
  const description = form.description.trim();
  const version = form.version.trim();
  if (!title) { formError.value = 'Title is required.'; return null; }
  if (!description) { formError.value = 'Description is required.'; return null; }
  if (!version) { formError.value = 'Version is required.'; return null; }

  const triggers: SkillTriggerDto[] = form.triggers.map(t => ({
    type: t.type,
    pattern: t.type === SkillTriggerType.PATTERN
      ? (t.pattern.trim() || undefined)
      : undefined,
    keywords: t.type === SkillTriggerType.KEYWORDS
      ? splitLines(t.keywordsText)
      : [],
  }));
  for (const t of triggers) {
    if (t.type === SkillTriggerType.PATTERN && !t.pattern) {
      formError.value = 'PATTERN trigger needs a regex.'; return null;
    }
    if (t.type === SkillTriggerType.KEYWORDS && (!t.keywords || t.keywords.length === 0)) {
      formError.value = 'KEYWORDS trigger needs at least one keyword.'; return null;
    }
  }

  const refDocs: SkillReferenceDocDto[] = form.refDocs.map(d => ({
    title: d.title.trim(),
    content: d.content,
    loadMode: d.loadMode,
  }));
  for (const d of refDocs) {
    if (!d.title) { formError.value = 'Reference doc title is required.'; return null; }
    if (!d.content) { formError.value = 'Reference doc content is required.'; return null; }
  }

  const scripts: SkillScriptDto[] = form.scripts.map(s => ({
    name: s.name.trim(),
    description: s.description.trim() || undefined,
    target: s.target,
    content: s.content,
  }));
  const seenScriptNames = new Set<string>();
  for (const s of scripts) {
    if (!s.name) { formError.value = 'Script name is required.'; return null; }
    if (!SCRIPT_NAME_PATTERN.test(s.name)) {
      formError.value = `Script name "${s.name}" must be lower-case alphanumerics with optional "-" or "_".`;
      return null;
    }
    if (seenScriptNames.has(s.name)) {
      formError.value = `Duplicate script name "${s.name}".`;
      return null;
    }
    seenScriptNames.add(s.name);
    if (!s.content) { formError.value = `Script "${s.name}" needs content.`; return null; }
  }

  return {
    title,
    description,
    version,
    triggers,
    promptExtension: form.promptExtension.trim() || undefined,
    tools: splitLines(form.toolsText),
    referenceDocs: refDocs,
    scripts,
    tags: splitLines(form.tagsText),
    enabled: form.enabled,
  };
}

function splitLines(s: string): string[] {
  return s
    .split(/[\n,]/)
    .map(x => x.trim())
    .filter(x => x.length > 0);
}

// ─── Trigger / RefDoc list mutators ─────────────────────────────────────

function addTrigger(): void {
  form.triggers.push({
    type: SkillTriggerType.PATTERN,
    pattern: '',
    keywordsText: '',
  });
}
function removeTrigger(idx: number): void {
  form.triggers.splice(idx, 1);
}

function addRefDoc(): void {
  form.refDocs.push({
    title: '',
    content: '',
    loadMode: SkillReferenceDocLoadMode.INLINE,
  });
}
function removeRefDoc(idx: number): void {
  form.refDocs.splice(idx, 1);
}

function addScript(): void {
  form.scripts.push({
    name: '',
    description: '',
    target: SkillScriptTarget.BRAIN,
    content: '',
  });
}
function removeScript(idx: number): void {
  form.scripts.splice(idx, 1);
}

// ─── Save / Override / Delete ───────────────────────────────────────────

async function save(): Promise<void> {
  if (!selectedName.value) return;
  formError.value = null;
  banner.value = null;
  const req = buildWriteRequest();
  if (!req) return;
  try {
    await skillsState.upsert(projectId.value, userId.value, selectedName.value, req);
    banner.value = isOwnedAtCurrentScope.value
      ? 'Saved.'
      : `Override created at ${scope.value.kind} scope.`;
  } catch {
    /* error in skillsState.error */
  }
}

async function deleteOverride(): Promise<void> {
  if (!selectedName.value || !isOwnedAtCurrentScope.value) return;
  if (!confirm(`Remove the ${scope.value.kind}-scope override of "${selectedName.value}"?`)) return;
  try {
    await skillsState.remove(projectId.value, userId.value, selectedName.value);
    banner.value = 'Override removed — skill falls back to inherited copy.';
  } catch {
    /* error */
  }
}

// ─── New-skill modal ────────────────────────────────────────────────────

function openNewSkill(): void {
  newName.value = '';
  newTitle.value = '';
  newDescription.value = '';
  newError.value = null;
  showNewModal.value = true;
}

async function submitNewSkill(): Promise<void> {
  newError.value = null;
  const name = newName.value.trim();
  const title = newTitle.value.trim();
  const description = newDescription.value.trim();
  if (!name) { newError.value = 'Name is required.'; return; }
  if (!NAME_PATTERN.test(name)) {
    newError.value = 'Name must be lower-case alphanumerics with optional "-" or "_".'; return;
  }
  if (skillsState.skills.value.some(s => s.name === name)) {
    newError.value = `A skill named "${name}" is already visible at this scope. Pick a different name (or open the existing one to override it).`;
    return;
  }
  if (!title) { newError.value = 'Title is required.'; return; }
  if (!description) { newError.value = 'Description is required.'; return; }

  const stub: SkillWriteRequest = {
    title,
    description,
    version: '1.0.0',
    triggers: [],
    tools: [],
    referenceDocs: [],
    scripts: [],
    tags: [],
    enabled: true,
  };
  try {
    await skillsState.upsert(projectId.value, userId.value, name, stub);
    showNewModal.value = false;
    selectedName.value = name;
    banner.value = `Created skill "${name}" at ${scope.value.kind} scope.`;
  } catch (e) {
    newError.value = e instanceof Error ? e.message : 'Failed to create skill.';
  }
}

// ─── Sidebar selection ──────────────────────────────────────────────────

function selectSkill(name: string): void {
  selectedName.value = name;
}

function scopeBadgeClass(s: SkillScope): string {
  if (s === SkillScope.BUNDLED) return 'badge-bundled';
  if (s === SkillScope.TENANT) return 'badge-tenant';
  if (s === SkillScope.PROJECT) return 'badge-project';
  if (s === SkillScope.USER) return 'badge-user';
  return '';
}

function scopeLabel(s: SkillScope): string {
  if (s === SkillScope.BUNDLED) return 'bundled';
  if (s === SkillScope.TENANT) return 'tenant';
  if (s === SkillScope.PROJECT) return 'project';
  if (s === SkillScope.USER) return 'user';
  return '';
}

const combinedError = computed<string | null>(() =>
  skillsState.error.value || tenantProjects.error.value);
</script>

<template>
  <EditorShell title="Skills" :breadcrumbs="breadcrumbs" wide-right-panel>
    <template #topbar-extra>
      <VSelect
        :model-value="scopeValue"
        :options="scopeOptions"
        @update:model-value="onScopeChange"
      />
    </template>

    <!-- ─── Sidebar ─── -->
    <template #sidebar>
      <nav class="flex flex-col gap-1 p-2">
        <div class="flex items-center justify-between px-2 mb-1">
          <span class="text-xs uppercase opacity-50">
            {{ scope.kind === 'project' ? `Project: ${scope.projectId}` :
               scope.kind === 'user' ? `User: ${currentUsername}` :
               'Tenant scope' }}
          </span>
          <VButton variant="ghost" size="sm" @click="openNewSkill">+ New</VButton>
        </div>

        <div v-if="skillsState.loading.value" class="px-2 text-xs opacity-60">
          Loading…
        </div>
        <VEmptyState
          v-else-if="skillsState.skills.value.length === 0"
          headline="No skills"
          body="Click + New to create one."
        />

        <button
          v-for="s in skillsState.skills.value"
          :key="s.name"
          class="skill-item"
          :class="{ 'skill-item--active': selectedName === s.name }"
          type="button"
          @click="selectSkill(s.name)"
        >
          <div class="flex items-center justify-between gap-2">
            <span class="font-mono text-sm truncate">{{ s.name }}</span>
            <span
              class="text-xs px-1.5 py-0.5 rounded"
              :class="scopeBadgeClass(s.scope)"
            >{{ scopeLabel(s.scope) }}</span>
          </div>
          <div class="text-xs opacity-60 truncate">{{ s.title }}</div>
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
        v-if="!selectedSkill"
        headline="Select a skill"
        body="Pick one from the list, or click + New to create one at the current scope."
      />

      <template v-else>
        <VCard>
          <div class="flex items-center justify-between gap-3">
            <div>
              <div class="font-mono text-lg">{{ selectedSkill.name }}</div>
              <div class="text-sm opacity-70">
                Effective at <strong>{{ scope.kind }}</strong>
                · source <strong>{{ scopeLabel(selectedSkill.scope) }}</strong>
              </div>
            </div>
            <div class="flex gap-2">
              <VButton
                v-if="isOwnedAtCurrentScope"
                variant="danger"
                :loading="skillsState.busy.value"
                @click="deleteOverride"
              >Delete override</VButton>
              <VButton
                v-if="!isReadOnly"
                variant="primary"
                :loading="skillsState.busy.value"
                @click="save"
              >{{ isOwnedAtCurrentScope ? 'Save' : 'Override here' }}</VButton>
            </div>
          </div>
          <VAlert v-if="!isOwnedAtCurrentScope && !isReadOnly" variant="info" class="mt-3">
            <span>
              This skill is inherited from <strong>{{ scopeLabel(selectedSkill.scope) }}</strong>.
              Click "Override here" to copy-on-write into the
              <strong>{{ scope.kind }}</strong> scope.
            </span>
          </VAlert>
          <VAlert v-if="isReadOnly" variant="info" class="mt-3">
            <span>Bundled skills are read-only. Switch to a writable scope and use "Override here" to customise.</span>
          </VAlert>
        </VCard>

        <VCard title="Identity">
          <div class="flex flex-col gap-3">
            <VInput v-model="form.title" label="Title" required />
            <VInput v-model="form.description" label="Description" required />
            <div class="grid grid-cols-2 gap-3">
              <VInput v-model="form.version" label="Version" required />
              <VCheckbox v-model="form.enabled" label="Enabled" />
            </div>
          </div>
        </VCard>

        <VCard title="Triggers">
          <div class="flex flex-col gap-3">
            <div
              v-for="(t, idx) in form.triggers"
              :key="'trig-' + idx"
              class="trigger-row"
            >
              <div class="flex items-center justify-between gap-2 mb-2">
                <VSelect
                  v-model="t.type"
                  :options="triggerTypeOptions"
                  label="Type"
                />
                <VButton
                  variant="ghost"
                  size="sm"
                  @click="removeTrigger(idx)"
                >Remove</VButton>
              </div>
              <VInput
                v-if="t.type === SkillTriggerType.PATTERN"
                v-model="t.pattern"
                label="Pattern (regex)"
                placeholder="e.g. schau.*(diff|PR)"
              />
              <VTextarea
                v-else
                v-model="t.keywordsText"
                label="Keywords"
                help="One per line (or comma-separated). Trigger fires when ≥50% of keywords appear."
                :rows="3"
              />
            </div>
            <VButton variant="ghost" size="sm" @click="addTrigger">+ Add trigger</VButton>
          </div>
        </VCard>

        <VCard title="Prompt extension">
          <CodeEditor
            v-model="form.promptExtension"
            mime-type="text/markdown"
            :rows="14"
          />
        </VCard>

        <VCard title="Tools & tags">
          <div class="grid grid-cols-2 gap-4">
            <VTextarea
              v-model="form.toolsText"
              label="Tools"
              help="One per line. Added to the engine/recipe allow-list."
              :rows="4"
            />
            <VTextarea
              v-model="form.tagsText"
              label="Tags"
              help="One per line."
              :rows="4"
            />
          </div>
        </VCard>

        <VCard title="Reference docs">
          <div class="flex flex-col gap-3">
            <div
              v-for="(d, idx) in form.refDocs"
              :key="'doc-' + idx"
              class="refdoc-row"
            >
              <div class="grid grid-cols-3 gap-2 mb-2">
                <VInput v-model="d.title" label="Title" required />
                <VSelect v-model="d.loadMode" :options="loadModeOptions" label="Load mode" />
                <div class="flex items-end justify-end">
                  <VButton variant="ghost" size="sm" @click="removeRefDoc(idx)">Remove</VButton>
                </div>
              </div>
              <CodeEditor
                v-model="d.content"
                mime-type="text/markdown"
                :rows="10"
              />
            </div>
            <VButton variant="ghost" size="sm" @click="addRefDoc">+ Add reference doc</VButton>
          </div>
        </VCard>

        <VCard title="Scripts">
          <div class="flex flex-col gap-3">
            <div
              v-for="(s, idx) in form.scripts"
              :key="'scr-' + idx"
              class="script-row"
            >
              <div class="grid grid-cols-3 gap-2 mb-2">
                <VInput v-model="s.name" label="Name" required />
                <VSelect v-model="s.target" :options="scriptTargetOptions" label="Target" />
                <div class="flex items-end justify-end">
                  <VButton variant="ghost" size="sm" @click="removeScript(idx)">Remove</VButton>
                </div>
              </div>
              <VInput
                v-model="s.description"
                label="Description"
                help="Becomes the tool description the LLM sees once Phase 2 mounts scripts as tools."
              />
              <CodeEditor
                v-model="s.content"
                mime-type="application/javascript"
                :rows="14"
              />
            </div>
            <VButton variant="ghost" size="sm" @click="addScript">+ Add script</VButton>
          </div>
        </VCard>
      </template>
    </div>

    <!-- ─── Right panel: help ─── -->
    <template #right-panel>
      <div class="p-4 flex flex-col gap-4">
        <section>
          <h3 class="text-xs uppercase opacity-60 mb-2">Field reference</h3>
          <div v-if="help.loading.value" class="text-xs opacity-60">Loading…</div>
          <div v-else-if="help.error.value" class="text-xs opacity-60">
            Help unavailable: {{ help.error.value }}
          </div>
          <div v-else-if="!help.content.value" class="text-xs opacity-60">
            No help content for this resource.
          </div>
          <MarkdownView v-else :source="help.content.value" />
        </section>
      </div>
    </template>

    <!-- ─── New-skill modal ─── -->
    <VModal v-model="showNewModal" title="New skill">
      <div class="flex flex-col gap-3">
        <VAlert v-if="newError" variant="error">
          <span>{{ newError }}</span>
        </VAlert>
        <VInput
          v-model="newName"
          label="Name"
          required
          help="Lower-case alphanumerics, '-' or '_' allowed. Must not collide with an existing skill at this scope."
        />
        <VInput v-model="newTitle" label="Title" required />
        <VInput v-model="newDescription" label="Description" required />
        <p class="text-xs opacity-70">
          A stub skill will be created at the
          <strong>{{ scope.kind }}</strong> scope. Fill in the details
          afterwards.
        </p>
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showNewModal = false">Cancel</VButton>
          <VButton
            variant="primary"
            :loading="skillsState.busy.value"
            @click="submitNewSkill"
          >Create</VButton>
        </div>
      </div>
    </VModal>
  </EditorShell>
</template>

<style scoped>
.skill-item {
  display: block;
  text-align: left;
  padding: 0.5rem 0.6rem;
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  width: 100%;
  border: 1px solid transparent;
}
.skill-item:hover { background: hsl(var(--bc) / 0.06); }
.skill-item--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
}
.badge-bundled { background: hsl(var(--bc) / 0.08); color: hsl(var(--bc) / 0.7); }
.badge-tenant  { background: hsl(var(--in) / 0.18); color: hsl(var(--inc)); }
.badge-project { background: hsl(var(--su) / 0.18); color: hsl(var(--suc)); }
.badge-user    { background: hsl(var(--wa) / 0.18); color: hsl(var(--wac)); }

.trigger-row,
.refdoc-row,
.script-row {
  border: 1px solid hsl(var(--bc) / 0.12);
  border-radius: 0.5rem;
  padding: 0.75rem;
}
</style>
