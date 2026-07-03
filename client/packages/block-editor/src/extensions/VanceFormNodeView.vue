<script setup lang="ts">
/**
 * NodeView for {@code ```vance-form} blocks. Mounts the host-provided
 * form component (injected from vance-face) with the block's
 * {@code config} URI. The host component loads the field schema +
 * current values from the edit-config's target and renders an editable
 * form with explicit Save / Cancel.
 *
 * When no host component is configured (e.g. the editor runs outside a
 * Vance host) the NodeView shows a static notice instead of the form —
 * the block-editor package itself has no form-engine.
 *
 * The inner wrapper carries {@code contenteditable="true"} for the same
 * reason as VanceEmbed: ProseMirror sets it false on childless nodes,
 * which would block input focus inside the hosted form.
 */
import { computed } from 'vue';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';

interface ExtensionOptions {
  formComponent?: (() => import('vue').Component | null) | null;
}

const props = defineProps<{
  node: ProseMirrorNode;
  updateAttributes: (attrs: Record<string, unknown>) => void;
  extension: { options: ExtensionOptions };
}>();

const config = computed(() => (props.node.attrs?.config as string | null) ?? '');
const saveScript = computed(() => (props.node.attrs?.saveScript as string | null) ?? '');
const session = computed(() => props.node.attrs?.session === true);
const form = computed(() => props.node.attrs?.form ?? { single: false, fields: [] });
const hostComponent = computed(
  () => props.extension.options.formComponent?.() ?? null,
);

// Persist an edited form definition (design-mode builder) into the fence.
function updateForm(next: unknown) {
  props.updateAttributes({ form: next });
}

// Persist the session opt-in (design-mode) into the fence.
function updateSession(next: boolean) {
  props.updateAttributes({ session: next });
}
</script>

<template>
  <NodeViewWrapper as="aside" class="vance-form" :data-config="config">
    <div v-if="hostComponent" class="vance-form__hosted" contenteditable="true">
      <component
        :is="hostComponent"
        :config="config"
        :save-script="saveScript"
        :session="session"
        :form="form"
        :update-form="updateForm"
        :update-session="updateSession"
      />
    </div>
    <div v-else class="vance-form__fallback" contenteditable="true">
      <span class="vance-form__icon">▦</span>
      <div class="vance-form__body">
        <div class="vance-form__title">Form</div>
        <div class="vance-form__path">{{ config }}</div>
      </div>
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.vance-form {
  margin: 0.75em 0;
}
.vance-form__hosted {
  position: relative;
}
.vance-form__fallback {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  border: 1px dashed oklch(var(--bc) / 0.3);
  border-radius: 0.5rem;
  background: oklch(var(--bc) / 0.04);
}
.vance-form__icon {
  font-size: 1.5em;
  line-height: 1.2;
  flex-shrink: 0;
}
.vance-form__body {
  flex: 1;
  min-width: 0;
}
.vance-form__title {
  font-weight: 600;
  font-size: 0.95rem;
}
.vance-form__path {
  font-family: monospace;
  font-size: 0.75rem;
  color: oklch(var(--bc) / 0.65);
  margin-top: 0.2em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
