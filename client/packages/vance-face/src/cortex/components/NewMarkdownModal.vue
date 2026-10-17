<script setup lang="ts">
/**
 * Quick "new Markdown file" modal: asks only for folder and name, adds
 * `.md` when missing and creates an empty `text/markdown` document. A blank
 * name picks the next free `note<N>.md` — first from the paths the client
 * knows, then by stepping past each 409 from the server. The
 * parent passes the actual create call so a failure (e.g. 409 when the
 * file already exists) stays inline and the form is kept.
 */
import { computed, nextTick, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { RestError } from '@vance/shared';
import { VAlert, VButton, VInput, VModal } from '@/components';
import { nextNotePath, quickMarkdownPath } from '../quickMarkdownPath';

interface Props {
  open: boolean;
  initialPath?: string;
  create: (fullPath: string) => Promise<void>;
  /** Paths already known to exist — used to pick a free `note<N>.md`. */
  isTaken?: (path: string) => boolean;
}

/** How many server-side conflicts a blank name steps past before giving up. */
const MAX_NOTE_CONFLICTS = 20;

const { t } = useI18n();

const props = withDefaults(defineProps<Props>(), {
  initialPath: '',
  isTaken: () => false,
});

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
}>();

const path = ref('');
const name = ref('');
const error = ref<string | null>(null);
const creating = ref(false);

// showModal() focuses the header's close button; the name field is the
// one the user types into, so it is focused through VInput's exposed ref.
const nameInput = ref<InstanceType<typeof VInput> | null>(null);

const preview = computed(() =>
  name.value.trim()
    ? quickMarkdownPath(path.value, name.value)
    : nextNotePath(path.value, props.isTaken).path,
);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    path.value = props.initialPath ?? '';
    name.value = '';
    error.value = null;
    nextTick(() => nameInput.value?.input?.focus());
  },
  { immediate: true },
);

function close(): void {
  emit('update:open', false);
}

async function submit(): Promise<void> {
  creating.value = true;
  error.value = null;
  try {
    if (name.value.trim()) {
      await props.create(quickMarkdownPath(path.value, name.value) as string);
    } else {
      await createNextNote();
    }
    close();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('cortex.newMarkdown.failed');
  } finally {
    creating.value = false;
  }
}

async function createNextNote(): Promise<void> {
  let next = nextNotePath(path.value, props.isTaken);
  for (let attempt = 0; ; attempt++) {
    try {
      await props.create(next.path);
      return;
    } catch (e) {
      if (!(e instanceof RestError && e.status === 409) || attempt >= MAX_NOTE_CONFLICTS) throw e;
      next = nextNotePath(path.value, props.isTaken, next.index + 1);
    }
  }
}

</script>

<template>
  <VModal
    :model-value="open"
    :title="$t('cortex.newMarkdown.title')"
    size="sm"
    @update:model-value="(v: boolean) => emit('update:open', v)"
  >
    <form id="new-markdown-form" class="flex flex-col gap-4" @submit.prevent="submit">
      <VInput
        v-model="path"
        :label="$t('cortex.newMarkdown.pathLabel')"
        :placeholder="$t('cortex.newMarkdown.pathPlaceholder')"
        :disabled="creating"
      />
      <VInput
        ref="nameInput"
        v-model="name"
        :label="$t('cortex.newMarkdown.nameLabel')"
        :placeholder="$t('cortex.newMarkdown.namePlaceholder')"
        :help="$t('cortex.newMarkdown.nameHelp')"
        :disabled="creating"
      />
      <p v-if="preview" class="text-xs text-(--v-label-secondary) font-mono break-all">{{ preview }}</p>
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    </form>

    <template #actions>
      <VButton type="button" variant="secondary" :disabled="creating" @click="close">
        {{ $t('cortex.newMarkdown.cancel') }}
      </VButton>
      <VButton type="submit" form="new-markdown-form" variant="primary" :disabled="creating">
        {{ $t('cortex.newMarkdown.create') }}
      </VButton>
    </template>
  </VModal>
</template>
