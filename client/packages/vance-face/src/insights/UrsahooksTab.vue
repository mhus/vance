<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  CodeEditor,
  VAlert,
  VButton,
  VCard,
  VEmptyState,
  VModal,
  VSelect,
} from '@/components';
import { useUrsahooks } from '@/composables/useUrsahooks';
import type { UrsaHookSummary } from '@vance/generated';

/** Wire-form event names (Java {@code UrsaHookEventName.wireName()}). */
type HookEventEntry = { wire: string; active: boolean };
const HOOK_EVENTS: HookEventEntry[] = [
  { wire: 'process.completed', active: true },
  { wire: 'process.failed', active: true },
  { wire: 'inbox.item.created', active: true },
  { wire: 'session.suspended', active: false },
  { wire: 'session.resumed', active: false },
  { wire: 'insight.saved', active: false },
  { wire: 'relation.created', active: false },
];

const DEFAULT_TEMPLATE = `description: "Triage notes for finished analyze runs."
enabled: true
timeout: 5s
recipe: notes-triage
params:
  parentProcessId: "\${event.process.id}"
initialMessage: "Bitte triage die Notes des Parent-Process."
`;

const props = defineProps<{ projectId: string | null }>();

const { t } = useI18n();
const state = useUrsahooks();

const selectedEvent = ref<string | null>(null);
const selectedName = ref<string | null>(null);
const yamlDraft = ref<string>('');
const banner = ref<string | null>(null);

const eventOptions = computed(() =>
  HOOK_EVENTS.map(e => ({
    value: e.wire,
    label: e.wire + (e.active ? '' : ' (reserved)'),
  })),
);

const showNewModal = ref(false);
const newName = ref('');
const newEvent = ref<string>('process.completed');
const newError = ref<string | null>(null);

const showDeleteModal = ref(false);

const isModified = computed<boolean>(() => {
  if (!state.current.value) return false;
  return yamlDraft.value !== state.current.value.yaml;
});

/** Grouped by event, sorted by catalog order then by name. */
const groupedHooks = computed<Array<{ event: string; hooks: UrsaHookSummary[] }>>(() => {
  const byEvent = new Map<string, UrsaHookSummary[]>();
  for (const h of state.hooks.value) {
    const list = byEvent.get(h.event) ?? [];
    list.push(h);
    byEvent.set(h.event, list);
  }
  const out: Array<{ event: string; hooks: UrsaHookSummary[] }> = [];
  for (const cat of HOOK_EVENTS) {
    const hooks = byEvent.get(cat.wire);
    if (hooks && hooks.length) {
      out.push({
        event: cat.wire,
        hooks: [...hooks].sort((a, b) => a.name.localeCompare(b.name)),
      });
    }
  }
  return out;
});

function isSelected(event: string, name: string): boolean {
  return selectedEvent.value === event && selectedName.value === name;
}

watch(
  () => props.projectId,
  (next) => {
    selectedEvent.value = null;
    selectedName.value = null;
    yamlDraft.value = '';
    banner.value = null;
    state.clearCurrent();
    if (next) {
      void state.loadProject(next);
    } else {
      state.hooks.value = [];
    }
  },
  { immediate: true },
);

async function selectHook(event: string, name: string): Promise<void> {
  if (!props.projectId) return;
  selectedEvent.value = event;
  selectedName.value = name;
  await state.loadOne(props.projectId, event, name);
  yamlDraft.value = state.current.value?.yaml ?? '';
  await state.loadEvents(props.projectId, event, name, 20);
}

async function refreshList(): Promise<void> {
  if (!props.projectId) return;
  banner.value = null;
  try {
    const registered = await state.refresh(props.projectId);
    banner.value = t('ursahooks.refreshedHint', { count: registered });
  } catch {
    /* error surfaced via state.error */
  }
}

function openNewModal(): void {
  newName.value = '';
  newEvent.value = 'process.completed';
  newError.value = null;
  showNewModal.value = true;
}

async function createHook(): Promise<void> {
  if (!props.projectId) return;
  const name = newName.value.trim().toLowerCase();
  if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(name)) {
    newError.value = t('ursahooks.invalidNameHint');
    return;
  }
  const event = newEvent.value;
  if (state.hooks.value.some(h => h.event === event && h.name === name)) {
    newError.value = t('ursahooks.duplicateNameHint');
    return;
  }
  try {
    await state.save(props.projectId, event, name, DEFAULT_TEMPLATE);
    showNewModal.value = false;
    await selectHook(event, name);
    banner.value = t('ursahooks.createdHint', { name });
  } catch (e) {
    newError.value = e instanceof Error ? e.message : t('ursahooks.saveFailed');
  }
}

async function saveCurrent(): Promise<void> {
  if (!props.projectId || !selectedName.value || !selectedEvent.value) return;
  banner.value = null;
  try {
    await state.save(
      props.projectId,
      selectedEvent.value,
      selectedName.value,
      yamlDraft.value,
    );
    banner.value = t('ursahooks.savedHint', { name: selectedName.value });
    await state.loadEvents(
      props.projectId,
      selectedEvent.value,
      selectedName.value,
      20,
    );
  } catch {
    /* surfaced via error */
  }
}

async function confirmDelete(): Promise<void> {
  if (!props.projectId || !selectedName.value || !selectedEvent.value) return;
  try {
    const name = selectedName.value;
    await state.remove(props.projectId, selectedEvent.value, name);
    showDeleteModal.value = false;
    selectedEvent.value = null;
    selectedName.value = null;
    yamlDraft.value = '';
    banner.value = t('ursahooks.deletedHint', { name });
  } catch {
    showDeleteModal.value = false;
  }
}

function formatTimestamp(value?: Date | string | null): string {
  if (!value) return '—';
  const d = typeof value === 'string' ? new Date(value) : value;
  return d.toLocaleString();
}
</script>

<template>
  <div class="flex flex-col gap-3 p-2">
    <div v-if="!projectId" class="opacity-60 text-sm">
      {{ $t('ursahooks.pickProject') }}
    </div>

    <template v-else>
      <!-- Top toolbar: refresh + new -->
      <div class="flex items-center gap-2 flex-wrap">
        <VButton size="sm" variant="ghost" @click="refreshList">
          ↻ {{ t('ursahooks.refresh') }}
        </VButton>
        <VButton size="sm" variant="primary" @click="openNewModal">
          {{ t('ursahooks.new') }}
        </VButton>
      </div>

      <VAlert v-if="state.error.value" variant="error">{{ state.error.value }}</VAlert>
      <VAlert v-if="banner" variant="success">{{ banner }}</VAlert>

      <!-- 3-column body: list / editor / events -->
      <div class="grid grid-cols-12 gap-3">
        <!-- List grouped by event -->
        <nav class="col-span-3 flex flex-col gap-2">
          <span class="text-sm font-semibold">{{ t('ursahooks.listLabel') }}</span>
          <template v-if="groupedHooks.length">
            <section v-for="group in groupedHooks" :key="group.event" class="space-y-1">
              <h4 class="text-xs font-semibold opacity-70 font-mono">{{ group.event }}</h4>
              <ul class="space-y-1">
                <li v-for="h in group.hooks" :key="h.name">
                  <button
                    :class="[
                      'w-full text-left p-2 rounded',
                      isSelected(group.event, h.name) ? 'bg-primary/15' : 'hover:bg-base-200',
                    ]"
                    @click="selectHook(group.event, h.name)"
                  >
                    <div class="flex items-center justify-between gap-2">
                      <span class="font-mono text-sm">{{ h.name }}</span>
                      <span
                        :class="[
                          'text-xs px-1.5 py-0.5 rounded',
                          h.enabled ? 'bg-success/20 text-success' : 'bg-base-300 text-base-content/60',
                        ]"
                      >
                        {{ h.enabled ? t('ursahooks.enabled') : t('ursahooks.disabled') }}
                      </span>
                    </div>
                    <div class="text-xs text-base-content/60 truncate">
                      {{ h.description || h.actionType }}
                    </div>
                  </button>
                </li>
              </ul>
            </section>
          </template>
          <VEmptyState
            v-else
            :headline="t('ursahooks.emptyTitle')"
            :body="t('ursahooks.emptyBody')"
          />
        </nav>

        <!-- Editor -->
        <div class="col-span-6 flex flex-col gap-3">
          <VCard v-if="state.current.value">
            <template #header>
              <div class="flex items-center justify-between w-full">
                <span class="font-mono text-sm">
                  {{ state.current.value.event }} / {{ state.current.value.name }}
                </span>
                <span class="text-xs text-base-content/60">{{ state.current.value.source }}</span>
              </div>
            </template>

            <CodeEditor v-model="yamlDraft" mime-type="application/yaml" :rows="22" />

            <template #actions>
              <VButton variant="ghost" @click="showDeleteModal = true">
                {{ t('common.delete') }}
              </VButton>
              <VButton
                variant="primary"
                :disabled="!isModified || state.busy.value"
                @click="saveCurrent"
              >
                {{ t('common.save') }}
              </VButton>
            </template>
          </VCard>

          <VEmptyState
            v-else
            :headline="t('ursahooks.selectTitle')"
            :body="t('ursahooks.selectBody')"
          />
        </div>

        <!-- Right: run history -->
        <aside class="col-span-3 flex flex-col gap-2">
          <h3 class="text-sm font-semibold">{{ t('ursahooks.runHistory') }}</h3>
          <div v-if="!state.events.value.length" class="text-sm text-base-content/60">
            {{ t('ursahooks.noEvents') }}
          </div>
          <ul v-else class="space-y-2">
            <li
              v-for="e in state.events.value"
              :key="e.id"
              class="border border-base-300 rounded p-2 text-xs space-y-0.5"
            >
              <div class="flex items-center justify-between">
                <span class="font-mono font-semibold">{{ e.type }}</span>
                <span class="text-base-content/60">{{ formatTimestamp(e.timestamp) }}</span>
              </div>
              <div v-if="e.processId" class="text-base-content/60">
                {{ t('ursahooks.process') }}: {{ e.processId }}
              </div>
              <div v-if="e.payload?.error" class="text-error">
                {{ e.payload.error }}
              </div>
              <div v-if="e.payload?.reason" class="text-base-content/60">
                {{ t('ursahooks.reason') }}: {{ e.payload.reason }}
              </div>
            </li>
          </ul>
        </aside>
      </div>
    </template>

    <VModal v-model="showNewModal" :title="t('ursahooks.newTitle')">
      <div class="space-y-3">
        <VSelect
          v-model="newEvent"
          :options="eventOptions"
          :label="t('ursahooks.eventLabel')"
        />
        <label class="block">
          <span class="text-sm font-semibold">{{ t('ursahooks.nameLabel') }}</span>
          <input
            v-model="newName"
            class="input input-bordered w-full mt-1"
            :placeholder="t('ursahooks.namePlaceholder')"
            @keyup.enter="createHook"
          />
        </label>
        <p class="text-xs text-base-content/60">{{ t('ursahooks.namePatternHint') }}</p>
        <VAlert v-if="newError" variant="error">{{ newError }}</VAlert>
      </div>
      <template #actions>
        <VButton variant="ghost" @click="showNewModal = false">
          {{ t('common.cancel') }}
        </VButton>
        <VButton variant="primary" :loading="state.busy.value" @click="createHook">
          {{ t('ursahooks.create') }}
        </VButton>
      </template>
    </VModal>

    <VModal v-model="showDeleteModal" :title="t('ursahooks.deleteTitle')">
      <p>{{ t('ursahooks.deleteBody', { event: selectedEvent, name: selectedName }) }}</p>
      <template #actions>
        <VButton variant="ghost" @click="showDeleteModal = false">
          {{ t('common.cancel') }}
        </VButton>
        <VButton variant="danger" :loading="state.busy.value" @click="confirmDelete">
          {{ t('common.delete') }}
        </VButton>
      </template>
    </VModal>
  </div>
</template>
