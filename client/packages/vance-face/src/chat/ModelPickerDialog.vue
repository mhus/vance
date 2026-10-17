<script setup lang="ts">
/**
 * Model + reasoning-level picker for the running chat process
 * (planning/model-picker.md §5). Lists the models the brain offers for the
 * project (`GET projects/{project}/chat-models`), reads the process's
 * current state via `//model get`, and applies a choice through the same
 * `//model` / `//thinking` engine commands a user could type. The brain
 * validates again on apply — the list is convenience, not enforcement.
 */
import type {
  ChatModelDto,
  ChatModelListDto,
  ChatModelStateDto,
  ProcessCommandRequest,
  ProcessCommandResponse,
} from '@vance/generated';
import { EngineCommandOutcome } from '@vance/generated';
import { brainFetch, type BrainWsApi } from '@vance/shared';
import { computed, nextTick, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  VAlert,
  VButton,
  VEmptyState,
  VGroup,
  VGroupRow,
  VIcon,
  VInput,
  VModal,
  VSegment,
  type SegmentOption,
} from '@components/index';
import {
  RECIPE_DEFAULT,
  commandsFor,
  filterModels,
  formatContext,
  formatPrice,
  groupModels,
  initialSelection,
  initialThinking,
  selectionSupportsThinking,
} from './modelPicker';

const props = defineProps<{
  open: boolean;
  socket: BrainWsApi;
  processName: string;
  projectId: string;
}>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  /**
   * The process's state after an apply — also after a partly failed one, so
   * the host's indicator never shows a model the process no longer runs.
   */
  (e: 'applied', state: ChatModelStateDto): void;
}>();

const { t } = useI18n();

const loading = ref(false);
const applying = ref(false);
const error = ref<string | null>(null);
const list = ref<ChatModelListDto | null>(null);
const state = ref<ChatModelStateDto | null>(null);
const filter = ref('');
const selection = ref<string>(RECIPE_DEFAULT);
const thinking = ref('off');
/** Keyboard cursor over {@link rows}. */
const cursor = ref(0);
const inputRef = ref<InstanceType<typeof VInput> | null>(null);
const listRef = ref<HTMLElement | null>(null);

const models = computed<ChatModelDto[]>(() => list.value?.models ?? []);
const filtered = computed(() => filterModels(models.value, filter.value));
const groups = computed(() => groupModels(filtered.value));
/** Flat order the keyboard walks: recipe default first, then the groups. */
const rows = computed<string[]>(() => [RECIPE_DEFAULT, ...filtered.value.map((m) => m.spec)]);

const thinkingEnabled = computed(() =>
  state.value ? selectionSupportsThinking(selection.value, state.value, models.value) : true,
);
const thinkingOptions = computed<SegmentOption<string>[]>(() =>
  (list.value?.thinkingLevels ?? ['off', 'minimal', 'low', 'medium', 'high']).map((level) => ({
    value: level,
    label: t(`chat.modelPicker.level.${level}`),
  })),
);
const pendingCommands = computed(() =>
  state.value ? commandsFor(state.value, selection.value, thinking.value, thinkingEnabled.value) : [],
);
/** Apply is possible once the state is loaded and nothing is in flight — button and Enter alike. */
const canApply = computed(() => !loading.value && !applying.value && !!state.value);

watch(
  () => props.open,
  (open) => {
    if (open) void load();
  },
  { immediate: true },
);

watch(filter, () => {
  cursor.value = 0;
});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  filter.value = '';
  cursor.value = 0;
  try {
    const [listed, current] = await Promise.all([
      brainFetch<ChatModelListDto>('GET', `projects/${encodeURIComponent(props.projectId)}/chat-models`),
      runCommand('model', 'get'),
    ]);
    list.value = listed;
    state.value = current;
    selection.value = initialSelection(current);
    thinking.value = initialThinking(current);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
  await nextTick();
  setTimeout(() => inputRef.value?.input?.focus(), 0);
}

/** Round-trips one engine command; resolves to the state the brain reports back. */
async function runCommand(verb: string, text: string): Promise<ChatModelStateDto> {
  const reply = await props.socket.send<ProcessCommandRequest, ProcessCommandResponse>('process-command', {
    processName: props.processName,
    command: verb,
    params: { text },
  });
  if (reply.outcome !== EngineCommandOutcome.OK) {
    throw new Error(reply.message ?? t('chat.modelPicker.commandFailed', { verb }));
  }
  return reply.value as ChatModelStateDto;
}

async function apply(): Promise<void> {
  if (!canApply.value) return;
  const commands = pendingCommands.value;
  if (commands.length === 0) {
    close();
    return;
  }
  applying.value = true;
  error.value = null;
  try {
    for (const c of commands) {
      await runCommand(c.verb, c.text);
    }
    // `//thinking` replies without a state; ask once for the final picture.
    const after = await runCommand('model', 'get');
    emit('applied', after);
    close();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    // An earlier command may have gone through (`//model` set, `//thinking`
    // refused). Report the real state so the host's indicator does not keep
    // showing the old model; the dialog stays open with the error.
    await reportStateAfterFailure();
  } finally {
    applying.value = false;
  }
}

async function reportStateAfterFailure(): Promise<void> {
  try {
    const after = await runCommand('model', 'get');
    state.value = after;
    emit('applied', after);
  } catch {
    // The apply error above is already shown; nothing more to report.
  }
}

function close(): void {
  emit('update:open', false);
}

function pick(spec: string): void {
  selection.value = spec;
  cursor.value = rows.value.indexOf(spec);
}

function onFilterKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    const max = rows.value.length - 1;
    cursor.value = Math.max(0, Math.min(max, cursor.value + (event.key === 'ArrowDown' ? 1 : -1)));
    selection.value = rows.value[cursor.value];
    void nextTick(() => {
      listRef.value?.querySelector('[data-model-cursor="true"]')?.scrollIntoView({ block: 'nearest' });
    });
  } else if (event.key === 'Enter') {
    event.preventDefault();
    void apply();
  }
}

function description(m: ChatModelDto): string {
  const parts = [m.size === 'SMALL' ? t('chat.modelPicker.small') : t('chat.modelPicker.large')];
  parts.push(t('chat.modelPicker.context', { n: formatContext(m.contextWindow) }));
  if (m.thinking) parts.push(t('chat.modelPicker.reasoning'));
  return parts.join(' · ');
}

function price(m: ChatModelDto): string {
  return formatPrice(m.pricing) ?? t('chat.modelPicker.noPrice');
}
</script>

<template>
  <VModal
    :model-value="open"
    :title="t('chat.modelPicker.title')"
    :description="t('chat.modelPicker.description')"
    height="80vh"
    @update:model-value="(v: boolean) => emit('update:open', v)"
  >
    <div class="flex flex-col gap-3 h-full min-h-0">
      <VInput
        ref="inputRef"
        v-model="filter"
        :placeholder="t('chat.modelPicker.filter')"
        autocomplete="off"
        @keydown="onFilterKeydown"
      />

      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
      <VAlert v-else-if="state?.modelError" variant="warning">
        {{ t('chat.modelPicker.modelError', { model: state.model, error: state.modelError }) }}
      </VAlert>

      <div v-if="loading" class="text-sm text-(--v-label-secondary)">
        {{ t('chat.modelPicker.loading') }}
      </div>

      <div v-else ref="listRef" class="flex-1 min-h-0 overflow-y-auto flex flex-col gap-3">
        <VGroup>
          <VGroupRow
            navigates
            :label="t('chat.modelPicker.recipeDefault')"
            :description="state?.recipeModel ?? undefined"
            :class="{ 'model-picker-cursor': rows[cursor] === RECIPE_DEFAULT }"
            :data-model-cursor="rows[cursor] === RECIPE_DEFAULT ? 'true' : 'false'"
            @click="pick(RECIPE_DEFAULT)"
          >
            <VIcon v-if="selection === RECIPE_DEFAULT" name="check" />
          </VGroupRow>
        </VGroup>

        <VEmptyState
          v-if="filtered.length === 0"
          :headline="t('chat.modelPicker.noModels')"
          :body="t('chat.modelPicker.noModelsBody')"
        />

        <VGroup v-for="g in groups" :key="g.providerInstance" :label="g.providerInstance">
          <VGroupRow
            v-for="m in g.models"
            :key="m.spec"
            navigates
            :label="m.modelName"
            :description="description(m)"
            :value="price(m)"
            :class="{ 'model-picker-cursor': rows[cursor] === m.spec }"
            :data-model-cursor="rows[cursor] === m.spec ? 'true' : 'false'"
            :data-model-spec="m.spec"
            @click="pick(m.spec)"
          >
            <VIcon v-if="selection === m.spec" name="check" />
          </VGroupRow>
        </VGroup>
      </div>

      <div class="flex flex-col gap-1">
        <span class="text-xs font-semibold text-(--v-label-secondary)">{{ t('chat.modelPicker.reasoningLevel') }}</span>
        <VSegment
          v-model="thinking"
          :options="thinkingOptions"
          :disabled="!thinkingEnabled || loading"
          :aria-label="t('chat.modelPicker.reasoningLevel')"
        />
        <span v-if="!thinkingEnabled" class="text-xs text-(--v-label-tertiary)">
          {{ t('chat.modelPicker.noReasoning') }}
        </span>
      </div>
    </div>

    <template #actions>
      <VButton variant="secondary" @click="close">{{ t('chat.modelPicker.cancel') }}</VButton>
      <VButton variant="primary" :disabled="!canApply" @click="apply">
        {{ t('chat.modelPicker.apply') }}
      </VButton>
    </template>
  </VModal>
</template>

<style scoped>
.model-picker-cursor {
  background: color-mix(in srgb, var(--v-fill) 70%, transparent);
}
</style>
