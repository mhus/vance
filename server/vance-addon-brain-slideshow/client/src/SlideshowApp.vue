<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { VAlert, VButton, VEmptyState } from '@vance/components';
import { documentContentUrl } from '@vance/shared';
import { getSlideshow, rebuildSlideshow } from './api';
import type { SlideView } from './generated/slideshow/SlideView';
import type { SlideshowView } from './generated/slideshow/SlideshowView';

const props = defineProps<{
  projectId: string;
  folder: string;
  title?: string;
}>();

const show = ref<SlideshowView | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);
const currentIndex = ref(0);
const autoplaying = ref(false);
const fullscreen = ref(false);
const viewportEl = ref<HTMLDivElement | null>(null);

let autoplayTimer: number | null = null;

const currentSlide = computed<SlideView | null>(() => {
  if (!show.value || show.value.slides.length === 0) return null;
  return show.value.slides[currentIndex.value] ?? null;
});

// Aspect ratio for the viewport. Manifest hint wins; otherwise use
// the current slide's pixel dimensions so the chrome doesn't shift
// between slides of identical shape. Fallback 16:9.
const aspectRatio = computed<string>(() => {
  if (show.value?.aspectRatio) return show.value.aspectRatio.replace(':', '/');
  const slide = currentSlide.value;
  if (slide?.width && slide?.height) return `${slide.width} / ${slide.height}`;
  return '16 / 9';
});

const slideUrl = computed<string | null>(() =>
  currentSlide.value ? documentContentUrl(currentSlide.value.documentId) : null,
);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    show.value = await getSlideshow(props.projectId, props.folder);
    if (currentIndex.value >= show.value.slides.length) {
      currentIndex.value = Math.max(0, show.value.slides.length - 1);
    }
  } catch (e) {
    error.value = `Could not load slideshow: ${(e as Error).message}`;
  } finally {
    loading.value = false;
  }
}

async function rebuild(): Promise<void> {
  try {
    await rebuildSlideshow(props.projectId, props.folder);
    await load();
  } catch (e) {
    error.value = `Rebuild failed: ${(e as Error).message}`;
  }
}

function next(): void {
  if (!show.value || show.value.slides.length === 0) return;
  currentIndex.value = (currentIndex.value + 1) % show.value.slides.length;
}

function prev(): void {
  if (!show.value || show.value.slides.length === 0) return;
  currentIndex.value =
    (currentIndex.value - 1 + show.value.slides.length) % show.value.slides.length;
}

function jump(idx: number): void {
  if (!show.value) return;
  currentIndex.value = Math.max(0, Math.min(idx, show.value.slides.length - 1));
}

function toggleAutoplay(): void {
  if (autoplaying.value) {
    stopAutoplay();
  } else {
    startAutoplay();
  }
}

function startAutoplay(): void {
  if (!show.value || show.value.autoplaySeconds <= 0) return;
  stopAutoplay();
  autoplaying.value = true;
  autoplayTimer = window.setInterval(() => next(), show.value.autoplaySeconds * 1000);
}

function stopAutoplay(): void {
  if (autoplayTimer != null) {
    window.clearInterval(autoplayTimer);
    autoplayTimer = null;
  }
  autoplaying.value = false;
}

async function toggleFullscreen(): Promise<void> {
  if (!document.fullscreenElement) {
    if (viewportEl.value) {
      await viewportEl.value.requestFullscreen?.();
    }
  } else {
    await document.exitFullscreen?.();
  }
}

function onFullscreenChange(): void {
  fullscreen.value = document.fullscreenElement != null;
}

function onKeydown(e: KeyboardEvent): void {
  // Don't hijack typing in an input (search field somewhere, etc.).
  const target = e.target as HTMLElement | null;
  if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return;
  switch (e.key) {
    case 'ArrowRight':
    case 'PageDown':
    case ' ':
      e.preventDefault();
      next();
      break;
    case 'ArrowLeft':
    case 'PageUp':
      e.preventDefault();
      prev();
      break;
    case 'Home':
      e.preventDefault();
      jump(0);
      break;
    case 'End':
      e.preventDefault();
      if (show.value) jump(show.value.slides.length - 1);
      break;
    case 'f':
    case 'F':
      e.preventDefault();
      void toggleFullscreen();
      break;
    case 'p':
    case 'P':
      e.preventDefault();
      toggleAutoplay();
      break;
  }
}

onMounted(async () => {
  await load();
  window.addEventListener('keydown', onKeydown);
  document.addEventListener('fullscreenchange', onFullscreenChange);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
  document.removeEventListener('fullscreenchange', onFullscreenChange);
  stopAutoplay();
});
</script>

<template>
  <div class="flex flex-col h-full bg-base-100">
    <div class="flex items-center justify-between p-4 border-b border-base-300">
      <div>
        <h1 class="text-xl font-semibold">{{ title ?? folder }}</h1>
        <div class="text-sm text-base-content/60 mt-0.5">
          {{ folder }}
          <template v-if="show && show.slides.length > 0">
            · Slide {{ currentIndex + 1 }} / {{ show.slides.length }}
          </template>
        </div>
      </div>
      <div class="flex gap-2 items-center">
        <VButton
          v-if="show && show.autoplaySeconds > 0"
          size="sm"
          :variant="autoplaying ? 'primary' : 'ghost'"
          @click="toggleAutoplay"
        >
          {{ autoplaying ? '⏸ Pause' : `▶ Play (${show.autoplaySeconds}s)` }}
        </VButton>
        <VButton size="sm" variant="ghost" @click="toggleFullscreen">
          {{ fullscreen ? 'Exit fullscreen' : 'Fullscreen (F)' }}
        </VButton>
        <VButton size="sm" variant="ghost" @click="load">Reload</VButton>
        <VButton size="sm" variant="ghost" @click="rebuild">Rebuild index</VButton>
      </div>
    </div>

    <VAlert v-if="error" variant="error" class="m-4">{{ error }}</VAlert>

    <div v-if="loading" class="p-8 text-base-content/70">Loading slideshow…</div>

    <VEmptyState
      v-else-if="show && show.slides.length === 0"
      class="m-4"
      headline="No slides"
      body="Upload images into this folder, then click 'Rebuild index' to refresh the slideshow."
    />

    <div
      v-else
      ref="viewportEl"
      class="flex-1 flex flex-col items-center justify-center bg-neutral text-neutral-content p-6 gap-4 relative overflow-hidden"
      @click="next"
    >
      <div
        class="flex-1 w-full flex items-center justify-center min-h-0"
      >
        <img
          v-if="slideUrl"
          :src="slideUrl"
          :alt="currentSlide?.caption ?? ''"
          :style="{ aspectRatio }"
          class="max-w-full max-h-full object-contain shadow-2xl"
          draggable="false"
        />
      </div>

      <div v-if="currentSlide?.caption" class="text-center text-sm opacity-80 max-w-2xl">
        {{ currentSlide.caption }}
      </div>

      <!-- Prev/next overlay buttons. Stop propagation so they don't trigger
           the viewport's click-to-next handler. -->
      <button
        v-if="show && show.slides.length > 1"
        class="absolute left-4 top-1/2 -translate-y-1/2 bg-base-100/20 hover:bg-base-100/40 text-base-content rounded-full w-12 h-12 flex items-center justify-center text-2xl"
        title="Previous slide (←)"
        @click.stop="prev"
      >‹</button>
      <button
        v-if="show && show.slides.length > 1"
        class="absolute right-4 top-1/2 -translate-y-1/2 bg-base-100/20 hover:bg-base-100/40 text-base-content rounded-full w-12 h-12 flex items-center justify-center text-2xl"
        title="Next slide (→)"
        @click.stop="next"
      >›</button>

      <!-- Slide counter dots — only when reasonable count. -->
      <div
        v-if="show && show.slides.length > 1 && show.slides.length <= 30"
        class="flex gap-1.5"
      >
        <button
          v-for="(_, idx) in show.slides"
          :key="idx"
          class="w-2 h-2 rounded-full transition-all"
          :class="idx === currentIndex ? 'bg-primary w-6' : 'bg-base-content/30 hover:bg-base-content/60'"
          :title="`Slide ${idx + 1}`"
          @click.stop="jump(idx)"
        />
      </div>
    </div>
  </div>
</template>
