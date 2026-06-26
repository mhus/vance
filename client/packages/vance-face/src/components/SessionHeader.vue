<script setup lang="ts">
import { VButton, VColorPicker, VEmojiPicker, VTagEditor } from '@vance/components';
import {
  AccentColor,
  SessionStatus,
  type SessionMetadataPatchRequest,
  type SessionSummaryRichDto,
} from '@vance/generated';
import {
  archiveSession,
  deleteSession,
  listSessions,
  patchSessionMetadata,
  reactivateSession,
} from '@vance/shared';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';

interface Props {
  sessionId: string;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'archived'): void;
  (e: 'reactivated'): void;
  (e: 'deleted'): void;
}>();

const { t } = useI18n();

const session = ref<SessionSummaryRichDto | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);
const editingTitle = ref(false);
const titleDraft = ref('');
const showColor = ref(false);
const showTags = ref(false);
const saving = ref(false);

// Responsive collapse: when the header has less than this many pixels of
// horizontal room, the five action buttons (pin/color/labels/archive/delete)
// fold into a single "⋯" overflow menu.
const COMPACT_THRESHOLD_PX = 520;
const rootEl = ref<HTMLElement | null>(null);
const menuEl = ref<HTMLElement | null>(null);
const containerWidth = ref<number>(Number.POSITIVE_INFINITY);
const compact = computed(() => containerWidth.value < COMPACT_THRESHOLD_PX);
const menuOpen = ref(false);
const menuExpand = ref<'color' | 'tags' | null>(null);

let resizeObserver: ResizeObserver | null = null;

function closeMenu(): void {
  menuOpen.value = false;
  menuExpand.value = null;
}

function onDocMouseDown(e: MouseEvent): void {
  if (!menuOpen.value) return;
  const target = e.target as Node | null;
  if (target && menuEl.value && menuEl.value.contains(target)) return;
  closeMenu();
}

onMounted(() => {
  if (rootEl.value) {
    containerWidth.value = rootEl.value.offsetWidth;
    resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        containerWidth.value = entry.contentRect.width;
      }
    });
    resizeObserver.observe(rootEl.value);
  }
  document.addEventListener('mousedown', onDocMouseDown);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  document.removeEventListener('mousedown', onDocMouseDown);
});

// Reset transient popover state when crossing the compact/wide threshold so
// the user never lands on a stranded panel that belongs to the other layout.
watch(compact, (isCompact) => {
  if (isCompact) {
    showColor.value = false;
    showTags.value = false;
  } else {
    closeMenu();
  }
});

const isArchived = computed(() => session.value?.status === SessionStatus.ARCHIVED);

const displayTitle = computed(() => {
  const s = session.value;
  if (!s) return props.sessionId;
  if (s.title && s.title.trim().length > 0) return s.title;
  if (s.firstUserMessage && s.firstUserMessage.trim().length > 0) {
    return s.firstUserMessage;
  }
  return t('chat.sessionHeader.untitled');
});

const colorAccentClass = computed(() => {
  // Match VColorPicker swatches at /15 opacity for a subtle left-border accent.
  switch (session.value?.color) {
    case AccentColor.SLATE: return 'border-l-4 border-slate-500';
    case AccentColor.RED: return 'border-l-4 border-red-500';
    case AccentColor.ORANGE: return 'border-l-4 border-orange-500';
    case AccentColor.AMBER: return 'border-l-4 border-amber-500';
    case AccentColor.GREEN: return 'border-l-4 border-green-500';
    case AccentColor.TEAL: return 'border-l-4 border-teal-500';
    case AccentColor.CYAN: return 'border-l-4 border-cyan-500';
    case AccentColor.BLUE: return 'border-l-4 border-blue-500';
    case AccentColor.INDIGO: return 'border-l-4 border-indigo-500';
    case AccentColor.PURPLE: return 'border-l-4 border-purple-500';
    case AccentColor.PINK: return 'border-l-4 border-pink-500';
    case AccentColor.ROSE: return 'border-l-4 border-rose-500';
    default: return '';
  }
});

async function load(sessionId: string): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    // No GET-by-id endpoint yet; the list endpoint with includeArchived
    // covers both active and archived sessions for the owner. Pull the
    // matching summary out of the result.
    const all = await listSessions({ includeArchived: true });
    session.value = all.find((s) => s.sessionId === sessionId) ?? null;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

watch(() => props.sessionId, (id) => {
  if (id) void load(id);
}, { immediate: true });

async function patch(patch: SessionMetadataPatchRequest): Promise<void> {
  if (!session.value) return;
  saving.value = true;
  error.value = null;
  try {
    const updated = await patchSessionMetadata(props.sessionId, patch);
    if (session.value) {
      session.value.title = updated.title ?? undefined;
      session.value.titleAutoGenerated = updated.titleAutoGenerated;
      session.value.icon = updated.icon ?? undefined;
      session.value.color = updated.color ?? undefined;
      session.value.tags = updated.tags ?? [];
      session.value.pinned = updated.pinned;
      session.value.allowMultipleClients = updated.allowMultipleClients;
    }
  } catch (e) {
    error.value = t('chat.sessionHeader.saveError') + ' ' + (e as Error).message;
  } finally {
    saving.value = false;
  }
}

function startTitleEdit(): void {
  if (isArchived.value) return;
  titleDraft.value = session.value?.title ?? '';
  editingTitle.value = true;
}

async function commitTitle(): Promise<void> {
  editingTitle.value = false;
  const next = titleDraft.value.trim();
  if (next === (session.value?.title ?? '')) return;
  await patch({ title: next });
}

function cancelTitle(): void {
  editingTitle.value = false;
}

async function onIcon(value: string | null): Promise<void> {
  if (isArchived.value) return;
  await patch({ icon: value ?? '' });
}

async function onColor(value: AccentColor | null): Promise<void> {
  if (isArchived.value) return;
  // PATCH only sets values — null means "don't change". To honour
  // "clear color" the controller would need a sentinel; left for v2.
  if (value === null) return;
  await patch({ color: value });
}

async function togglePin(): Promise<void> {
  if (isArchived.value || !session.value) return;
  await patch({ pinned: !session.value.pinned });
}

async function toggleAllowMultipleClients(): Promise<void> {
  if (isArchived.value || !session.value) return;
  await patch({ allowMultipleClients: !session.value.allowMultipleClients });
}

async function onTags(value: string[]): Promise<void> {
  if (isArchived.value) return;
  await patch({ tags: value });
}

async function onArchive(): Promise<void> {
  if (!session.value) return;
  if (!window.confirm(t('chat.sessionHeader.archiveConfirm'))) return;
  saving.value = true;
  try {
    await archiveSession(props.sessionId);
    emit('archived');
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    saving.value = false;
  }
}

async function onReactivate(): Promise<void> {
  if (!session.value) return;
  if (!window.confirm(t('chat.sessionHeader.reactivateConfirm'))) return;
  saving.value = true;
  try {
    await reactivateSession(props.sessionId);
    emit('reactivated');
    await load(props.sessionId);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    saving.value = false;
  }
}

async function onDelete(): Promise<void> {
  if (!session.value) return;
  if (!window.confirm(t('chat.sessionHeader.deleteConfirm'))) return;
  saving.value = true;
  try {
    await deleteSession(props.sessionId);
    emit('deleted');
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div
    ref="rootEl"
    class="flex items-center gap-2 min-w-0 flex-1 pl-3"
    :class="colorAccentClass"
  >
    <VEmojiPicker
      :model-value="session?.icon ?? null"
      :disabled="isArchived || saving"
      @update:model-value="onIcon"
    />

    <div class="min-w-0 flex-1 flex items-center gap-2">
      <input
        v-if="editingTitle"
        v-model="titleDraft"
        type="text"
        class="input input-sm input-bordered flex-1 min-w-0"
        :placeholder="t('chat.sessionHeader.editTitlePlaceholder')"
        autofocus
        @keyup.enter="commitTitle"
        @keyup.escape="cancelTitle"
        @blur="commitTitle"
      />
      <button
        v-else
        type="button"
        class="font-medium truncate text-left flex-1 min-w-0 hover:opacity-80"
        :class="!session?.title ? 'opacity-60' : ''"
        :disabled="isArchived"
        @click="startTitleEdit"
      >
        {{ displayTitle }}
      </button>

      <span
        v-if="session?.titleAutoGenerated && session?.title"
        class="text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded bg-base-200 opacity-60"
        :title="t('chat.sessionHeader.autoTitle')"
      >
        AI
      </span>

      <span
        v-if="isArchived"
        class="text-xs uppercase tracking-wide px-1.5 py-0.5 rounded bg-warning/15 text-warning border border-warning/30"
      >
        {{ t('chat.sessionHeader.archived') }}
      </span>
    </div>

    <!-- ─── Wide layout: inline action buttons ─── -->
    <template v-if="!compact">
      <!-- Pin toggle -->
      <button
        v-if="!isArchived"
        type="button"
        class="btn btn-ghost btn-sm"
        :title="session?.pinned ? t('chat.sessionHeader.unpinTooltip') : t('chat.sessionHeader.pinTooltip')"
        :disabled="saving"
        @click="togglePin"
      >
        <span v-if="session?.pinned">📌</span>
        <span v-else class="opacity-40">📌</span>
      </button>

      <!-- Color picker (compact, opens on click) -->
      <div v-if="!isArchived" class="relative">
        <button
          type="button"
          class="btn btn-ghost btn-sm"
          :title="t('chat.sessionHeader.colorLabel')"
          @click="showColor = !showColor"
        >
          🎨
        </button>
        <div
          v-if="showColor"
          class="absolute right-0 top-full mt-1 z-30 p-3 rounded-md border border-base-300 bg-base-100 shadow-lg"
        >
          <VColorPicker
            :model-value="session?.color"
            @update:model-value="(v) => { onColor(v); showColor = false; }"
          />
        </div>
      </div>

      <!-- Tag editor (compact, opens on click) -->
      <div v-if="!isArchived" class="relative">
        <button
          type="button"
          class="btn btn-ghost btn-sm"
          :title="t('chat.sessionHeader.tagsTooltip')"
          :class="(session?.tags?.length ?? 0) > 0 ? '' : 'opacity-60'"
          @click="showTags = !showTags"
        >
          🏷️
          <span
            v-if="(session?.tags?.length ?? 0) > 0"
            class="text-[10px] ml-1 opacity-70"
          >{{ session?.tags?.length }}</span>
        </button>
        <div
          v-if="showTags"
          class="absolute right-0 top-full mt-1 z-30 w-72 p-3 rounded-md border border-base-300 bg-base-100 shadow-lg"
        >
          <VTagEditor
            :model-value="session?.tags ?? []"
            :label="t('chat.sessionHeader.tagsLabel')"
            :placeholder="t('chat.sessionHeader.tagsPlaceholder')"
            @update:model-value="onTags"
          />
        </div>
      </div>

      <!-- Archive / Reactivate / Delete -->
      <VButton
        v-if="!isArchived"
        variant="ghost"
        size="sm"
        :disabled="saving"
        :title="t('chat.sessionHeader.archiveTooltip')"
        @click="onArchive"
      >
        📦
      </VButton>
      <VButton
        v-else
        variant="primary"
        size="sm"
        :disabled="saving"
        @click="onReactivate"
      >
        {{ t('chat.sessionHeader.reactivate') }}
      </VButton>

      <VButton
        variant="ghost"
        size="sm"
        :disabled="saving"
        :title="t('chat.sessionHeader.delete')"
        @click="onDelete"
      >
        🗑️
      </VButton>
    </template>

    <!-- ─── Compact layout: overflow menu ─── -->
    <div v-else ref="menuEl" class="relative">
      <button
        type="button"
        class="btn btn-ghost btn-sm"
        :title="t('chat.sessionHeader.moreActions')"
        :aria-expanded="menuOpen"
        :disabled="saving"
        @click="menuOpen = !menuOpen"
      >
        <span class="text-lg leading-none">⋯</span>
        <span
          v-if="session?.pinned || (session?.tags?.length ?? 0) > 0"
          class="text-[10px] ml-1 opacity-70"
        >
          <span v-if="session?.pinned">📌</span>
          <span v-if="(session?.tags?.length ?? 0) > 0">{{ session?.tags?.length }}</span>
        </span>
      </button>

      <div
        v-if="menuOpen"
        class="absolute right-0 top-full mt-1 z-30 w-64 rounded-md border border-base-300 bg-base-100 shadow-lg overflow-hidden"
        role="menu"
      >
        <!-- Pin toggle -->
        <button
          v-if="!isArchived"
          type="button"
          class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50"
          :disabled="saving"
          role="menuitem"
          @click="togglePin(); closeMenu()"
        >
          <span class="w-5 text-center" :class="session?.pinned ? '' : 'opacity-40'">📌</span>
          <span class="flex-1 text-left">
            {{ session?.pinned ? t('chat.sessionHeader.unpinTooltip') : t('chat.sessionHeader.pinTooltip') }}
          </span>
        </button>

        <!-- Multi-user toggle — see planning/multi-user-sessions.md §2.1 -->
        <button
          v-if="!isArchived"
          type="button"
          class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50"
          :disabled="saving"
          role="menuitem"
          @click="toggleAllowMultipleClients(); closeMenu()"
        >
          <span
            class="w-5 text-center"
            :class="session?.allowMultipleClients ? '' : 'opacity-40'"
          >👥</span>
          <span class="flex-1 text-left">
            {{ session?.allowMultipleClients
              ? t('chat.sessionHeader.collabDisableLabel')
              : t('chat.sessionHeader.collabEnableLabel') }}
          </span>
        </button>

        <!-- Color (expands inline picker) -->
        <template v-if="!isArchived">
          <button
            type="button"
            class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200"
            role="menuitem"
            :aria-expanded="menuExpand === 'color'"
            @click="menuExpand = menuExpand === 'color' ? null : 'color'"
          >
            <span class="w-5 text-center">🎨</span>
            <span class="flex-1 text-left">{{ t('chat.sessionHeader.colorLabel') }}</span>
            <span class="opacity-60 text-xs">{{ menuExpand === 'color' ? '▾' : '▸' }}</span>
          </button>
          <div
            v-if="menuExpand === 'color'"
            class="px-3 py-2 border-t border-b border-base-300 bg-base-200/30"
          >
            <VColorPicker
              :model-value="session?.color"
              @update:model-value="(v) => { onColor(v); closeMenu(); }"
            />
          </div>

          <!-- Labels (expands inline editor) -->
          <button
            type="button"
            class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200"
            role="menuitem"
            :aria-expanded="menuExpand === 'tags'"
            @click="menuExpand = menuExpand === 'tags' ? null : 'tags'"
          >
            <span class="w-5 text-center">🏷️</span>
            <span class="flex-1 text-left">{{ t('chat.sessionHeader.tagsLabel') }}</span>
            <span
              v-if="(session?.tags?.length ?? 0) > 0"
              class="text-[10px] opacity-70"
            >{{ session?.tags?.length }}</span>
            <span class="opacity-60 text-xs">{{ menuExpand === 'tags' ? '▾' : '▸' }}</span>
          </button>
          <div
            v-if="menuExpand === 'tags'"
            class="px-3 py-2 border-t border-b border-base-300 bg-base-200/30"
          >
            <VTagEditor
              :model-value="session?.tags ?? []"
              :label="t('chat.sessionHeader.tagsLabel')"
              :placeholder="t('chat.sessionHeader.tagsPlaceholder')"
              @update:model-value="onTags"
            />
          </div>
        </template>

        <div class="border-t border-base-300"></div>

        <!-- Archive / Reactivate -->
        <button
          v-if="!isArchived"
          type="button"
          class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50"
          :disabled="saving"
          role="menuitem"
          @click="closeMenu(); onArchive()"
        >
          <span class="w-5 text-center">📦</span>
          <span class="flex-1 text-left">{{ t('chat.sessionHeader.archive') }}</span>
        </button>
        <button
          v-else
          type="button"
          class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50"
          :disabled="saving"
          role="menuitem"
          @click="closeMenu(); onReactivate()"
        >
          <span class="w-5 text-center">♻️</span>
          <span class="flex-1 text-left">{{ t('chat.sessionHeader.reactivate') }}</span>
        </button>

        <!-- Delete -->
        <button
          type="button"
          class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50 text-error"
          :disabled="saving"
          role="menuitem"
          @click="closeMenu(); onDelete()"
        >
          <span class="w-5 text-center">🗑️</span>
          <span class="flex-1 text-left">{{ t('chat.sessionHeader.delete') }}</span>
        </button>
      </div>
    </div>
  </div>
</template>
