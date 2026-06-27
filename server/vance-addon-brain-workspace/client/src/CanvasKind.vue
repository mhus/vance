<script setup lang="ts">
import { computed, ref } from 'vue';
import { CanvasEditor } from '@vance/block-editor';

/**
 * Adapter from the kind-registry mount contract (single `document` prop)
 * to {@link CanvasEditor}. Owns the save handler — the host invokes us
 * with the document state and we relay block edits back via the host's
 * standard write endpoint.
 *
 * Save flow:
 *   editor save → emit('save', body) → host write → server persists →
 *   live-WS pushes back to other connected clients
 *
 * In v1 this component does NOT call the REST endpoints itself — it
 * delegates the write to the parent slot (DocumentApp shell) by emitting
 * `save`. That keeps the kind-mount free of host-internal API knowledge.
 */
const props = defineProps<{
  document: {
    id: string;
    path: string;
    projectId: string;
    title?: string | null;
    inlineText?: string | null;
    mimeType?: string | null;
  };
}>();

const emit = defineEmits<{
  (e: 'save', body: string): void;
  (e: 'dirty', dirty: boolean): void;
}>();

const editorRef = ref<InstanceType<typeof CanvasEditor> | null>(null);
const source = computed(() => props.document.inlineText ?? '');

function onSave(body: string) {
  emit('save', body);
}

function onDirty(d: boolean) {
  emit('dirty', d);
}

defineExpose({
  save: () => editorRef.value?.save(),
});
</script>

<template>
  <CanvasEditor
    ref="editorRef"
    :document="document"
    :source="source"
    @save="onSave"
    @dirty="onDirty"
  />
</template>
