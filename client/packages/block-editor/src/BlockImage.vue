<script setup lang="ts">
/**
 * Read-only image renderer for BlockView. Image sources authored in a
 * workpage are `vance:/…` URIs the browser cannot load directly (spec
 * §4); when a host provides `resolveImageSrc` we resolve the URI to a
 * loadable URL before binding `<img src>`, mirroring the editor-side
 * VanceImageNodeView. A plain http(s)/data src is bound as-is.
 */
import { computed, ref, watch } from 'vue';

const props = defineProps<{
  src: string;
  alt: string;
  resolveImageSrc?: (vanceUri: string) => Promise<string | null>;
}>();

const resolved = ref<string | null>(null);
const failed = ref(false);

const isVanceUri = computed(() => props.src.startsWith('vance:'));
const effectiveSrc = computed<string | null>(() =>
  isVanceUri.value ? resolved.value : props.src,
);

async function update(): Promise<void> {
  failed.value = false;
  resolved.value = null;
  if (!isVanceUri.value) return;
  const resolver = props.resolveImageSrc;
  if (!resolver) {
    failed.value = true; // no host resolver → cannot load a vance: image
    return;
  }
  try {
    const url = await resolver(props.src);
    if (url) resolved.value = url;
    else failed.value = true;
  } catch {
    failed.value = true;
  }
}

watch(() => props.src, update, { immediate: true });
</script>

<template>
  <img
    v-if="effectiveSrc"
    :src="effectiveSrc"
    :alt="alt"
    class="block-view__image"
  />
  <span v-else-if="failed" class="block-view__image-missing">🖼 {{ alt || 'image' }}</span>
</template>

<style scoped>
.block-view__image-missing {
  display: inline-block;
  padding: 0.25em 0.5em;
  border: 1px dashed color-mix(in oklab, var(--color-base-content) 40%, transparent);
  border-radius: 0.375rem;
  color: color-mix(in oklab, var(--color-base-content) 65%, transparent);
  font-size: 0.85em;
}
</style>
