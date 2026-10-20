<script setup lang="ts">
import { computed, ref } from 'vue';
import { VButton, VInput, VModal, VSelect, VTagEditor, VTextarea } from '@vance/components';
import type { LinkEntryView } from './generated/links/LinkEntryView';
import type { LinkFields } from './api';
import { useT } from './i18n';

/**
 * Editing one link.
 *
 * The dialog's job is to keep the "stored vs. live" distinction visible.
 * Teaser and picture are placeholders showing what the page says today —
 * type over one and it becomes yours, empty it again and the page speaks
 * again. Without that the reader could not tell whether a teaser is theirs
 * to keep or a snapshot that will drift.
 *
 * The opening mode follows the same shape at its own scale: "inherit the
 * list default" is the unset state, and choosing one makes this link an
 * exception on purpose.
 *
 * Only changed fields are emitted, so the untouched ones keep the
 * server-side null that means "unchanged" — see UpdateLinkRequest.
 */
const props = defineProps<{
  entry: LinkEntryView;
  /** Existing group headings, offered as a datalist. */
  groups: string[];
  /** What the page says right now, for the placeholders. */
  pageTeaser: string | null;
  pageImage: string | null;
  /** The list default, so "inherit" can say what it inherits. */
  listOpen: string;
  busy?: boolean;
}>();

const emit = defineEmits<{
  (e: 'save', fields: LinkFields): void;
  (e: 'cancel'): void;
}>();

const t = useT();

const open = ref(true);

const title = ref(props.entry.title ?? '');
const teaser = ref(props.entry.teaser ?? '');
const image = ref(props.entry.image ?? '');
const group = ref(props.entry.group ?? '');
const note = ref(props.entry.note ?? '');
const tags = ref<string[]>([...(props.entry.tags ?? [])]);

// The select cannot carry '' as a value — VSelect maps an empty option to
// null — so inheritance goes through its own sentinel instead. The blank
// string is what the wire means by "back to the list default".
const OPEN_INHERIT = '__inherit__';
const openChoice = ref<string | null>(props.entry.open ?? OPEN_INHERIT);
const openNow = (): string => openChoice.value ?? OPEN_INHERIT;

const openOptions = computed(() => [
  { value: OPEN_INHERIT, label: t('links.edit.openInherit', { mode: t(`links.settings.open${cap(props.listOpen)}`) }) },
  { value: 'tab', label: t('links.settings.openTab') },
  { value: 'same', label: t('links.settings.openSame') },
  { value: 'frame', label: t('links.settings.openFrame') },
]);

/** 'tab' → 'Tab' — the option keys differ from the wire values by a capital. */
function cap(value: string): string {
  return value ? value[0].toUpperCase() + value.slice(1) : value;
}

function save(): void {
  const fields: LinkFields = {};
  if (title.value !== (props.entry.title ?? '')) fields.title = title.value;
  if (teaser.value !== (props.entry.teaser ?? '')) fields.teaser = teaser.value;
  if (image.value !== (props.entry.image ?? '')) fields.image = image.value;
  if (group.value !== (props.entry.group ?? '')) fields.group = group.value;
  if (note.value !== (props.entry.note ?? '')) fields.note = note.value;
  if (!sameTags(tags.value, props.entry.tags ?? [])) fields.tags = tags.value;
  if (openNow() !== (props.entry.open ?? OPEN_INHERIT)) {
    fields.open = openNow() === OPEN_INHERIT ? '' : openNow();
  }
  emit('save', fields);
}

function sameTags(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((t, i) => t === b[i]);
}

function close(): void {
  open.value = false;
  emit('cancel');
}
</script>

<template>
  <VModal
    v-model="open"
    :title="t('links.edit.title')"
    :close-on-backdrop="false"
    @update:model-value="(v: boolean) => { if (!v) emit('cancel'); }"
  >
    <div class="flex flex-col gap-4">
      <p class="truncate font-mono text-xs text-(--v-label-secondary)" :title="entry.url">{{ entry.url }}</p>

      <VInput
        v-model="title"
        :label="t('links.edit.fieldTitle')"
        :placeholder="t('links.edit.titlePlaceholder')"
        :help="t('links.edit.titleHelp')"
      />

      <VTextarea
        v-model="teaser"
        :label="t('links.edit.teaser')"
        :rows="3"
        :mono="false"
        :placeholder="pageTeaser ?? t('links.edit.teaserPlaceholder')"
        :help="pageTeaser
          ? t('links.edit.teaserHelpLive')
          : t('links.edit.teaserHelpNone')"
      />

      <VInput
        v-model="group"
        :label="t('links.edit.group')"
        :placeholder="t('links.edit.groupPlaceholder')"
        :suggestions="groups"
        :help="t('links.edit.groupHelp')"
      />

      <VTagEditor
        v-model="tags"
        :label="t('links.edit.tags')"
        :placeholder="t('links.edit.tagsPlaceholder')"
      />

      <div class="w-64">
        <VSelect
          v-model="openChoice"
          :options="openOptions"
          :label="t('links.edit.openLabel')"
          :help="t('links.edit.openHelp')"
        />
      </div>

      <VTextarea
        v-model="note"
        :label="t('links.edit.note')"
        :rows="2"
        :mono="false"
        :placeholder="t('links.edit.notePlaceholder')"
        :help="t('links.edit.noteHelp')"
      />

      <VInput
        v-model="image"
        :label="t('links.edit.image')"
        :placeholder="pageImage ?? t('links.edit.imagePlaceholder')"
        :help="t('links.edit.imageHelp')"
      />
    </div>

    <template #actions>
      <VButton variant="secondary" :disabled="busy" @click="close">{{ t('links.common.cancel') }}</VButton>
      <VButton variant="primary" :disabled="busy" @click="save">{{ t('links.common.save') }}</VButton>
    </template>
  </VModal>
</template>
