<script setup lang="ts">
import { VButton, VColorPicker, VEmojiPicker, VTagEditor, VIcon, VIconButton } from '@vance/components';
import {
  AccentColor,
  type SessionSummaryRichDto,
} from '@vance/generated';
import { listSessions } from '@vance/shared';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useSessionActions } from '@composables/useSessionActions';

interface Props {
  sessionId: string;
  /** Show a "Save as document" affordance in the action group. Wired by
   *  the chat host (ChatView) — the export logic lives there. */
  canSave?: boolean;
  /** True while an export is in flight; renders a spinner glyph and
   *  disables the button. */
  exporting?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  canSave: false,
  exporting: false,
});

const emit = defineEmits<{
  (e: 'archived'): void;
  (e: 'reactivated'): void;
  (e: 'deleted'): void;
  (e: 'save'): void;
}>();

const { t } = useI18n();

const session = ref<SessionSummaryRichDto | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);
const editingTitle = ref(false);
const titleDraft = ref('');
const showColor = ref(false);
const showTags = ref(false);

/**
 * Shared session actions. Owner-gating (shared sessions are read-only for
 * non-owners — see planning/multi-user-sessions.md §2.1 / §2.4) and the
 * archived-state guards live in the composable; this header wires the
 * results back into its locally-loaded {@code session} ref.
 */
const {
  saving,
  isOwner,
  isArchived,
  setIcon,
  setColor,
  setTags,
  setTitle,
  togglePin,
  toggleAllowMultipleClients,
  archive: archiveAction,
  reactivate: reactivateAction,
  remove: removeAction,
} = useSessionActions(session, {
  onPatched: (updated) => { session.value = updated; },
  onArchived: () => emit('archived'),
  onReactivated: () => { emit('reactivated'); void load(props.sessionId); },
  onDeleted: () => emit('deleted'),
});

// Responsive collapse: when the header has less than this many pixels of
// horizontal room, the action buttons (save/pin/multi-user/color/labels/
// archive/delete) fold into a single "⋯" overflow menu.
const COMPACT_THRESHOLD_PX = 580;
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
  // User-chosen session colour → the content palette (corporate-design.md
  // §3.4); the twelve stored accents fold onto the eight content tones.
  switch (session.value?.color) {
    case AccentColor.SLATE: return 'border-l-4 border-(--v-content-grey-fg)';
    case AccentColor.RED: return 'border-l-4 border-(--v-content-pink-fg)';
    case AccentColor.ORANGE: return 'border-l-4 border-(--v-content-orange-fg)';
    case AccentColor.AMBER: return 'border-l-4 border-(--v-content-yellow-fg)';
    case AccentColor.GREEN: return 'border-l-4 border-(--v-content-green-fg)';
    case AccentColor.TEAL: return 'border-l-4 border-(--v-content-teal-fg)';
    case AccentColor.CYAN: return 'border-l-4 border-(--v-content-teal-fg)';
    case AccentColor.BLUE: return 'border-l-4 border-(--v-content-purple-fg)';
    case AccentColor.INDIGO: return 'border-l-4 border-(--v-content-purple-fg)';
    case AccentColor.PURPLE: return 'border-l-4 border-(--v-content-purple-fg)';
    case AccentColor.PINK: return 'border-l-4 border-(--v-content-pink-fg)';
    case AccentColor.ROSE: return 'border-l-4 border-(--v-content-pink-fg)';
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

function startTitleEdit(): void {
  if (isArchived.value) return;
  if (!isOwner.value) return; // owner-only edit — see composable isOwner doc
  titleDraft.value = session.value?.title ?? '';
  editingTitle.value = true;
}

async function commitTitle(): Promise<void> {
  editingTitle.value = false;
  const next = titleDraft.value.trim();
  if (next === (session.value?.title ?? '')) return;
  await setTitle(next);
}

function cancelTitle(): void {
  editingTitle.value = false;
}

async function onIcon(value: string | null): Promise<void> {
  await setIcon(value);
}

async function onColor(value: AccentColor | null): Promise<void> {
  await setColor(value);
}

async function onTags(value: string[]): Promise<void> {
  await setTags(value);
}

async function onArchive(): Promise<void> {
  if (!session.value) return;
  if (!window.confirm(t('chat.sessionHeader.archiveConfirm'))) return;
  await archiveAction();
}

async function onReactivate(): Promise<void> {
  if (!session.value) return;
  if (!window.confirm(t('chat.sessionHeader.reactivateConfirm'))) return;
  await reactivateAction();
}

async function onDelete(): Promise<void> {
  if (!session.value) return;
  if (!window.confirm(t('chat.sessionHeader.deleteConfirm'))) return;
  await removeAction();
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
        class="input input-sm flex-1 min-w-0"
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
        class="text-xs uppercase tracking-wide text-(--v-warning)"
      >
        {{ t('chat.sessionHeader.archived') }}
      </span>
    </div>

    <!-- ─── Wide layout: inline action buttons ─── -->
    <template v-if="!compact">
      <!-- Save as document — visible to any participant (including
           non-owners on shared sessions) since the export targets the
           chat project, not session metadata. -->
      <VIconButton
        v-if="canSave"
        :icon="exporting ? 'loading' : 'save'"
        size="sm"
        :disabled="exporting"
        :label="t('chat.export.saveAsDocumentTooltip')"
        @click="emit('save')"
      />
    </template>

    <!-- Owner-only: all metadata action buttons (pin/color/tags/archive/
         delete) hit owner-gated REST endpoints. Hide them for non-owners
         on shared sessions. -->
    <template v-if="!compact && isOwner">
      <!-- Pin toggle -->
      <VIconButton
        v-if="!isArchived"
        icon="pin"
        size="sm"
        :active="!!session?.pinned"
        :label="session?.pinned ? t('chat.sessionHeader.unpinTooltip') : t('chat.sessionHeader.pinTooltip')"
        :disabled="saving"
        @click="togglePin"
      />

      <!-- Multi-user toggle — see planning/multi-user-sessions.md §2.1 -->
      <VIconButton
        v-if="!isArchived"
        icon="team"
        size="sm"
        :active="!!session?.allowMultipleClients"
        :label="session?.allowMultipleClients
          ? t('chat.sessionHeader.collabDisableLabel')
          : t('chat.sessionHeader.collabEnableLabel')"
        :disabled="saving"
        @click="toggleAllowMultipleClients"
      />

      <!-- Color picker (compact, opens on click) -->
      <div v-if="!isArchived" class="relative">
        <VIconButton
          icon="theme"
          size="sm"
          :active="showColor"
          :label="t('chat.sessionHeader.colorLabel')"
          @click="showColor = !showColor"
        />
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
        <VIconButton
          icon="tag"
          size="sm"
          :active="showTags"
          :badge="session?.tags?.length ?? 0"
          :label="t('chat.sessionHeader.tagsTooltip')"
          @click="showTags = !showTags"
        />
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
      <VIconButton icon="archive" :label="t('chat.sessionHeader.archiveTooltip')" size="sm"
        v-if="!isArchived"
        :disabled="saving"
        @click="onArchive"
       />
      <VButton
        v-else
        variant="primary"
        size="sm"
        :disabled="saving"
        @click="onReactivate"
      >
        {{ t('chat.sessionHeader.reactivate') }}
      </VButton>

      <VIconButton icon="delete" :label="t('chat.sessionHeader.delete')" size="sm"
        :disabled="saving"
        @click="onDelete"
       />
    </template>

    <!-- ─── Compact layout: overflow menu ─── -->
    <!-- The trigger renders for owners (metadata actions are owner-gated)
         OR for any participant who has something exportable (save item).
         Owner-only items inside the menu stay gated on isOwner — see
         planning/multi-user-sessions.md §2.4. -->
    <div v-if="compact && (canSave || isOwner)" ref="menuEl" class="relative">
      <div class="flex items-center gap-1">
        <VIcon
          v-if="session?.pinned"
          name="pin"
          class="text-(--v-label-secondary)"
        />
        <VIconButton
          icon="more"
          size="sm"
          :active="menuOpen"
          :badge="session?.tags?.length ?? 0"
          :label="t('chat.sessionHeader.moreActions')"
          :aria-expanded="menuOpen"
          :disabled="saving"
          @click="menuOpen = !menuOpen"
        />
      </div>

      <div
        v-if="menuOpen"
        class="absolute right-0 top-full mt-1 z-30 w-64 rounded-md border border-base-300 bg-base-100 shadow-lg overflow-hidden"
        role="menu"
      >
        <!-- Save as document — top of menu, visible to any participant -->
        <button
          v-if="canSave"
          type="button"
          class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50"
          :disabled="exporting"
          :title="t('chat.export.saveAsDocumentTooltip')"
          role="menuitem"
          @click="closeMenu(); emit('save')"
        >
          <span class="w-5 flex justify-center"><VIcon :name="exporting ? 'loading' : 'save'" /></span>
          <span class="flex-1 text-left">{{ t('chat.export.saveAsDocumentLabel') }}</span>
        </button>
        <div v-if="canSave && isOwner" class="border-t border-base-300"></div>

        <!-- Owner-gated section: every item below hits owner-only REST
             endpoints, so wrap the whole block in a single isOwner gate. -->
        <template v-if="isOwner">
        <!-- Pin toggle -->
        <button
          v-if="!isArchived"
          type="button"
          class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50"
          :disabled="saving"
          role="menuitem"
          @click="togglePin(); closeMenu()"
        >
          <span class="w-5 flex justify-center" :class="session?.pinned ? '' : 'opacity-40'"><VIcon name="pin" /></span>
          <span class="flex-1 text-left">
            {{ session?.pinned ? t('chat.sessionHeader.unpinTooltip') : t('chat.sessionHeader.pinTooltip') }}
          </span>
        </button>

        <!-- Multi-user toggle — see planning/multi-user-sessions.md §2.1.
             Owner-only: backend SessionMetadataPatchHandler rejects
             non-owners with 403, so we hide the entry too. -->
        <button
          v-if="!isArchived && isOwner"
          type="button"
          class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50"
          :disabled="saving"
          role="menuitem"
          @click="toggleAllowMultipleClients(); closeMenu()"
        >
          <span
            class="w-5 flex justify-center"
            :class="session?.allowMultipleClients ? '' : 'opacity-40'"
          ><VIcon name="team" /></span>
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
            <span class="w-5 flex justify-center"><VIcon name="theme" /></span>
            <span class="flex-1 text-left">{{ t('chat.sessionHeader.colorLabel') }}</span>
            <VIcon :name="menuExpand === 'color' ? 'expand' : 'forward'" class="opacity-60" />
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
            <span class="w-5 flex justify-center"><VIcon name="tag" /></span>
            <span class="flex-1 text-left">{{ t('chat.sessionHeader.tagsLabel') }}</span>
            <span
              v-if="(session?.tags?.length ?? 0) > 0"
              class="text-[10px] opacity-70"
            >{{ session?.tags?.length }}</span>
            <VIcon :name="menuExpand === 'tags' ? 'expand' : 'forward'" class="opacity-60" />
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
          <span class="w-5 flex justify-center"><VIcon name="archive" /></span>
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
          <span class="w-5 flex justify-center"><VIcon name="restore" /></span>
          <span class="flex-1 text-left">{{ t('chat.sessionHeader.reactivate') }}</span>
        </button>

        <!-- Delete -->
        <button
          type="button"
          class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50 text-(--v-danger)"
          :disabled="saving"
          role="menuitem"
          @click="closeMenu(); onDelete()"
        >
          <span class="w-5 flex justify-center"><VIcon name="delete" /></span>
          <span class="flex-1 text-left">{{ t('chat.sessionHeader.delete') }}</span>
        </button>
        </template>
      </div>
    </div>
  </div>
</template>
