<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import type { SearchHitView } from './generated/search/SearchHitView';
import { imageFile, thumbnail } from './hitView';
import { previewImageFor, previewSettled, requestPreviewImage } from './previewImage';

/**
 * The picture for one hit, from whichever source has one.
 *
 * Three rungs, in order of how much they cost and how much they mean:
 * the file the hit *is* (image search), the picture the provider shipped
 * *with* it (Serper news lead image, video still, book cover), and — only
 * if neither exists — the og:image behind the link, through the brain's
 * preview proxy.
 *
 * The third rung is fetched lazily on visibility, the way the chat's link
 * cards do it. A search returns ten hits and a scrolling reader may never
 * reach the last five; fetching for those would spend ten foreign requests
 * to decorate four rows.
 *
 * When nothing resolves the component renders nothing at all. A permanent
 * grey box would claim there is a picture coming.
 */
const props = defineProps<{
  hit: SearchHitView;
  /** Detail view: the image file itself counts and gets the larger box. */
  full?: boolean;
}>();

const root = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | null = null;

/** What the provider already gave us — free, no request. */
const shipped = computed<string | null>(
  () => (props.full ? imageFile(props.hit) : null) ?? thumbnail(props.hit),
);

const src = computed<string | null>(
  () => shipped.value ?? previewImageFor(props.hit.url),
);

/** Hold the box only while an answer is outstanding. */
const awaiting = computed(
  () => !shipped.value && !previewSettled(props.hit.url),
);

onMounted(() => {
  if (shipped.value || !root.value) return;
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        requestPreviewImage(props.hit.url);
        disconnect();
        break;
      }
    },
    { rootMargin: '200px' },
  );
  observer.observe(root.value);
});

onBeforeUnmount(disconnect);

function disconnect(): void {
  observer?.disconnect();
  observer = null;
}
</script>

<template>
  <div
    v-if="src || awaiting"
    ref="root"
    :class="[
      'flex-none overflow-hidden rounded',
      full && src ? 'w-full' : 'h-20 w-28',
      awaiting ? 'bg-base-200' : '',
    ]"
  >
    <img
      v-if="src"
      :src="src"
      alt=""
      loading="lazy"
      referrerpolicy="no-referrer"
      :class="full ? 'max-h-64 w-full object-contain' : 'h-full w-full object-cover'"
    />
  </div>
</template>
