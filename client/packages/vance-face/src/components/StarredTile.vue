<script setup lang="ts">
/**
 * One starred document as a compact tile for the landing page.
 *
 * Deliberately narrow: a title, an optional description, and a link. No status,
 * no counts — those would each be a request, and the landing page is the worst
 * place to fan out over N documents in N projects.
 *
 * Long content is clipped rather than wrapped: the tile grid must stay a grid.
 */
import { computed } from 'vue';
import type { StarredItemDto } from '@vance/generated';

const props = defineProps<{
  item: StarredItemDto;
  /** Reported unreachable by the last check — greyed out, with a remove offer. */
  broken?: boolean;
}>();

const emit = defineEmits<{ (e: 'remove'): void }>();

/**
 * Cortex opens by path. Cortex resolves it to a document id on boot — the
 * stored entry keeps the path because that is the stable business key, and
 * a Mongo id has no business in a control file or an address bar.
 */
const href = computed(() => {
  const p = new URLSearchParams({
    project: props.item.project,
    path: props.item.path,
  });
  return `/cortex?${p}`;
});

const label = computed(() => props.item.title?.trim() || props.item.path);
</script>

<template>
  <div
    class="relative flex flex-col rounded-lg border bg-base-100 shadow-sm transition-colors"
    :class="[
      broken
        ? 'border-base-300 opacity-60'
        : item.highlight
          ? 'border-primary ring-1 ring-primary'
          : 'border-base-300 hover:border-primary',
    ]"
  >
    <a
      :href="broken ? undefined : href"
      class="flex min-w-0 flex-col gap-1 p-3 no-underline text-base-content"
      :class="broken ? 'cursor-default' : 'cursor-pointer'"
    >
      <div class="truncate font-semibold" :title="label">{{ label }}</div>
      <div
        v-if="item.description"
        class="line-clamp-2 text-xs opacity-70"
        :title="item.description"
      >{{ item.description }}</div>
      <div v-else class="truncate text-xs opacity-50" :title="item.path">
        {{ item.project }}
      </div>
    </a>
    <!-- A broken tile is not silently dropped: the entry is the user's
         curation, so removing it stays their decision. -->
    <button
      v-if="broken"
      type="button"
      class="absolute right-1 top-1 px-1 text-xs opacity-60 hover:opacity-100"
      :title="$t('starred.remove')"
      @click="emit('remove')"
    >✕</button>
  </div>
</template>

<style scoped>
@reference "../style/app.css";
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
