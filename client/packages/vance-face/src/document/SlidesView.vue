<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Marpit } from '@marp-team/marpit';
import { parseSlides, type SlidesDocument } from './slidesCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

/**
 * Renderer for `kind: slides` documents. Marpit turns the deck's
 * Markdown into HTML+CSS; we mount the per-slide HTML in a scoped
 * container and inject Marpit's generated CSS via an owned <style>
 * element so theme rules don't leak.
 *
 * Two viewing modes inside the editor:
 *   - Single — one slide at a time, keyboard / button navigation.
 *   - Stream — all slides stacked vertically (overview / printing).
 *
 * Mode prop matches the {@code MindmapView} convention:
 *   - {@code editor}   — full editor surface, {@code doc} prop required.
 *   - {@code embedded} — compact render from a loaded {@link DocumentDto}.
 *
 * No {@code inline} mode — slides are embedded-only per
 * {@link specification/inline-and-embedded-content.md} §8.
 */
defineOptions({ name: 'SlidesView' });

/**
 * Minimal Marpit theme. 1280×720 canvas (16:9) — inlineSVG scales it
 * to whatever width the container provides. Typography deliberately
 * neutral; we don't try to mimic DaisyUI tokens here because Marpit's
 * CSS lives inside an SVG/foreignObject sandbox where CSS variables
 * from the surrounding app don't reliably propagate.
 */
const DEFAULT_THEME_CSS = `
/* @theme vance-default */
section {
  width: 1280px;
  height: 720px;
  padding: 70px 80px;
  background: #ffffff;
  color: #1f2937;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, system-ui, sans-serif;
  font-size: 28px;
  line-height: 1.5;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  box-sizing: border-box;
  overflow: hidden;
}
section h1 { font-size: 64px; margin: 0 0 0.3em; color: #1d4ed8; font-weight: 700; line-height: 1.1; }
section h2 { font-size: 44px; margin: 0 0 0.4em; color: #1e3a8a; font-weight: 700; line-height: 1.15; }
section h3 { font-size: 32px; margin: 0 0 0.3em; color: #1e3a8a; font-weight: 600; }
section p { margin: 0.4em 0; }
section ul, section ol { padding-left: 1.4em; margin: 0.4em 0; }
section li { margin: 0.25em 0; }
section li > p { margin: 0; }
section strong { font-weight: 700; color: inherit; }
section em { font-style: italic; }
section a { color: #2563eb; text-decoration: underline; }
section code {
  background: #f3f4f6;
  padding: 0.08em 0.35em;
  border-radius: 0.25em;
  font-family: ui-monospace, SFMono-Regular, "Cascadia Mono", monospace;
  font-size: 0.85em;
}
section pre {
  background: #1f2937;
  color: #f9fafb;
  padding: 0.8em 1em;
  border-radius: 0.5em;
  overflow: auto;
  font-size: 0.75em;
  line-height: 1.4;
}
section pre code { background: none; padding: 0; color: inherit; font-size: 1em; }
section blockquote {
  border-left: 4px solid #93c5fd;
  margin: 0.5em 0;
  padding: 0.2em 0.8em;
  color: #4b5563;
  font-style: italic;
}
section table { border-collapse: collapse; margin: 0.5em 0; font-size: 0.9em; }
section th, section td { border: 1px solid #d1d5db; padding: 0.4em 0.7em; text-align: left; }
section th { background: #f9fafb; font-weight: 600; }
section img { max-width: 100%; height: auto; }
section header {
  position: absolute;
  top: 24px;
  left: 80px;
  right: 80px;
  font-size: 16px;
  color: #6b7280;
}
section footer {
  position: absolute;
  bottom: 24px;
  left: 80px;
  right: 80px;
  font-size: 16px;
  color: #6b7280;
}
section::after {
  content: attr(data-marpit-pagination);
  position: absolute;
  bottom: 24px;
  right: 80px;
  font-size: 18px;
  color: #9ca3af;
}
`;

interface Props {
  mode?: 'editor' | 'embedded';
  doc?: SlidesDocument;
  document?: DocumentDto;
  embedRef?: EmbedRef;
}

const props = withDefaults(defineProps<Props>(), { mode: 'editor' });

const { t } = useI18n();

const styleHost = ref<HTMLElement | null>(null);
const stageHost = ref<HTMLElement | null>(null);

const currentIndex = ref(0);
const stream = ref(false);
const presenting = ref(false);
const rootEl = ref<HTMLElement | null>(null);

let marpit: Marpit | null = null;

const resolvedDoc = computed<SlidesDocument>(() => {
  if (props.mode === 'editor') {
    return props.doc ?? emptyDoc();
  }
  // embedded
  const d = props.document;
  if (!d || !d.inlineText) return emptyDoc();
  const mime = d.mimeType ?? 'text/markdown';
  try {
    return parseSlides(d.inlineText, mime);
  } catch (e) {
    console.warn('SlidesView: failed to parse embedded document', e);
    return emptyDoc();
  }
});

const items = computed<string[]>(() => resolvedDoc.value.items);

function emptyDoc(): SlidesDocument {
  return { kind: 'slides', items: [], slides: { extra: {} }, extra: {} };
}

/**
 * Reduce the deck back to the Markdown form Marpit consumes. The
 * codec already split slides apart; we re-join with the canonical
 * `\n\n---\n\n` separator and prepend the directive front-matter
 * (Marpit's own YAML directives like `paginate`, `header`, `footer`).
 *
 * Note: Vance's `slides:` block wraps the Marpit-style keys, so we
 * lift them up to top-level here — Marpit doesn't understand a nested
 * `slides:` key.
 */
function buildMarpitMarkdown(doc: SlidesDocument): string {
  const directives: string[] = [];
  const h = doc.slides;
  if (h.paginate !== undefined) directives.push(`paginate: ${h.paginate}`);
  if (h.header !== undefined) directives.push(`header: ${JSON.stringify(h.header)}`);
  if (h.footer !== undefined) directives.push(`footer: ${JSON.stringify(h.footer)}`);
  if (h.defaultClass !== undefined) directives.push(`class: ${JSON.stringify(h.defaultClass)}`);
  // `theme` and `aspect` are Vance-level concepts — Marpit picks them
  // up through theme name + CSS rules. Wire-through is §6 work.

  const frontMatter = directives.length > 0
    ? `---\n${directives.join('\n')}\n---\n\n`
    : '';

  return frontMatter + doc.items.join('\n\n---\n\n');
}

interface Rendered {
  htmlArray: string[];
  css: string;
}

const rendered = computed<Rendered>(() => {
  if (!marpit || items.value.length === 0) {
    return { htmlArray: [], css: '' };
  }
  try {
    const md = buildMarpitMarkdown(resolvedDoc.value);
    const result = marpit.render(md, { htmlAsArray: true }) as unknown as {
      html: string[];
      css: string;
    };
    return { htmlArray: result.html, css: result.css };
  } catch (e) {
    console.warn('SlidesView: Marpit render failed', e);
    return { htmlArray: [], css: '' };
  }
});

/** Stage HTML — the actual DOM injected into the slides container.
 *  Wraps in `<div class="marpit">` so Marpit's generated CSS rules
 *  (which are all prefixed with `.marpit`) apply. */
const stageHtml = computed<string>(() => {
  const arr = rendered.value.htmlArray;
  if (arr.length === 0) return '';
  if (stream.value) {
    return `<div class="marpit">${arr.join('')}</div>`;
  }
  const idx = Math.min(Math.max(currentIndex.value, 0), arr.length - 1);
  return `<div class="marpit">${arr[idx] ?? ''}</div>`;
});

function applyStage(): void {
  if (stageHost.value) stageHost.value.innerHTML = stageHtml.value;
  if (styleHost.value) {
    styleHost.value.innerHTML = '';
    if (rendered.value.css) {
      const style = document.createElement('style');
      style.textContent = rendered.value.css;
      styleHost.value.appendChild(style);
    }
  }
}

function clampIndex(): void {
  const n = items.value.length;
  if (n === 0) {
    currentIndex.value = 0;
    return;
  }
  if (currentIndex.value < 0) currentIndex.value = 0;
  if (currentIndex.value >= n) currentIndex.value = n - 1;
}

function next(): void {
  if (currentIndex.value < items.value.length - 1) currentIndex.value += 1;
}
function prev(): void {
  if (currentIndex.value > 0) currentIndex.value -= 1;
}
function first(): void {
  currentIndex.value = 0;
}
function last(): void {
  currentIndex.value = Math.max(0, items.value.length - 1);
}

function onKey(e: KeyboardEvent): void {
  // Only react when our root has focus (or a descendant) — otherwise
  // we'd hijack arrows from the Raw tab's CodeEditor.
  // Stream view: keys do nothing (scrolling is the natural nav).
  if (stream.value && !presenting.value) return;
  switch (e.key) {
    case 'ArrowRight':
    case 'PageDown':
      next();
      e.preventDefault();
      break;
    case ' ':
    case 'Spacebar': // legacy IE/Edge — harmless to keep
      if (e.shiftKey) prev();
      else next();
      e.preventDefault();
      break;
    case 'ArrowLeft':
    case 'PageUp':
    case 'Backspace':
      prev();
      e.preventDefault();
      break;
    case 'Home':
      first();
      e.preventDefault();
      break;
    case 'End':
      last();
      e.preventDefault();
      break;
  }
}

/**
 * Enter Fullscreen Present mode. Browser owns the Esc-to-exit and the
 * black background; we just request fullscreen on the root and force
 * single-slide-view (stream layout makes no sense fullscreen).
 */
async function enterPresenting(): Promise<void> {
  const el = rootEl.value;
  if (!el) return;
  stream.value = false;
  try {
    await el.requestFullscreen();
    el.focus();
  } catch (e) {
    console.warn('SlidesView: requestFullscreen failed', e);
  }
}

async function exitPresenting(): Promise<void> {
  if (document.fullscreenElement) {
    try {
      await document.exitFullscreen();
    } catch (e) {
      console.warn('SlidesView: exitFullscreen failed', e);
    }
  }
}

/** Keep our `presenting` flag in sync with the browser's fullscreen
 *  state — covers the case where the user presses Esc, which we don't
 *  intercept (the browser owns Esc in fullscreen). */
function onFullscreenChange(): void {
  presenting.value = document.fullscreenElement === rootEl.value;
}

onMounted(() => {
  marpit = new Marpit({
    markdown: { html: false, breaks: false },
    // inlineSVG wraps each slide in an SVG with a viewBox — the slide
    // scales naturally to whatever width the container offers,
    // without us having to compute a CSS transform.
    inlineSVG: true,
  });
  // Marpit ships no built-in theme — without one, <section> elements
  // have no dimensions and the deck renders empty. Register a minimal
  // light theme here; future spec §5.6 adds `dark` and `vance` (DaisyUI
  // token-driven).
  marpit.themeSet.default = marpit.themeSet.add(DEFAULT_THEME_CSS);
  applyStage();
  document.addEventListener('fullscreenchange', onFullscreenChange);
});

watch(() => resolvedDoc.value, () => {
  clampIndex();
  applyStage();
}, { deep: true });

watch([currentIndex, stream], () => {
  applyStage();
});

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', onFullscreenChange);
  if (document.fullscreenElement === rootEl.value) {
    void document.exitFullscreen();
  }
  marpit = null;
});
</script>

<template>
  <div
    ref="rootEl"
    :class="[
      'slides-view',
      `slides-view--${mode}`,
      presenting ? 'slides-view--presenting' : null,
    ]"
    tabindex="0"
    @keydown="onKey"
  >
    <div ref="styleHost" class="slides-style-host" aria-hidden="true" />
    <div
      ref="stageHost"
      :class="['slides-stage', stream ? 'slides-stage--stream' : 'slides-stage--single']"
    />
    <div v-if="items.length > 0 && mode === 'editor' && !presenting" class="slides-nav">
      <button
        type="button"
        class="slides-nav-btn"
        :disabled="stream || currentIndex === 0"
        :title="t('documents.slidesView.prev')"
        @click="prev"
      >←</button>
      <span class="slides-nav-index">
        {{ stream ? items.length : `${currentIndex + 1} / ${items.length}` }}
      </span>
      <button
        type="button"
        class="slides-nav-btn"
        :disabled="stream || currentIndex >= items.length - 1"
        :title="t('documents.slidesView.next')"
        @click="next"
      >→</button>
      <button
        type="button"
        class="slides-nav-toggle"
        :title="stream
          ? t('documents.slidesView.toggleSingle')
          : t('documents.slidesView.toggleStream')"
        @click="stream = !stream"
      >{{ stream ? t('documents.slidesView.modeStream') : t('documents.slidesView.modeSingle') }}</button>
      <button
        type="button"
        class="slides-nav-present"
        :title="t('documents.slidesView.present')"
        @click="enterPresenting"
      >▶ {{ t('documents.slidesView.presentLabel') }}</button>
    </div>
    <!-- Exit affordance while presenting — small floating button.
         Esc still works (browser-native fullscreen exit). -->
    <button
      v-if="presenting"
      type="button"
      class="slides-present-exit"
      :title="t('documents.slidesView.exit')"
      @click="exitPresenting"
    >✕</button>
    <div v-if="items.length === 0" class="slides-empty">
      {{ t('documents.slidesView.empty') }}
    </div>
  </div>
</template>

<style scoped>
.slides-view {
  position: relative;
  width: 100%;
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  outline: none;
  overflow: hidden;
}
.slides-view--editor {
  height: 70vh;
  min-height: 460px;
  display: flex;
  flex-direction: column;
}
.slides-view--embedded {
  height: 22rem;
  min-height: 16rem;
  border: none;
  background: transparent;
}

.slides-style-host {
  display: none;
}

.slides-stage {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 1rem;
}
.slides-stage :deep(.marpit) {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
}
/* In inlineSVG mode each slide is an <svg> with a 1280x720 viewBox —
 * cap the displayed width so it scales down on small panels but never
 * blows up beyond design width on huge displays. */
.slides-stage :deep(svg[data-marpit-svg]) {
  width: 100%;
  max-width: 1024px;
  height: auto;
  display: block;
  border-radius: 0.5rem;
}
.slides-stage--single :deep(svg[data-marpit-svg]) {
  box-shadow: 0 0.5rem 1.5rem hsl(var(--bc) / 0.15);
}
.slides-stage--stream {
  flex-direction: column;
  align-items: center;
  gap: 1.5rem;
}
.slides-stage--stream :deep(svg[data-marpit-svg]) {
  box-shadow: 0 0.25rem 0.75rem hsl(var(--bc) / 0.1);
}

.slides-nav {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding: 0.5rem;
  border-top: 1px solid hsl(var(--bc) / 0.1);
  background: hsl(var(--b2) / 0.5);
}
.slides-nav-btn,
.slides-nav-toggle,
.slides-nav-present {
  border: 1px solid hsl(var(--bc) / 0.2);
  background: hsl(var(--b1));
  border-radius: 0.375rem;
  padding: 0.25rem 0.75rem;
  font-size: 0.875rem;
  cursor: pointer;
  color: hsl(var(--bc));
}
.slides-nav-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.slides-nav-btn:hover:not(:disabled),
.slides-nav-toggle:hover,
.slides-nav-present:hover {
  background: hsl(var(--b2));
}
.slides-nav-present {
  margin-left: 0.5rem;
  background: hsl(var(--p) / 0.15);
  border-color: hsl(var(--p) / 0.4);
  color: hsl(var(--p));
  font-weight: 500;
}
.slides-nav-present:hover {
  background: hsl(var(--p) / 0.25);
}
.slides-nav-index {
  font-variant-numeric: tabular-nums;
  font-size: 0.875rem;
  opacity: 0.75;
  min-width: 4rem;
  text-align: center;
}

.slides-empty {
  padding: 2rem;
  text-align: center;
  font-size: 0.875rem;
  opacity: 0.6;
}

/* ── Present mode ─────────────────────────────────────────────
 * Browser fullscreen API hands us a screen-sized box. We turn the
 * stage into a black canvas with the slide centered. Esc exits via
 * the browser; we listen to fullscreenchange to update state.
 */
.slides-view--presenting {
  background: #000;
  border: none;
  border-radius: 0;
  height: 100vh;
  min-height: 0;
  display: flex;
  flex-direction: column;
  cursor: none;
}
.slides-view--presenting .slides-stage {
  flex: 1;
  padding: 0;
  align-items: center;
  justify-content: center;
}
.slides-view--presenting :deep(svg[data-marpit-svg]) {
  width: auto;
  max-width: 100vw;
  max-height: 100vh;
  height: auto;
  box-shadow: none;
  border-radius: 0;
  display: block;
}
.slides-present-exit {
  position: fixed;
  top: 1rem;
  right: 1rem;
  width: 2.25rem;
  height: 2.25rem;
  border-radius: 9999px;
  border: none;
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  font-size: 1.1rem;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s;
  z-index: 10;
}
.slides-view--presenting:hover .slides-present-exit,
.slides-present-exit:focus {
  opacity: 1;
}
.slides-present-exit:hover {
  background: rgba(255, 255, 255, 0.25);
}
</style>
