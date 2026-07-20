<script setup lang="ts">
import { VColorPicker } from '@vance/components';
import {
  AccentColor,
  type SessionSummaryRichDto,
} from '@vance/generated';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useSessionActions } from '@composables/useSessionActions';

/**
 * Per-session-card action menu for the chat session list. Exposes the
 * subset of {@link useSessionActions} that makes sense outside an open
 * chat (pin, multi-user, colour, archive/reactivate, delete) so the user
 * can manage a session without entering it first. Save-as-document and
 * the tag editor deliberately stay chat-only.
 *
 * List-only actions that have no place in the in-chat header will be
 * added here directly — this is the home for session-list affordances.
 */
const props = defineProps<{
  session: SessionSummaryRichDto;
}>();

const emit = defineEmits<{
  /** A metadata patch succeeded; payload is the merged summary. Parent
   *  re-sorts / replaces the list entry (pin changes ordering). */
  (e: 'changed', updated: SessionSummaryRichDto): void;
  (e: 'archived'): void;
  (e: 'reactivated'): void;
  (e: 'deleted'): void;
  /** A duplicate was created; payload is the new session's business id. */
  (e: 'duplicated', newSessionId: string): void;
}>();

const { t } = useI18n();

// Local mirror of the card's session so the composable can read/update it
// without mutating the prop. Kept in sync when the parent swaps the entry.
const sessionRef = ref<SessionSummaryRichDto | null>(props.session);
watch(() => props.session, (s) => { sessionRef.value = s; });

const {
  saving,
  isOwner,
  isArchived,
  setColor,
  togglePin,
  toggleAllowMultipleClients,
  archive: archiveAction,
  reactivate: reactivateAction,
  remove: removeAction,
  duplicate: duplicateAction,
} = useSessionActions(sessionRef, {
  onPatched: (updated) => {
    sessionRef.value = updated;
    emit('changed', updated);
  },
  onArchived: () => emit('archived'),
  onReactivated: () => emit('reactivated'),
  onDeleted: () => emit('deleted'),
  onDuplicated: (newSessionId) => emit('duplicated', newSessionId),
});

const menuOpen = ref(false);
const colorExpanded = ref(false);
const triggerEl = ref<HTMLElement | null>(null);
const menuEl = ref<HTMLElement | null>(null);

// The dropdown is teleported to <body>: the session card is a DaisyUI
// `.card` (overflow:hidden) sitting in a scroll container, so an inline
// absolute panel would be clipped. Fixed positioning is computed from the
// trigger's viewport rect and right-aligned to it.
const MENU_WIDTH_PX = 224; // matches w-56
const menuStyle = ref<Record<string, string>>({});

function positionMenu(): void {
  const el = triggerEl.value;
  if (!el) return;
  const rect = el.getBoundingClientRect();
  menuStyle.value = {
    position: 'fixed',
    top: `${rect.bottom + 4}px`,
    left: `${Math.max(8, rect.right - MENU_WIDTH_PX)}px`,
    width: `${MENU_WIDTH_PX}px`,
  };
}

function closeMenu(): void {
  menuOpen.value = false;
  colorExpanded.value = false;
}

function toggleMenu(): void {
  if (menuOpen.value) {
    closeMenu();
  } else {
    positionMenu();
    menuOpen.value = true;
  }
}

function onDocMouseDown(e: MouseEvent): void {
  if (!menuOpen.value) return;
  const target = e.target as Node | null;
  if (target && triggerEl.value && triggerEl.value.contains(target)) return;
  if (target && menuEl.value && menuEl.value.contains(target)) return;
  closeMenu();
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape' && menuOpen.value) closeMenu();
}

// A scroll or resize would leave the fixed panel detached from its card;
// simplest correct behaviour is to close it.
function onViewportChange(): void {
  if (menuOpen.value) closeMenu();
}

onMounted(() => {
  document.addEventListener('mousedown', onDocMouseDown);
  document.addEventListener('keydown', onKeydown);
  window.addEventListener('scroll', onViewportChange, true);
  window.addEventListener('resize', onViewportChange);
});

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocMouseDown);
  document.removeEventListener('keydown', onKeydown);
  window.removeEventListener('scroll', onViewportChange, true);
  window.removeEventListener('resize', onViewportChange);
});

async function onColor(value: AccentColor | null): Promise<void> {
  await setColor(value);
  closeMenu();
}

async function onDuplicate(): Promise<void> {
  closeMenu();
  const s = props.session;
  // Mirror the list's display-title fallback (title → firstUserMessage →
  // fallback) so the copy is named after what the user actually sees, not
  // after an empty explicit title.
  let base: string;
  if (s.title && s.title.trim().length > 0) {
    base = s.title.trim();
  } else if (s.firstUserMessage && s.firstUserMessage.trim().length > 0) {
    base = s.firstUserMessage.trim();
  } else {
    base = t('chat.sessionHeader.duplicateFallbackTitle');
  }
  // Keep the label sane if the source falls back to a long first message.
  if (base.length > 80) base = base.slice(0, 80).trimEnd() + '…';
  const title = t('chat.sessionHeader.duplicateTitlePrefix', { title: base });
  await duplicateAction(title);
}

async function onArchive(): Promise<void> {
  if (!window.confirm(t('chat.sessionHeader.archiveConfirm'))) return;
  closeMenu();
  await archiveAction();
}

async function onReactivate(): Promise<void> {
  if (!window.confirm(t('chat.sessionHeader.reactivateConfirm'))) return;
  closeMenu();
  await reactivateAction();
}

async function onDelete(): Promise<void> {
  if (!window.confirm(t('chat.sessionHeader.deleteConfirm'))) return;
  closeMenu();
  await removeAction();
}
</script>

<template>
  <!-- Owner-only: every entry hits an owner-gated REST endpoint (the
       backend rejects non-owners with 403). Non-owners on shared sessions
       see no menu — matching SessionHeader. -->
  <div v-if="isOwner" ref="triggerEl" @click.stop>
    <button
      type="button"
      class="btn btn-ghost btn-sm btn-square"
      :title="t('chat.sessionHeader.moreActions')"
      :aria-expanded="menuOpen"
      :disabled="saving"
      @click.stop="toggleMenu"
    >
      <span class="text-lg leading-none">⋯</span>
    </button>

    <Teleport to="body">
    <div
      v-if="menuOpen"
      ref="menuEl"
      :style="menuStyle"
      class="z-50 rounded-md border border-base-300 bg-base-100 shadow-lg overflow-hidden"
      role="menu"
      @click.stop
    >
      <!-- Duplicate — available in any state; the copy is created active -->
      <button
        type="button"
        class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50"
        :disabled="saving"
        role="menuitem"
        @click.stop="onDuplicate"
      >
        <span class="w-5 text-center">⧉</span>
        <span class="flex-1 text-left">{{ t('chat.sessionHeader.duplicate') }}</span>
      </button>

      <div class="border-t border-base-300"></div>

      <!-- Pin toggle -->
      <button
        v-if="!isArchived"
        type="button"
        class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50"
        :disabled="saving"
        role="menuitem"
        @click.stop="togglePin(); closeMenu()"
      >
        <span class="w-5 text-center" :class="session.pinned ? '' : 'opacity-40'">📌</span>
        <span class="flex-1 text-left">
          {{ session.pinned ? t('chat.sessionHeader.unpinTooltip') : t('chat.sessionHeader.pinTooltip') }}
        </span>
      </button>

      <!-- Multi-user toggle — see planning/multi-user-sessions.md §2.1 -->
      <button
        v-if="!isArchived"
        type="button"
        class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200 disabled:opacity-50"
        :disabled="saving"
        role="menuitem"
        @click.stop="toggleAllowMultipleClients(); closeMenu()"
      >
        <span
          class="w-5 text-center"
          :class="session.allowMultipleClients ? '' : 'opacity-40'"
        >👥</span>
        <span class="flex-1 text-left">
          {{ session.allowMultipleClients
            ? t('chat.sessionHeader.collabDisableLabel')
            : t('chat.sessionHeader.collabEnableLabel') }}
        </span>
      </button>

      <!-- Colour (expands inline picker) -->
      <template v-if="!isArchived">
        <button
          type="button"
          class="flex items-center gap-3 w-full px-3 py-2 text-sm hover:bg-base-200"
          role="menuitem"
          :aria-expanded="colorExpanded"
          @click.stop="colorExpanded = !colorExpanded"
        >
          <span class="w-5 text-center">🎨</span>
          <span class="flex-1 text-left">{{ t('chat.sessionHeader.colorLabel') }}</span>
          <span class="opacity-60 text-xs">{{ colorExpanded ? '▾' : '▸' }}</span>
        </button>
        <div
          v-if="colorExpanded"
          class="px-3 py-2 border-t border-b border-base-300 bg-base-200/30"
        >
          <VColorPicker
            :model-value="session.color"
            @update:model-value="onColor"
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
        @click.stop="onArchive"
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
        @click.stop="onReactivate"
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
        @click.stop="onDelete"
      >
        <span class="w-5 text-center">🗑️</span>
        <span class="flex-1 text-left">{{ t('chat.sessionHeader.delete') }}</span>
      </button>
    </div>
    </Teleport>
  </div>
</template>
