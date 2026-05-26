import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Marpit } from '@marp-team/marpit';
import { parseSlides } from './slidesCodec';
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
const props = withDefaults(defineProps(), { mode: 'editor' });
const { t } = useI18n();
const styleHost = ref(null);
const stageHost = ref(null);
const currentIndex = ref(0);
const stream = ref(false);
const presenting = ref(false);
const rootEl = ref(null);
let marpit = null;
const resolvedDoc = computed(() => {
    if (props.mode === 'editor') {
        return props.doc ?? emptyDoc();
    }
    // embedded
    const d = props.document;
    if (!d || !d.inlineText)
        return emptyDoc();
    const mime = d.mimeType ?? 'text/markdown';
    try {
        return parseSlides(d.inlineText, mime);
    }
    catch (e) {
        console.warn('SlidesView: failed to parse embedded document', e);
        return emptyDoc();
    }
});
const items = computed(() => resolvedDoc.value.items);
function emptyDoc() {
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
function buildMarpitMarkdown(doc) {
    const directives = [];
    const h = doc.slides;
    if (h.paginate !== undefined)
        directives.push(`paginate: ${h.paginate}`);
    if (h.header !== undefined)
        directives.push(`header: ${JSON.stringify(h.header)}`);
    if (h.footer !== undefined)
        directives.push(`footer: ${JSON.stringify(h.footer)}`);
    if (h.defaultClass !== undefined)
        directives.push(`class: ${JSON.stringify(h.defaultClass)}`);
    // `theme` and `aspect` are Vance-level concepts — Marpit picks them
    // up through theme name + CSS rules. Wire-through is §6 work.
    const frontMatter = directives.length > 0
        ? `---\n${directives.join('\n')}\n---\n\n`
        : '';
    return frontMatter + doc.items.join('\n\n---\n\n');
}
const rendered = computed(() => {
    if (!marpit || items.value.length === 0) {
        return { htmlArray: [], css: '' };
    }
    try {
        const md = buildMarpitMarkdown(resolvedDoc.value);
        const result = marpit.render(md, { htmlAsArray: true });
        return { htmlArray: result.html, css: result.css };
    }
    catch (e) {
        console.warn('SlidesView: Marpit render failed', e);
        return { htmlArray: [], css: '' };
    }
});
/** Stage HTML — the actual DOM injected into the slides container.
 *  Wraps in `<div class="marpit">` so Marpit's generated CSS rules
 *  (which are all prefixed with `.marpit`) apply. */
const stageHtml = computed(() => {
    const arr = rendered.value.htmlArray;
    if (arr.length === 0)
        return '';
    if (stream.value) {
        return `<div class="marpit">${arr.join('')}</div>`;
    }
    const idx = Math.min(Math.max(currentIndex.value, 0), arr.length - 1);
    return `<div class="marpit">${arr[idx] ?? ''}</div>`;
});
function applyStage() {
    if (stageHost.value)
        stageHost.value.innerHTML = stageHtml.value;
    if (styleHost.value) {
        styleHost.value.innerHTML = '';
        if (rendered.value.css) {
            const style = document.createElement('style');
            style.textContent = rendered.value.css;
            styleHost.value.appendChild(style);
        }
    }
}
function clampIndex() {
    const n = items.value.length;
    if (n === 0) {
        currentIndex.value = 0;
        return;
    }
    if (currentIndex.value < 0)
        currentIndex.value = 0;
    if (currentIndex.value >= n)
        currentIndex.value = n - 1;
}
function next() {
    if (currentIndex.value < items.value.length - 1)
        currentIndex.value += 1;
}
function prev() {
    if (currentIndex.value > 0)
        currentIndex.value -= 1;
}
function first() {
    currentIndex.value = 0;
}
function last() {
    currentIndex.value = Math.max(0, items.value.length - 1);
}
function onKey(e) {
    // Only react when our root has focus (or a descendant) — otherwise
    // we'd hijack arrows from the Raw tab's CodeEditor.
    // Stream view: keys do nothing (scrolling is the natural nav).
    if (stream.value && !presenting.value)
        return;
    switch (e.key) {
        case 'ArrowRight':
        case 'PageDown':
            next();
            e.preventDefault();
            break;
        case ' ':
        case 'Spacebar': // legacy IE/Edge — harmless to keep
            if (e.shiftKey)
                prev();
            else
                next();
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
async function enterPresenting() {
    const el = rootEl.value;
    if (!el)
        return;
    stream.value = false;
    try {
        await el.requestFullscreen();
        el.focus();
    }
    catch (e) {
        console.warn('SlidesView: requestFullscreen failed', e);
    }
}
async function exitPresenting() {
    if (document.fullscreenElement) {
        try {
            await document.exitFullscreen();
        }
        catch (e) {
            console.warn('SlidesView: exitFullscreen failed', e);
        }
    }
}
/** Keep our `presenting` flag in sync with the browser's fullscreen
 *  state — covers the case where the user presses Esc, which we don't
 *  intercept (the browser owns Esc in fullscreen). */
function onFullscreenChange() {
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
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ mode: 'editor' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['slides-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-stage--stream']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-present']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-present']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-present']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-view--presenting']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-view--presenting']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-view--presenting']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-present-exit']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-present-exit']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-present-exit']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ onKeydown: (__VLS_ctx.onKey) },
    ref: "rootEl",
    ...{ class: ([
            'slides-view',
            `slides-view--${__VLS_ctx.mode}`,
            __VLS_ctx.presenting ? 'slides-view--presenting' : null,
        ]) },
    tabindex: "0",
});
/** @type {typeof __VLS_ctx.rootEl} */ ;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
    ref: "styleHost",
    ...{ class: "slides-style-host" },
    'aria-hidden': "true",
});
/** @type {typeof __VLS_ctx.styleHost} */ ;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
    ref: "stageHost",
    ...{ class: (['slides-stage', __VLS_ctx.stream ? 'slides-stage--stream' : 'slides-stage--single']) },
});
/** @type {typeof __VLS_ctx.stageHost} */ ;
if (__VLS_ctx.items.length > 0 && __VLS_ctx.mode === 'editor' && !__VLS_ctx.presenting) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "slides-nav" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.prev) },
        type: "button",
        ...{ class: "slides-nav-btn" },
        disabled: (__VLS_ctx.stream || __VLS_ctx.currentIndex === 0),
        title: (__VLS_ctx.t('documents.slidesView.prev')),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "slides-nav-index" },
    });
    (__VLS_ctx.stream ? __VLS_ctx.items.length : `${__VLS_ctx.currentIndex + 1} / ${__VLS_ctx.items.length}`);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.next) },
        type: "button",
        ...{ class: "slides-nav-btn" },
        disabled: (__VLS_ctx.stream || __VLS_ctx.currentIndex >= __VLS_ctx.items.length - 1),
        title: (__VLS_ctx.t('documents.slidesView.next')),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.items.length > 0 && __VLS_ctx.mode === 'editor' && !__VLS_ctx.presenting))
                    return;
                __VLS_ctx.stream = !__VLS_ctx.stream;
            } },
        type: "button",
        ...{ class: "slides-nav-toggle" },
        title: (__VLS_ctx.stream
            ? __VLS_ctx.t('documents.slidesView.toggleSingle')
            : __VLS_ctx.t('documents.slidesView.toggleStream')),
    });
    (__VLS_ctx.stream ? __VLS_ctx.t('documents.slidesView.modeStream') : __VLS_ctx.t('documents.slidesView.modeSingle'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.enterPresenting) },
        type: "button",
        ...{ class: "slides-nav-present" },
        title: (__VLS_ctx.t('documents.slidesView.present')),
    });
    (__VLS_ctx.t('documents.slidesView.presentLabel'));
}
if (__VLS_ctx.presenting) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.exitPresenting) },
        type: "button",
        ...{ class: "slides-present-exit" },
        title: (__VLS_ctx.t('documents.slidesView.exit')),
    });
}
if (__VLS_ctx.items.length === 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "slides-empty" },
    });
    (__VLS_ctx.t('documents.slidesView.empty'));
}
/** @type {__VLS_StyleScopedClasses['slides-view']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-style-host']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-index']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-nav-present']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-present-exit']} */ ;
/** @type {__VLS_StyleScopedClasses['slides-empty']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            t: t,
            styleHost: styleHost,
            stageHost: stageHost,
            currentIndex: currentIndex,
            stream: stream,
            presenting: presenting,
            rootEl: rootEl,
            items: items,
            next: next,
            prev: prev,
            onKey: onKey,
            enterPresenting: enterPresenting,
            exitPresenting: exitPresenting,
        };
    },
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=SlidesView.vue.js.map