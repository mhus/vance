<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { imageOf, previewSettled, requestPreview } from './linkPreview';

/**
 * The picture for one link.
 *
 * Two rungs: the picture somebody set by hand, and — only if there is none —
 * the og:image behind the link, through the brain's preview proxy.
 *
 * The second rung is fetched lazily on visibility, the way the chat's link
 * cards and the search app's hits do it. A list of eighty bookmarks that a
 * reader scrolls a third of must not spend eighty foreign requests to
 * decorate the twenty rows that were looked at.
 *
 * When nothing resolves the component renders nothing at all. A permanent
 * grey box would claim a picture is still coming.
 */
const props = defineProps<{
  url: string;
  /** The stored override, if the reader set one. */
  image?: string | null;
  alt?: string;
}>();

const root = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | null = null;

const src = computed<string | null>(() => imageOf(props.image, props.url));

/** Hold the box only while an answer is outstanding. */
const awaiting = computed(() => !props.image && !previewSettled(props.url));

onMounted(() => {
  if (props.image || !root.value) return;
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        requestPreview(props.url);
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
    :class="['h-20 w-28 flex-none overflow-hidden rounded', awaiting ? 'bg-base-200' : '']"
  >
    <!-- referrerpolicy: the thumbnail is fetched straight from the foreign
         host, and it has no business learning which page asked for it. -->
    <img
      v-if="src"
      :src="src"
      :alt="alt ?? ''"
      loading="lazy"
      referrerpolicy="no-referrer"
      class="h-full w-full object-cover"
    />
  </div>
</template>
