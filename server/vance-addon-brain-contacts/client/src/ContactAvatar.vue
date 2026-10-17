<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { photoUrl } from './api';
import type { ContactScope } from './generated/contacts/ContactScope';

/**
 * A contact's picture, or its initials when it has none. The picture is
 * fetched with the reader's credentials and cached per version.
 */
const props = withDefaults(defineProps<{
  name: string;
  projectId: string | null;
  id?: string | null;
  scope?: ContactScope | null;
  hasPhoto?: boolean;
  photoVersion?: number | null;
  size?: 'sm' | 'md' | 'lg';
}>(), { id: null, scope: null, hasPhoto: false, photoVersion: null, size: 'md' });

const src = ref<string | null>(null);

watch(
  () => [props.projectId, props.id, props.scope, props.hasPhoto, props.photoVersion] as const,
  async ([projectId, id, scope, hasPhoto, version]) => {
    src.value = null;
    if (!hasPhoto || !id || !scope) return;
    src.value = await photoUrl(projectId, id, scope, version);
  },
  { immediate: true },
);

const initials = computed(() => {
  const parts = props.name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  const first = parts[0][0] ?? '';
  const last = parts.length > 1 ? parts[parts.length - 1][0] ?? '' : '';
  return (first + last).toUpperCase();
});

const box = computed(() => ({ sm: 'w-8 h-8 text-xs', md: 'w-12 h-12 text-base', lg: 'w-24 h-24 text-2xl' })[props.size]);
</script>

<template>
  <span
    :class="['inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full font-semibold bg-(--v-fill) text-(--v-label-secondary)', box]"
  >
    <img v-if="src" :src="src" :alt="name" class="w-full h-full object-cover" />
    <span v-else aria-hidden="true">{{ initials }}</span>
  </span>
</template>
