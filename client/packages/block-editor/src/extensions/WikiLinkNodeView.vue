<script setup lang="ts">
import { computed } from 'vue';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { WikiLinkOptions } from './VanceWikiLink';

const props = defineProps<{
  node: { attrs: Record<string, unknown> };
  extension: { options: WikiLinkOptions };
}>();

const target = computed(() => String(props.node.attrs.target ?? ''));
const label = computed(() => (String(props.node.attrs.label ?? '') || target.value));

// Best-effort existence check for red-link styling. Re-resolves when the
// target changes; a created page self-corrects on the next editor mount.
const exists = computed(() => {
  try {
    return props.extension.options.resolveWikiLink(target.value) !== false;
  } catch {
    return true;
  }
});

function onClick(e: MouseEvent) {
  e.preventDefault();
  e.stopPropagation();
  props.extension.options.openWikiLink(target.value);
}
</script>

<template>
  <NodeViewWrapper as="span" class="vance-wikilink-wrap">
    <a
      class="vance-wikilink"
      :class="{ 'vance-wikilink--missing': !exists }"
      :title="exists ? target : `${target} — anlegen`"
      contenteditable="false"
      @click="onClick"
    >{{ label }}</a>
  </NodeViewWrapper>
</template>

<style>
.vance-wikilink-wrap { display: inline; }
.vance-wikilink {
  color: var(--color-primary);
  text-decoration: none;
  cursor: pointer;
  border-bottom: 1px solid color-mix(in oklab, var(--color-primary) 40%, transparent);
}
.vance-wikilink:hover { text-decoration: underline; }
.vance-wikilink--missing {
  color: var(--color-error);
  border-bottom: 1px dashed color-mix(in oklab, var(--color-error) 55%, transparent);
}
</style>
