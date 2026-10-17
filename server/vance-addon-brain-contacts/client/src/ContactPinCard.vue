<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { VButton, VIcon, VIconButton, VInput, VTextarea } from '@vance/components';
import ContactAvatar from './ContactAvatar.vue';
import { useT } from './i18n';
import type { ContactPinView } from './generated/contacts/ContactPinView';

/**
 * One pinned contact on a board: the heading above, the card (always the
 * address book's current data), the note below — collapsed to one line
 * until opened, so the list stays short enough to drag.
 */
const props = defineProps<{
  pin: ContactPinView;
  projectId: string;
  editable: boolean;
}>();

const emit = defineEmits<{
  (e: 'update', change: { label?: string; note?: string }): void;
  (e: 'unpin'): void;
  (e: 'details'): void;
}>();

const t = useT();

const editingLabel = ref(false);
const labelDraft = ref('');
const noteOpen = ref(false);
const editingNote = ref(false);
const noteDraft = ref('');
const noteHtml = ref('');

const name = computed(() => props.pin.contact?.displayName ?? '');
const subtitle = computed(() => [props.pin.contact?.title, props.pin.contact?.org].filter(Boolean).join(' · '));

/** First line of the note without Markdown punctuation — the collapsed preview. */
const notePreview = computed(() => {
  const first = (props.pin.note ?? '').split('\n').find((l) => l.trim()) ?? '';
  return first.replace(/[#>*_`~[\]]/g, '').trim();
});

watch(
  () => [props.pin.note, noteOpen.value] as const,
  async ([note, open]) => {
    noteHtml.value = open && note ? DOMPurify.sanitize(await marked.parse(note)) : '';
  },
  { immediate: true },
);

function startLabel(): void {
  if (!props.editable) return;
  labelDraft.value = props.pin.label ?? '';
  editingLabel.value = true;
}

function commitLabel(): void {
  if (!editingLabel.value) return;
  editingLabel.value = false;
  if ((props.pin.label ?? '') !== labelDraft.value.trim()) emit('update', { label: labelDraft.value.trim() });
}

function startNote(): void {
  noteDraft.value = props.pin.note ?? '';
  editingNote.value = true;
  noteOpen.value = true;
}

function commitNote(): void {
  editingNote.value = false;
  if ((props.pin.note ?? '') !== noteDraft.value.trim()) emit('update', { note: noteDraft.value.trim() });
}

function telHref(value: string): string {
  return `tel:${value.replace(/[^+\d]/g, '')}`;
}
</script>

<template>
  <div class="flex flex-col gap-2 rounded-(--v-radius-group) bg-(--v-surface) p-3">
    <div class="flex items-center gap-2">
      <span v-if="editable" class="pin-handle cursor-grab text-(--v-label-tertiary)" :title="t('contacts.board.drag')">
        <VIcon name="drag" />
      </span>
      <div class="flex-1 min-w-0">
        <div v-if="editingLabel" class="w-full">
          <VInput
            v-model="labelDraft"
            size="sm"
            :placeholder="t('contacts.board.labelPlaceholder')"
            @keydown.enter.prevent="commitLabel"
            @keydown.esc="editingLabel = false"
            @blur="commitLabel"
          />
        </div>
        <button
          v-else
          type="button"
          class="text-left w-full text-xs font-semibold uppercase tracking-wide truncate"
          :class="pin.label ? 'text-(--v-label-secondary)' : 'text-(--v-label-tertiary)'"
          :disabled="!editable"
          @click="startLabel"
        >
          {{ pin.label || (editable ? t('contacts.board.labelPlaceholder') : '') }}
        </button>
      </div>
      <VIconButton v-if="pin.available" icon="external" size="sm" :label="t('contacts.board.details')" @click="emit('details')" />
      <VIconButton v-if="editable" icon="close" size="sm" :label="t('contacts.board.unpin')" @click="emit('unpin')" />
    </div>

    <div v-if="pin.available && pin.contact" class="flex items-center gap-3">
      <ContactAvatar
        :name="name"
        :project-id="projectId"
        :id="pin.contact.id"
        :scope="pin.contact.scope"
        :has-photo="pin.contact.hasPhoto"
        :photo-version="pin.contact.photoVersion"
        size="md"
      />
      <div class="min-w-0">
        <div class="font-semibold truncate">{{ name }}</div>
        <div v-if="subtitle" class="text-sm text-(--v-label-secondary) truncate">{{ subtitle }}</div>
        <div class="flex flex-wrap gap-x-4 text-sm">
          <a v-if="pin.contact.email" :href="`mailto:${pin.contact.email}`" class="text-(--v-accent-text) hover:underline">{{ pin.contact.email }}</a>
          <a v-if="pin.contact.phone" :href="telHref(pin.contact.phone)" class="text-(--v-accent-text) hover:underline">{{ pin.contact.phone }}</a>
        </div>
      </div>
    </div>
    <p v-else class="text-sm text-(--v-label-secondary)">{{ t('contacts.board.unavailable') }}</p>

    <div v-if="editingNote" class="flex flex-col gap-2">
      <VTextarea v-model="noteDraft" :rows="4" :mono="false" auto-grow :label="t('contacts.board.note')" />
      <div class="flex justify-end gap-2">
        <VButton variant="secondary" size="sm" @click="editingNote = false">{{ t('contacts.common.cancel') }}</VButton>
        <VButton variant="primary" size="sm" @click="commitNote">{{ t('contacts.common.save') }}</VButton>
      </div>
    </div>
    <template v-else-if="pin.note">
      <div class="flex items-start gap-1">
        <VIconButton
          :icon="noteOpen ? 'collapse' : 'expand'"
          size="sm"
          :label="noteOpen ? t('contacts.board.hideNote') : t('contacts.board.showNote')"
          @click="noteOpen = !noteOpen"
        />
        <!-- eslint-disable-next-line vue/no-v-html -- markdown, sanitised via DOMPurify -->
        <div v-if="noteOpen" class="contact-note flex-1 min-w-0 text-sm" v-html="noteHtml"></div>
        <button v-else type="button" class="flex-1 min-w-0 text-left text-sm text-(--v-label-secondary) truncate" @click="noteOpen = true">
          {{ notePreview }}
        </button>
        <VIconButton v-if="editable && noteOpen" icon="edit" size="sm" :label="t('contacts.board.editNote')" @click="startNote" />
      </div>
    </template>
    <button
      v-else-if="editable"
      type="button"
      class="text-left text-sm text-(--v-label-tertiary)"
      @click="startNote"
    >
      {{ t('contacts.board.noNote') }}
    </button>
  </div>
</template>

<style scoped>
.contact-note :deep(p) { margin: 0 0 0.5em; }
.contact-note :deep(ul) { list-style: disc; padding-left: 1.25em; margin: 0 0 0.5em; }
.contact-note :deep(ol) { list-style: decimal; padding-left: 1.25em; margin: 0 0 0.5em; }
.contact-note :deep(a) { color: var(--v-accent-text); text-decoration: underline; }
.contact-note :deep(code) { font-family: var(--v-font-mono); }
</style>
