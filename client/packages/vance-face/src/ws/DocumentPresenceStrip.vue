<script setup lang="ts">
/**
 * Avatar strip showing who else has the document at {@code path}
 * currently open. Subscribes on mount via the {@code documents}
 * channel of the Live-WS; unsubscribes on unmount.
 *
 * <p>Server pre-filters the viewer's own {@code editorId} out of the
 * roster, so the entries shown here are always "other people" or
 * "other tabs of yours" — never the current connection.
 *
 * <p>Empty state is invisible: when nobody else is on the path,
 * the strip renders nothing at all (no whitespace, no "0 viewers"
 * indicator). Drop it into a layout slot and it appears only when
 * presence is actually present.
 *
 * <p>See {@code planning/document-presence.md}.
 */
import { computed, onBeforeUnmount, watch } from 'vue';
import {
  subscribeDocument,
  unsubscribeDocument,
  useWsConnection,
} from './wsConnectionStore';

interface Props {
  /** Document path to track (e.g. {@code _vance/notes.md}). */
  path: string;
  /** Avatar diameter in px. Default 22. */
  size?: number;
  /** Max avatars to render before collapsing into a "+N" pill. Default 5. */
  max?: number;
}

const props = withDefaults(defineProps<Props>(), { size: 22, max: 5 });

const { documentViewers } = useWsConnection();

const viewers = computed(() => documentViewers.get(props.path) ?? []);
const visibleViewers = computed(() => viewers.value.slice(0, props.max));
const overflowCount = computed(() => Math.max(0, viewers.value.length - props.max));

watch(
  () => props.path,
  (next, prev) => {
    if (prev) void unsubscribeDocument(prev);
    if (next) void subscribeDocument(next);
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  if (props.path) void unsubscribeDocument(props.path);
});

function initials(displayName: string): string {
  const trimmed = displayName.trim();
  if (!trimmed) return '?';
  const parts = trimmed.split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * Deterministic avatar color from the userId — same user always shows
 * the same color, across tabs and reconnects. Tailwind/DaisyUI hue rota.
 */
function colorFor(userId: string): string {
  const palette = [
    'bg-primary text-primary-content',
    'bg-secondary text-secondary-content',
    'bg-accent text-accent-content',
    'bg-info text-info-content',
    'bg-success text-success-content',
    'bg-warning text-warning-content',
  ];
  let hash = 0;
  for (let i = 0; i < userId.length; i++) hash = (hash * 31 + userId.charCodeAt(i)) | 0;
  return palette[Math.abs(hash) % palette.length];
}
</script>

<template>
  <div
    v-if="viewers.length > 0"
    class="flex items-center -space-x-1.5"
    :title="$t('documentPresence.tooltip', { n: viewers.length })"
  >
    <div
      v-for="(v, i) in visibleViewers"
      :key="v.editorId"
      class="rounded-full ring-2 ring-base-100 flex items-center justify-center font-medium select-none"
      :class="colorFor(v.userId)"
      :style="{
        width: `${size}px`,
        height: `${size}px`,
        fontSize: `${Math.round(size * 0.42)}px`,
        zIndex: visibleViewers.length - i,
      }"
      :title="v.displayName"
    >
      {{ initials(v.displayName) }}
    </div>
    <div
      v-if="overflowCount > 0"
      class="rounded-full ring-2 ring-base-100 bg-base-300 text-base-content
             flex items-center justify-center font-medium select-none"
      :style="{
        width: `${size}px`,
        height: `${size}px`,
        fontSize: `${Math.round(size * 0.42)}px`,
      }"
    >
      +{{ overflowCount }}
    </div>
  </div>
</template>
