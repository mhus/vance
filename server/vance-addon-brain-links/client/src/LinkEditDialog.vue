<script setup lang="ts">
import { ref } from 'vue';
import { VButton, VInput, VModal, VTagEditor, VTextarea } from '@vance/components';
import type { LinkEntryView } from './generated/links/LinkEntryView';
import type { LinkFields } from './api';

/**
 * Editing one link.
 *
 * The dialog's job is to keep the "stored vs. live" distinction visible.
 * Teaser and picture are placeholders showing what the page says today —
 * type over one and it becomes yours, empty it again and the page speaks
 * again. Without that the reader could not tell whether a teaser is theirs
 * to keep or a snapshot that will drift.
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
  busy?: boolean;
}>();

const emit = defineEmits<{
  (e: 'save', fields: LinkFields): void;
  (e: 'cancel'): void;
}>();

const open = ref(true);

const title = ref(props.entry.title ?? '');
const teaser = ref(props.entry.teaser ?? '');
const image = ref(props.entry.image ?? '');
const group = ref(props.entry.group ?? '');
const note = ref(props.entry.note ?? '');
const tags = ref<string[]>([...(props.entry.tags ?? [])]);

function save(): void {
  const fields: LinkFields = {};
  if (title.value !== (props.entry.title ?? '')) fields.title = title.value;
  if (teaser.value !== (props.entry.teaser ?? '')) fields.teaser = teaser.value;
  if (image.value !== (props.entry.image ?? '')) fields.image = image.value;
  if (group.value !== (props.entry.group ?? '')) fields.group = group.value;
  if (note.value !== (props.entry.note ?? '')) fields.note = note.value;
  if (!sameTags(tags.value, props.entry.tags ?? [])) fields.tags = tags.value;
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
    title="Edit link"
    :close-on-backdrop="false"
    @update:model-value="(v: boolean) => { if (!v) emit('cancel'); }"
  >
    <div class="flex flex-col gap-3">
      <p class="truncate font-mono text-xs opacity-60" :title="entry.url">{{ entry.url }}</p>

      <VInput
        v-model="title"
        label="Title"
        placeholder="the page's own title"
        help="Empty re-fetches the title from the page."
      />

      <VTextarea
        v-model="teaser"
        label="Teaser"
        :rows="3"
        :mono="false"
        :placeholder="pageTeaser ?? 'the page has no description'"
        :help="pageTeaser
          ? 'Empty shows the page\'s own description (the grey text above) and keeps it current.'
          : 'The page offers no description — write one to give the card a subtitle.'"
      />

      <VInput
        v-model="group"
        label="Group"
        placeholder="(no group)"
        :suggestions="groups"
        help="A name that does not exist yet becomes a new group."
      />

      <VTagEditor v-model="tags" label="Tags" placeholder="add a tag…" />

      <VTextarea
        v-model="note"
        label="Note"
        :rows="2"
        :mono="false"
        placeholder="why this list has the link"
        help="Your remark. The teaser describes the page; this describes why you kept it."
      />

      <VInput
        v-model="image"
        label="Picture URL"
        :placeholder="pageImage ?? 'the page offers no picture'"
        help="Empty uses the page's own preview picture."
      />
    </div>

    <template #actions>
      <VButton variant="ghost" :disabled="busy" @click="close">Cancel</VButton>
      <VButton variant="primary" :disabled="busy" @click="save">Save</VButton>
    </template>
  </VModal>
</template>
