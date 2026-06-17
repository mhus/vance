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
} from '@/components';
import { useSchedulers } from '@/composables/useSchedulers';
import type { SchedulerSummary } from '@vance/generated';

const DEFAULT_TEMPLATE = `description: "Spawns the analyze recipe every weekday at 08:00."
cron: "0 0 8 * * MON-FRI"
timezone: "Europe/Berlin"
recipe: "analyze"
overlap: skip
# runAs: <username>          # defaults to your account
# initialMessage: |
#   Draft the morning briefing.
# params:
#   model: default:fast
`;

const props = defineProps<{ projectId: string | null }>();

const { t } = useI18n();
const state = useSchedulers();

const selectedName = ref<string | null>(null);
const yamlDraft = ref<string>('');
const banner = ref<string | null>(null);

const showNewModal = ref(false);
const newName = ref('');
const newError = ref<string | null>(null);

const showDeleteModal = ref(false);

const isModified = computed<boolean>(() => {
  if (!state.current.value) return false;
  return yamlDraft.value !== state.current.value.yaml;
});

const sortedSchedulers = computed<SchedulerSummary[]>(() =>
  [...state.schedulers.value].sort((a, b) => a.name.localeCompare(b.name)));

watch(
  () => props.projectId,
  (next) => {
    selectedName.value = null;
    yamlDraft.value = '';
    banner.value = null;
    state.clearCurrent();
    if (next) {
      void state.loadProject(next);
    } else {
      state.schedulers.value = [];
    }
  },
  { immediate: true },
);

async function selectScheduler(name: string): Promise<void> {
  if (!props.projectId) return;
  selectedName.value = name;
  await state.loadOne(props.projectId, name);
  yamlDraft.value = state.current.value?.yaml ?? '';
  await state.loadEvents(props.projectId, name, 20);
}

async function refreshList(): Promise<void> {
  if (!props.projectId) return;
  banner.value = null;
  try {
    const registered = await state.refresh(props.projectId);
    banner.value = t('scheduler.refreshedHint', { count: registered });
  } catch {
    /* error surfaced via state.error */
  }
}

function openNewModal(): void {
  newName.value = '';
  newError.value = null;
  showNewModal.value = true;
}

async function createScheduler(): Promise<void> {
  if (!props.projectId) return;
  const name = newName.value.trim().toLowerCase();
  if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(name)) {
    newError.value = t('scheduler.invalidNameHint');
    return;
  }
  if (state.schedulers.value.some(s => s.name === name)) {
    newError.value = t('scheduler.duplicateNameHint');
    return;
  }
  try {
    await state.save(props.projectId, name, DEFAULT_TEMPLATE);
    showNewModal.value = false;
    await selectScheduler(name);
    banner.value = t('scheduler.createdHint', { name });
  } catch (e) {
    newError.value = e instanceof Error ? e.message : t('scheduler.saveFailed');
  }
}

async function fireCurrent(): Promise<void> {
  if (!props.projectId || !selectedName.value) return;
  banner.value = null;
  try {
    const result = await state.fire(props.projectId, selectedName.value);
    banner.value = t('scheduler.firedHint', {
      name: selectedName.value,
      correlationId: result.correlationId,
    });
  } catch {
    /* surfaced via state.error */
  }
}

async function saveCurrent(): Promise<void> {
  if (!props.projectId || !selectedName.value) return;
  banner.value = null;
  try {
    await state.save(props.projectId, selectedName.value, yamlDraft.value);
    banner.value = t('scheduler.savedHint', { name: selectedName.value });
    await state.loadEvents(props.projectId, selectedName.value, 20);
  } catch {
    /* surfaced via error */
  }
}

async function confirmDelete(): Promise<void> {
  if (!props.projectId || !selectedName.value) return;
  try {
    const name = selectedName.value;
    await state.remove(props.projectId, name);
    showDeleteModal.value = false;
    selectedName.value = null;
    yamlDraft.value = '';
    banner.value = t('scheduler.deletedHint', { name });
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
      {{ $t('scheduler.pickProject') }}
    </div>

    <template v-else>
      <!-- Top toolbar: refresh + new -->
      <div class="flex items-center gap-2 flex-wrap">
        <VButton size="sm" variant="ghost" @click="refreshList">
          ↻ {{ t('scheduler.refresh') }}
        </VButton>
        <VButton size="sm" variant="primary" @click="openNewModal">
          {{ t('scheduler.new') }}
        </VButton>
      </div>

      <VAlert v-if="state.error.value" variant="error">{{ state.error.value }}</VAlert>
      <VAlert v-if="banner" variant="success">{{ banner }}</VAlert>

      <!-- 3-column body: list / editor / events -->
      <div class="grid grid-cols-12 gap-3">
        <!-- List -->
        <nav class="col-span-3 flex flex-col gap-1">
          <span class="text-sm font-semibold mb-1">{{ t('scheduler.listLabel') }}</span>
          <ul v-if="sortedSchedulers.length" class="space-y-1">
            <li v-for="s in sortedSchedulers" :key="s.name">
              <button
                :class="[
                  'w-full text-left p-2 rounded',
                  selectedName === s.name ? 'bg-primary/15' : 'hover:bg-base-200',
                ]"
                @click="selectScheduler(s.name)"
              >
                <div class="flex items-center justify-between gap-2">
                  <span class="font-mono text-sm">{{ s.name }}</span>
                  <span
                    :class="[
                      'text-xs px-1.5 py-0.5 rounded',
                      s.enabled ? 'bg-success/20 text-success' : 'bg-base-300 text-base-content/60',
                    ]"
                  >
                    {{ s.enabled ? t('scheduler.enabled') : t('scheduler.disabled') }}
                  </span>
                </div>
                <div class="text-xs text-base-content/60 truncate">{{ s.description }}</div>
                <div class="text-xs text-base-content/50 font-mono">{{ s.cron }}</div>
              </button>
            </li>
          </ul>
          <VEmptyState
            v-else
            :headline="t('scheduler.emptyTitle')"
            :body="t('scheduler.emptyBody')"
          />
        </nav>

        <!-- Editor -->
        <div class="col-span-6 flex flex-col gap-3">
          <VCard v-if="state.current.value">
            <template #header>
              <div class="flex items-center justify-between w-full">
                <span>{{ state.current.value.name }}</span>
                <span class="text-xs text-base-content/60">{{ state.current.value.source }}</span>
              </div>
            </template>

            <CodeEditor v-model="yamlDraft" mime-type="application/yaml" :rows="22" />

            <template #actions>
              <VButton variant="ghost" @click="showDeleteModal = true">
                {{ t('common.delete') }}
              </VButton>
              <VButton
                variant="ghost"
                :disabled="isModified || state.busy.value"
                :title="t('scheduler.fireHint')"
                @click="fireCurrent"
              >
                ▶ {{ t('scheduler.fire') }}
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
            :headline="t('scheduler.selectTitle')"
            :body="t('scheduler.selectBody')"
          />
        </div>

        <!-- Right: run history -->
        <aside class="col-span-3 flex flex-col gap-2">
          <h3 class="text-sm font-semibold">{{ t('scheduler.runHistory') }}</h3>
          <div v-if="!state.events.value.length" class="text-sm text-base-content/60">
            {{ t('scheduler.noEvents') }}
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
                {{ t('scheduler.process') }}: {{ e.processId }}
              </div>
              <div v-if="e.payload?.error" class="text-error">
                {{ e.payload.error }}
              </div>
              <div v-if="e.payload?.reason" class="text-base-content/60">
                {{ t('scheduler.reason') }}: {{ e.payload.reason }}
              </div>
            </li>
          </ul>
        </aside>
      </div>
    </template>

    <VModal v-model="showNewModal" :title="t('scheduler.newTitle')">
      <div class="space-y-3">
        <label class="block">
          <span class="text-sm font-semibold">{{ t('scheduler.nameLabel') }}</span>
          <input
            v-model="newName"
            class="input input-bordered w-full mt-1"
            :placeholder="t('scheduler.namePlaceholder')"
            @keyup.enter="createScheduler"
          />
        </label>
        <p class="text-xs text-base-content/60">{{ t('scheduler.namePatternHint') }}</p>
        <VAlert v-if="newError" variant="error">{{ newError }}</VAlert>
      </div>
      <template #actions>
        <VButton variant="ghost" @click="showNewModal = false">
          {{ t('common.cancel') }}
        </VButton>
        <VButton variant="primary" :loading="state.busy.value" @click="createScheduler">
          {{ t('scheduler.create') }}
        </VButton>
      </template>
    </VModal>

    <VModal v-model="showDeleteModal" :title="t('scheduler.deleteTitle')">
      <p>{{ t('scheduler.deleteBody', { name: selectedName }) }}</p>
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
