<script setup lang="ts">
import { computed, ref } from 'vue';
import { VAlert, VButton, VGroup, VGroupRow, VModal, VSegment } from '@vance/components';
import { updateOpen } from './api';
import { asOpenMode, type OpenMode } from './openMode';
import type { LinksView } from './generated/links/LinksView';
import CaptureTokenPanel from './CaptureTokenPanel.vue';
import { useT } from './i18n';

/**
 * The list's settings — the gear in the header. Two sections, both about the
 * list as a whole: how its links open, and the capture credentials an
 * outside tool saves into it.
 *
 * <p><b>The opening mode applies itself</b> rather than waiting for a Save:
 * a three-way segment is a mode switcher, and a Save button next to it would
 * have to answer "applies to what?" across a section it does not belong to.
 * A failed write reverts the segment and says so — the value the reader sees
 * is always the one the list has.
 */
const props = defineProps<{
  projectId: string;
  folder: string;
  /** The list default as the server last reported it (raw wire value). */
  open: string;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'saved', view: LinksView): void;
}>();

const t = useT();

const showing = ref(true);
const draft = ref<OpenMode>(asOpenMode(props.open));
const busy = ref(false);
const error = ref<string | null>(null);

const options = computed(() => [
  { value: 'tab', label: t('links.settings.openTab') },
  { value: 'same', label: t('links.settings.openSame') },
  { value: 'frame', label: t('links.settings.openFrame') },
]);

async function onOpenChange(mode: OpenMode): Promise<void> {
  if (mode === draft.value) return;
  const previous = draft.value;
  draft.value = mode;
  busy.value = true;
  error.value = null;
  try {
    const view = await updateOpen(props.projectId, props.folder, mode);
    emit('saved', view);
  } catch (e) {
    // The segment shows what the list says, not what the click wished for.
    draft.value = previous;
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

/**
 * Closing throws an un-copied connection string away — the server keeps no
 * copy. Deliberately **no confirm** here either: ESC closes the native
 * `<dialog>` before this runs, so a question at this point could not cancel
 * the close it is asking about. The loss is removed instead of confirmed —
 * minting copies to the clipboard straight away.
 */
function onClose(): void {
  showing.value = false;
  emit('close');
}
</script>

<template>
  <!-- `:model-value` rather than `v-model`: with both, the built-in binding
       and an explicit listener would each act on a close, and this component
       owns what closing means. -->
  <VModal
    :model-value="showing"
    :title="t('links.settings.title')"
    :description="t('links.settings.intro')"
    :close-on-backdrop="false"
    @update:model-value="onClose()"
  >
    <div class="flex flex-col gap-6">
      <VGroup :label="t('links.settings.opening')">
        <VGroupRow
          :label="t('links.settings.openLabel')"
          :description="t('links.settings.openHelp')"
        >
          <VSegment
            :model-value="draft"
            :options="options"
            :aria-label="t('links.settings.openLabel')"
            :disabled="busy"
            @update:model-value="onOpenChange($event as OpenMode)"
          />
        </VGroupRow>
      </VGroup>
      <!-- The one thing a frame cannot do and the reader cannot see coming:
           sites that refuse embedding. Stated next to the control, so nobody
           has to discover it by staring at an empty pane. -->
      <p v-if="draft === 'frame'" class="text-xs text-(--v-label-secondary)">
        {{ t('links.settings.frameNote') }}
      </p>

      <VAlert v-if="error" variant="error">{{ error }}</VAlert>

      <div class="flex flex-col gap-4 border-t border-(--v-separator) pt-4">
        <span class="text-xs font-semibold text-(--v-label-secondary)">
          {{ t('links.settings.captureSection') }}
        </span>
        <CaptureTokenPanel :project-id="projectId" :folder="folder" />
      </div>
    </div>

    <template #actions>
      <VButton variant="secondary" @click="onClose()">{{ t('links.common.close') }}</VButton>
    </template>
  </VModal>
</template>
