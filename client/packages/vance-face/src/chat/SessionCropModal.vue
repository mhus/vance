<script setup lang="ts">
import { VAlert, VButton, VModal, VIconButton } from '@components/index';
import { ChatRole, type ChatMessageDto } from '@vance/generated';
import { cropSession, getCropMessages } from '@vance/shared';
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';

/**
 * Modify/Crop editor for a session's chat memory. Lists the logical
 * conversation (incl. already-removed messages) and lets the owner mark
 * messages as removed — individually or "crop from here" (this message and
 * everything after) — and restore them again. Nothing persists until
 * "Apply", which sends the remove/restore diff in one call.
 *
 * Opened from the session list menu; works on any session (not just the
 * one currently open in a chat tab). See
 * {@code specification/public/session-crop.md}.
 */
const props = defineProps<{
  modelValue: boolean;
  sessionId: string | null;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
  /** Fired after a successful apply (something actually changed). */
  (e: 'applied'): void;
}>();

const { t } = useI18n();

const messages = ref<ChatMessageDto[]>([]);
const loading = ref(false);
const saving = ref(false);
const error = ref<string | null>(null);

// Desired removed set (message ids). Initialised from the server state on
// load; edited locally; diffed against `original` on apply.
const removed = ref<Set<string>>(new Set());
const original = ref<Set<string>>(new Set());

function isRemovedOnServer(m: ChatMessageDto): boolean {
  return m.meta?.kind === 'removed';
}

async function load(sessionId: string): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const data = await getCropMessages(sessionId);
    messages.value = data;
    const init = new Set<string>();
    for (const m of data) if (isRemovedOnServer(m)) init.add(m.messageId);
    removed.value = new Set(init);
    original.value = new Set(init);
  } catch (e) {
    error.value = t('chat.crop.loadError') + ' ' + (e as Error).message;
    messages.value = [];
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.modelValue, props.sessionId] as const,
  ([open, id]) => {
    if (open && id) void load(id);
  },
  { immediate: true },
);

function isMarked(id: string): boolean {
  return removed.value.has(id);
}

function toggle(id: string): void {
  const next = new Set(removed.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  removed.value = next;
}

/** Mark this message and every later one as removed. */
function cropFrom(index: number): void {
  const next = new Set(removed.value);
  for (let i = index; i < messages.value.length; i++) {
    next.add(messages.value[i].messageId);
  }
  removed.value = next;
}

/** Clear all pending marks — back to the server state. */
function reset(): void {
  removed.value = new Set(original.value);
}

const toRemove = computed(() =>
  [...removed.value].filter((id) => !original.value.has(id)));
const toRestore = computed(() =>
  [...original.value].filter((id) => !removed.value.has(id)));
const pendingCount = computed(() => toRemove.value.length + toRestore.value.length);

async function apply(): Promise<void> {
  if (!props.sessionId || pendingCount.value === 0) return;
  saving.value = true;
  error.value = null;
  try {
    const data = await cropSession(props.sessionId, {
      remove: toRemove.value,
      restore: toRestore.value,
    });
    messages.value = data;
    const init = new Set<string>();
    for (const m of data) if (isRemovedOnServer(m)) init.add(m.messageId);
    removed.value = new Set(init);
    original.value = new Set(init);
    emit('applied');
  } catch (e) {
    error.value = t('chat.crop.applyError') + ' ' + (e as Error).message;
  } finally {
    saving.value = false;
  }
}

function close(): void {
  emit('update:modelValue', false);
}

function roleLabel(role: ChatRole): string {
  switch (role) {
    case ChatRole.USER: return t('chat.crop.roleUser');
    case ChatRole.ASSISTANT: return t('chat.crop.roleAssistant');
    case ChatRole.SYSTEM: return t('chat.crop.roleSystem');
    default: return String(role);
  }
}

/** Row state relative to server: marked-new (will remove), unmarked-was-removed
 *  (will restore), currently-removed-and-still-marked, or plain. */
function rowState(m: ChatMessageDto): 'willRemove' | 'willRestore' | 'removed' | 'plain' {
  const marked = removed.value.has(m.messageId);
  const wasRemoved = original.value.has(m.messageId);
  if (marked && !wasRemoved) return 'willRemove';
  if (!marked && wasRemoved) return 'willRestore';
  if (marked && wasRemoved) return 'removed';
  return 'plain';
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    :title="t('chat.crop.title')"
    :description="t('chat.crop.description')"
    size="lg"
    :close-on-backdrop="false"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <div class="flex flex-col gap-4">
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>

      <div v-if="loading" class="text-sm text-(--v-label-secondary) py-6 text-center">
        {{ t('chat.crop.loading') }}
      </div>
      <div
        v-else-if="messages.length === 0"
        class="text-sm text-(--v-label-secondary) py-6 text-center"
      >
        {{ t('chat.crop.empty') }}
      </div>

      <ul v-else class="flex flex-col gap-2">
        <li
          v-for="(m, index) in messages"
          :key="m.messageId"
          class="crop-row"
          :class="{
            'crop-row-remove': rowState(m) === 'willRemove' || rowState(m) === 'removed',
            'crop-row-restore': rowState(m) === 'willRestore',
          }"
        >
          <input
            type="checkbox"
            class="mt-1 shrink-0"
            :checked="isMarked(m.messageId)"
            :title="t('chat.crop.removeToggle')"
            :aria-label="t('chat.crop.removeToggle')"
            @change="toggle(m.messageId)"
          />
          <div class="min-w-0 flex-1">
            <div
              class="flex items-center gap-2 text-[11px] uppercase tracking-wide
                     text-(--v-label-secondary)"
            >
              <span>{{ roleLabel(m.role) }}</span>
              <span
                v-if="rowState(m) === 'removed'"
                class="crop-badge crop-badge-remove"
              >{{ t('chat.crop.removedBadge') }}</span>
              <span
                v-else-if="rowState(m) === 'willRemove'"
                class="crop-badge crop-badge-remove"
              >{{ t('chat.crop.willRemove') }}</span>
              <span
                v-else-if="rowState(m) === 'willRestore'"
                class="crop-badge crop-badge-restore"
              >{{ t('chat.crop.willRestore') }}</span>
            </div>
            <div
              class="crop-content text-sm whitespace-pre-wrap break-words"
              :class="{ 'line-through text-(--v-label-tertiary)': isMarked(m.messageId) }"
            >{{ m.content }}</div>
          </div>
          <VIconButton
            icon="crop"
            :label="t('chat.crop.cropFromHere')"
            size="sm"
            @click="cropFrom(index)"
          />
        </li>
      </ul>
    </div>

    <template #actions>
      <span class="mr-auto text-xs text-(--v-label-secondary)">
        {{ pendingCount === 0 ? t('chat.crop.pendingNone') : t('chat.crop.pending', { n: pendingCount }) }}
      </span>
      <VButton
        v-if="pendingCount > 0"
        variant="plain"
        :disabled="saving"
        @click="reset"
      >{{ t('chat.crop.undoCrop') }}</VButton>
      <VButton variant="secondary" :disabled="saving" @click="close">
        {{ t('chat.crop.cancel') }}
      </VButton>
      <VButton
        variant="primary"
        :loading="saving"
        :disabled="saving || pendingCount === 0"
        @click="apply"
      >{{ t('chat.crop.apply') }}</VButton>
    </template>
  </VModal>
</template>

<style scoped>
/* A message row; removal / restore states tint via the semantic tokens. */
.crop-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px;
  border: 1px solid var(--v-separator);
  border-radius: 6px;
  transition: background-color 120ms, border-color 120ms;
}
.crop-row-remove {
  border-color: color-mix(in srgb, var(--v-danger) 50%, transparent);
  background: color-mix(in srgb, var(--v-danger) 5%, transparent);
}
.crop-row-restore {
  border-color: color-mix(in srgb, var(--v-success) 50%, transparent);
  background: color-mix(in srgb, var(--v-success) 5%, transparent);
}
.crop-badge {
  padding: 0 4px;
  border-radius: 4px;
  text-transform: none;
}
.crop-badge-remove {
  color: var(--v-danger);
  background: color-mix(in srgb, var(--v-danger) 15%, transparent);
}
.crop-badge-restore {
  color: var(--v-success);
  background: color-mix(in srgb, var(--v-success) 15%, transparent);
}
.crop-content {
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
