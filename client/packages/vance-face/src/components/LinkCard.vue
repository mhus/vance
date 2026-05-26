<script setup lang="ts">
/**
 * Slack-style preview card for external links the LLM emitted in
 * chat. Self-managed: on first visibility (IntersectionObserver) it
 * fetches OpenGraph metadata from the Brain proxy, caches it
 * client-side, and renders a card or a muted "preview unavailable"
 * badge depending on the verdict.
 *
 * Rendered by {@code MarkdownView.vue} after each paragraph that
 * contains external https-URLs — the inline link stays clickable,
 * the card is supplementary context.
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { fetchLinkPreview } from '@vance/shared';
import type { LinkPreviewDto } from '@vance/generated';

const props = defineProps<{
  url: string;
}>();

const cardRoot = ref<HTMLDivElement | null>(null);
const preview = ref<LinkPreviewDto | null>(null);
const loading = ref(false);
const failed = ref(false);
let observer: IntersectionObserver | null = null;

/**
 * Resolves the favicon URL for the link's host via Google's s2
 * service. CORS-stable across browsers, gives a usable 64px icon
 * for nearly every domain that has one in its HTML head. Falls
 * back to {@code null} only when the URL doesn't parse.
 */
function faviconUrl(): string | null {
  try {
    const host = new URL(props.url).hostname;
    if (!host) return null;
    return `https://www.google.com/s2/favicons?domain=${encodeURIComponent(host)}&sz=64`;
  } catch {
    return null;
  }
}

/**
 * Picks the best thumbnail for the card: OG image first (full
 * 96x72 box), falling back to a 48x48 favicon. Cards without OG
 * data still get a visual anchor on the left — closer to the
 * Slack / Telegram look the user expects.
 */
const thumbnail = computed<{ url: string; kind: 'image' | 'icon' } | null>(() => {
  const ogImage = preview.value?.image;
  if (ogImage && ogImage.trim().length > 0) {
    return { url: ogImage, kind: 'image' };
  }
  const fav = faviconUrl();
  return fav ? { url: fav, kind: 'icon' } : null;
});

async function loadPreview(): Promise<void> {
  if (preview.value || loading.value) return;
  loading.value = true;
  try {
    preview.value = await fetchLinkPreview(props.url);
  } catch (e) {
    // Network/auth errors are non-fatal for previews — render the
    // muted fallback rather than throwing into the parent. The
    // inline link itself is still clickable.
    console.warn('LinkCard: preview fetch failed', props.url, e);
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (!cardRoot.value) return;
  // IntersectionObserver: only fetch when the card scrolls into
  // view. Long chat histories with many links shouldn't trigger
  // dozens of unused fetches.
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          void loadPreview();
          observer?.disconnect();
          observer = null;
          break;
        }
      }
    },
    { rootMargin: '100px' },
  );
  observer.observe(cardRoot.value);
});

onBeforeUnmount(() => {
  observer?.disconnect();
  observer = null;
});

function hostnameLabel(): string {
  try {
    return new URL(props.url).hostname.replace(/^www\./, '');
  } catch {
    return props.url;
  }
}
</script>

<template>
  <div ref="cardRoot" class="link-card">
    <a
      v-if="preview && preview.ok"
      :href="preview.finalUrl ?? url"
      target="_blank"
      rel="noopener noreferrer"
      class="link-card__inner"
    >
      <img
        v-if="thumbnail"
        :src="thumbnail.url"
        :alt="preview.title ?? ''"
        :class="thumbnail.kind === 'image' ? 'link-card__image' : 'link-card__icon'"
        loading="lazy"
      />
      <div class="link-card__body">
        <div v-if="preview.siteName" class="link-card__site">{{ preview.siteName }}</div>
        <div v-if="preview.title" class="link-card__title">{{ preview.title }}</div>
        <div v-if="preview.description" class="link-card__desc">{{ preview.description }}</div>
      </div>
    </a>
    <!-- Restricted: host refused preview (Cloudflare / login / rate
         limit) but the link itself works in a real browser. Stay
         clickable so the user can still navigate. -->
    <a
      v-else-if="preview && !preview.ok && preview.failureReason === 'access_restricted'"
      :href="preview.finalUrl ?? url"
      target="_blank"
      rel="noopener noreferrer"
      class="link-card__inner link-card__inner--restricted"
      :title="`Host refused preview (HTTP ${preview.status}); open in browser`"
    >
      <img
        v-if="thumbnail"
        :src="thumbnail.url"
        alt=""
        :class="thumbnail.kind === 'image' ? 'link-card__image' : 'link-card__icon'"
        loading="lazy"
      />
      <div class="link-card__body">
        <div class="link-card__site">{{ preview.siteName ?? hostnameLabel() }}</div>
        <div class="link-card__title link-card__title--restricted">Open in browser</div>
      </div>
    </a>
    <div
      v-else-if="preview && !preview.ok"
      class="link-card__inner link-card__inner--muted"
      :title="preview.failureReason ?? ''"
    >
      <img
        v-if="thumbnail"
        :src="thumbnail.url"
        alt=""
        :class="thumbnail.kind === 'image' ? 'link-card__image' : 'link-card__icon'"
        loading="lazy"
      />
      <div class="link-card__body">
        <div class="link-card__site">{{ preview.siteName ?? hostnameLabel() }}</div>
        <div class="link-card__title link-card__title--muted">Preview unavailable</div>
      </div>
    </div>
    <div
      v-else-if="failed"
      class="link-card__inner link-card__inner--muted"
    >
      <img
        v-if="thumbnail"
        :src="thumbnail.url"
        alt=""
        :class="thumbnail.kind === 'image' ? 'link-card__image' : 'link-card__icon'"
        loading="lazy"
      />
      <div class="link-card__body">
        <div class="link-card__site">{{ hostnameLabel() }}</div>
        <div class="link-card__title link-card__title--muted">Preview unavailable</div>
      </div>
    </div>
    <div v-else-if="loading" class="link-card__inner link-card__inner--loading">
      <div class="link-card__body">
        <div class="link-card__site">{{ hostnameLabel() }}</div>
        <div class="link-card__title link-card__title--muted">Loading preview…</div>
      </div>
    </div>
    <!-- Idle (not yet observed): render a small placeholder so the
         observer has something to attach to. -->
    <div v-else class="link-card__placeholder" />
  </div>
</template>

<style scoped>
.link-card {
  margin: 0.4rem 0;
}

.link-card__inner {
  display: flex;
  gap: 0.75rem;
  align-items: stretch;
  padding: 0.5rem 0.75rem;
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  background: hsl(var(--b1));
  text-decoration: none !important;
  color: inherit;
  transition: background 0.15s, border-color 0.15s;
}
.link-card__inner:hover {
  background: hsl(var(--bc) / 0.04);
  border-color: hsl(var(--bc) / 0.25);
}

.link-card__inner--muted {
  cursor: default;
  opacity: 0.7;
}
.link-card__inner--muted:hover {
  background: hsl(var(--b1));
  border-color: hsl(var(--bc) / 0.15);
}

/* "Reachable but preview restricted" — host blocked the OG fetch
   (Cloudflare / login wall / rate limit), but the link is still a
   live destination. Keep it clickable, just signal the lower
   confidence with a slightly muted accent. */
.link-card__inner--restricted {
  opacity: 0.85;
}
.link-card__title--restricted {
  font-weight: 500;
  color: hsl(var(--p));
}

.link-card__inner--loading {
  cursor: progress;
}

.link-card__image {
  flex: 0 0 96px;
  width: 96px;
  height: 72px;
  object-fit: cover;
  border-radius: 0.375rem;
  background: hsl(var(--bc) / 0.05);
}

/* Favicon fallback — used when the page didn't expose an og:image.
   Smaller, square, centered against the body so the layout doesn't
   look like a broken thumbnail. */
.link-card__icon {
  flex: 0 0 48px;
  width: 48px;
  height: 48px;
  align-self: center;
  object-fit: contain;
  padding: 0.35rem;
  border-radius: 0.5rem;
  background: hsl(var(--bc) / 0.05);
}

.link-card__body {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}

.link-card__site {
  font-size: 0.75rem;
  color: hsl(var(--bc) / 0.65);
  text-transform: lowercase;
  letter-spacing: 0.02em;
}

.link-card__title {
  font-size: 0.95rem;
  font-weight: 600;
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.link-card__title--muted {
  font-weight: 500;
  color: hsl(var(--bc) / 0.7);
}

.link-card__desc {
  font-size: 0.85rem;
  color: hsl(var(--bc) / 0.75);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.link-card__placeholder {
  height: 1px;
}
</style>
