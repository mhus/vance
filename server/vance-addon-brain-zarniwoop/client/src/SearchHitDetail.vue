<script setup lang="ts">
import { VButton, VShareButton } from '@vance/components';
// The state values come from the record that declares them — one authority for
// what `contentState` can say, on both sides of the wire.
import { CONTENT_ON_DEMAND, type SearchHitView } from './generated/search/SearchHitView';
import HitPicture from './HitPicture.vue';
import { extraLink, imageFile, link } from './hitView';

/**
 * What an opened hit shows beyond its headline.
 *
 * It lives in its own component so the picture, the full-text ladder and the
 * link row stay one thing — the card above it only decides *whether* to show
 * a detail, never what a detail is.
 *
 * Clicks are stopped here: the surrounding card is itself a toggle, and a
 * reader following a link or pressing "Load full text" is not asking to close
 * what they just opened.
 */
defineProps<{
  hit: SearchHitView;
  fullText: string | null;
  fullTextUrl: string | null;
  loadingBody: boolean;
}>();

defineEmits<{ (e: 'load'): void }>();

/**
 * Milliways, if the host offers it. A hit is the case the share subject was
 * generalised for: a title, a URL and a quote, with no document anywhere — so
 * showing one to a colleague no longer requires clipping it first.
 *
 * The snippet the provider shipped, else the body it shipped instead —
 * whichever exists is the quote. Neither is fetched for this.
 */
function shareSubject(hit: SearchHitView) {
  return { title: hit.title, link: hit.url, snippet: hit.snippet ?? hit.body ?? undefined };
}
</script>

<template>
  <div class="flex flex-col gap-1" @click.stop>
    <HitPicture :hit="hit" full />

    <!-- The ladder: the text is here, it is fetchable, or it is neither — and
         the third rung says so instead of showing a button that cannot work. -->
    <p v-if="hit.body" class="whitespace-pre-wrap text-sm">{{ hit.body }}</p>
    <template v-else-if="hit.contentState === CONTENT_ON_DEMAND">
      <div>
        <VButton
          v-if="!fullText && !fullTextUrl"
          size="sm"
          variant="ghost"
          :disabled="loadingBody"
          @click="$emit('load')"
        >
          {{ loadingBody ? 'Loading…' : 'Load full text' }}
          <span v-if="hit.sizeBytes" class="opacity-60">
            ({{ Math.round(hit.sizeBytes / 1024) }} kB)
          </span>
        </VButton>
      </div>
      <p v-if="fullText" class="whitespace-pre-wrap text-sm">{{ fullText }}</p>
      <!-- sandbox with nothing enabled: the bytes are foreign and a blob URL
           inherits this origin, so the frame gets no script, no forms and no
           same-origin access. -->
      <iframe
        v-if="fullTextUrl"
        :src="fullTextUrl"
        sandbox=""
        referrerpolicy="no-referrer"
        class="h-96 w-full rounded border"
      ></iframe>
    </template>
    <p v-else class="text-xs opacity-60">
      This provider ships no full text — the page behind the link is the answer.
    </p>

    <div class="mt-1 flex flex-wrap gap-3 text-sm">
      <VShareButton :subject="shareSubject(hit)" />
      <a
        v-if="link(hit.url)"
        :href="link(hit.url)!"
        target="_blank"
        rel="noopener noreferrer"
        class="hover:underline"
      >
        Open source page ↗
      </a>
      <!-- Two links for an image on purpose: the page carries the context and
           the attribution, the file is just pixels. -->
      <a
        v-if="imageFile(hit) && imageFile(hit) !== hit.url"
        :href="imageFile(hit)!"
        target="_blank"
        rel="noopener noreferrer"
        class="hover:underline"
      >
        Open image file ↗
      </a>
      <a
        v-if="extraLink(hit, 'videoUrl')"
        :href="extraLink(hit, 'videoUrl')!"
        target="_blank"
        rel="noopener noreferrer"
        class="hover:underline"
      >
        Watch video ↗
      </a>
      <a
        v-if="extraLink(hit, 'pdfUrl')"
        :href="extraLink(hit, 'pdfUrl')!"
        target="_blank"
        rel="noopener noreferrer"
        class="hover:underline"
      >
        Open PDF ↗
      </a>
      <a
        v-if="extraLink(hit, 'hnDiscussion')"
        :href="extraLink(hit, 'hnDiscussion')!"
        target="_blank"
        rel="noopener noreferrer"
        class="hover:underline"
      >
        Open discussion ↗
      </a>
    </div>
  </div>
</template>
