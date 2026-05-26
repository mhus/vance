<script setup lang="ts">
/**
 * YouTube embed for the inline channel.
 *
 * Fence forms accepted:
 *   ```youtube
 *   https://www.youtube.com/watch?v=dQw4w9WgXcQ
 *   ```
 *   ```youtube
 *   https://youtu.be/dQw4w9WgXcQ
 *   ```
 *   ```youtube
 *   dQw4w9WgXcQ
 *   ```
 *
 * Fence-meta keys understood:
 *   start=N        seconds offset into the video
 *   title=...      caption shown below the player
 *
 * Privacy: we serve from {@code youtube-nocookie.com} so no tracking
 * cookies fire until the user actually presses play. No autoplay,
 * no rel-videos.
 *
 * Spec: specification/inline-and-embedded-content.md §8.
 */
import { computed } from 'vue';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';

interface Props {
  /** Mode is structurally always inline for this kind. */
  mode?: 'inline';
  content: string;
  meta?: FenceMeta;
}

const props = withDefaults(defineProps<Props>(), {
  mode: 'inline',
  meta: () => ({}),
});

const videoId = computed<string | null>(() => extractVideoId(props.content));

const startSeconds = computed<number>(() => {
  const raw = props.meta.start;
  if (!raw) return 0;
  const n = parseInt(raw, 10);
  return Number.isFinite(n) && n > 0 ? n : 0;
});

const embedSrc = computed<string>(() => {
  if (!videoId.value) return '';
  const params = new URLSearchParams();
  params.set('rel', '0');
  params.set('modestbranding', '1');
  if (startSeconds.value > 0) params.set('start', String(startSeconds.value));
  return `https://www.youtube-nocookie.com/embed/${videoId.value}?${params.toString()}`;
});

const watchUrl = computed<string>(() => {
  if (!videoId.value) return '';
  const base = `https://www.youtube.com/watch?v=${videoId.value}`;
  return startSeconds.value > 0 ? `${base}&t=${startSeconds.value}s` : base;
});

const caption = computed<string>(() => props.meta.title ?? '');

const ID_RE = /^[A-Za-z0-9_-]{11}$/;

function videoIdFromUrl(token: string): string | null {
  try {
    const url = new URL(token);
    const host = url.hostname.replace(/^www\./, '').toLowerCase();
    if (host === 'youtu.be') {
      const id = url.pathname.replace(/^\//, '').split('/')[0];
      return ID_RE.test(id) ? id : null;
    }
    if (host === 'youtube.com'
        || host === 'm.youtube.com'
        || host === 'youtube-nocookie.com') {
      const v = url.searchParams.get('v');
      if (v && ID_RE.test(v)) return v;
      const segs = url.pathname.split('/').filter(Boolean);
      const idx = segs.findIndex((p) => p === 'embed' || p === 'shorts' || p === 'v');
      if (idx >= 0 && segs[idx + 1] && ID_RE.test(segs[idx + 1])) {
        return segs[idx + 1];
      }
    }
  } catch {
    // not a URL
  }
  return null;
}

/**
 * Forgiving id extraction. LLMs sometimes "help" by writing a
 * YAML-style body ({@code id: <id>}, {@code videoId: <id>}, …)
 * instead of just pasting the URL. Rather than refuse those, scan
 * line by line and accept the first plausible signal.
 *
 * Accepted shapes (any line):
 * 1. bare 11-char id
 * 2. youtube watch / short / embed / shorts URL
 * 3. {@code id|videoId|video_id|v|url: <value>} (quoted or bare,
 *    {@code :} or {@code =} as separator)
 */
function extractVideoId(raw: string): string | null {
  const s = (raw ?? '').trim();
  if (!s) return null;
  if (ID_RE.test(s)) return s;
  const direct = videoIdFromUrl(s);
  if (direct) return direct;

  const lines = s.split(/\r?\n/);
  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line) continue;
    if (ID_RE.test(line)) return line;
    const fromUrl = videoIdFromUrl(line);
    if (fromUrl) return fromUrl;
    // YAML-/key-value-style: id: <id> | url: <url>. Allow quoted
    // and unquoted values; tolerate `:` or `=` as separator.
    const m = line.match(
      /^\s*(id|videoid|video_id|v|url|videourl|video_url|link)\s*[:=]\s*["']?(.+?)["']?\s*$/i,
    );
    if (m) {
      const val = m[2].trim();
      if (ID_RE.test(val)) return val;
      const fromVal = videoIdFromUrl(val);
      if (fromVal) return fromVal;
    }
  }
  return null;
}
</script>

<template>
  <div class="yt-view">
    <div v-if="!videoId" class="yt-view__error">
      Keine gültige YouTube-Video-ID aus dem Body extrahierbar.
    </div>
    <template v-else>
      <div class="yt-view__frame-wrap">
        <iframe
          class="yt-view__frame"
          :src="embedSrc"
          loading="lazy"
          referrerpolicy="strict-origin-when-cross-origin"
          allow="accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
          allowfullscreen
          title="YouTube video"
        />
      </div>
      <p class="yt-view__meta">
        <span v-if="caption">{{ caption }}</span>
        <a
          :href="watchUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="yt-view__source"
        >▶ youtube.com</a>
      </p>
    </template>
  </div>
</template>

<style scoped>
.yt-view {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.yt-view__frame-wrap {
  position: relative;
  width: 100%;
  /* 16:9 aspect-ratio box. */
  padding-bottom: 56.25%;
  background: hsl(var(--bc) / 0.04);
  border-radius: 0.375rem;
  overflow: hidden;
}
.yt-view__frame {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border: none;
}
.yt-view__meta {
  display: flex;
  gap: 0.75rem;
  align-items: baseline;
  margin: 0;
  font-size: 0.8rem;
  opacity: 0.75;
}
.yt-view__source {
  margin-left: auto;
  color: hsl(var(--p));
  text-decoration: none;
}
.yt-view__source:hover {
  text-decoration: underline;
}
.yt-view__error {
  padding: 0.6rem;
  font-size: 0.85rem;
  color: hsl(var(--er));
  background: hsl(var(--er) / 0.08);
  border-radius: 0.375rem;
}
</style>
