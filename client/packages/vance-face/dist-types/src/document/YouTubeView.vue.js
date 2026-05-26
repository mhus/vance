import { computed } from 'vue';
const props = withDefaults(defineProps(), {
    mode: 'inline',
    meta: () => ({}),
});
const videoId = computed(() => extractVideoId(props.content));
const startSeconds = computed(() => {
    const raw = props.meta.start;
    if (!raw)
        return 0;
    const n = parseInt(raw, 10);
    return Number.isFinite(n) && n > 0 ? n : 0;
});
const embedSrc = computed(() => {
    if (!videoId.value)
        return '';
    const params = new URLSearchParams();
    params.set('rel', '0');
    params.set('modestbranding', '1');
    if (startSeconds.value > 0)
        params.set('start', String(startSeconds.value));
    return `https://www.youtube-nocookie.com/embed/${videoId.value}?${params.toString()}`;
});
const watchUrl = computed(() => {
    if (!videoId.value)
        return '';
    const base = `https://www.youtube.com/watch?v=${videoId.value}`;
    return startSeconds.value > 0 ? `${base}&t=${startSeconds.value}s` : base;
});
const caption = computed(() => props.meta.title ?? '');
const ID_RE = /^[A-Za-z0-9_-]{11}$/;
function videoIdFromUrl(token) {
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
            if (v && ID_RE.test(v))
                return v;
            const segs = url.pathname.split('/').filter(Boolean);
            const idx = segs.findIndex((p) => p === 'embed' || p === 'shorts' || p === 'v');
            if (idx >= 0 && segs[idx + 1] && ID_RE.test(segs[idx + 1])) {
                return segs[idx + 1];
            }
        }
    }
    catch {
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
function extractVideoId(raw) {
    const s = (raw ?? '').trim();
    if (!s)
        return null;
    if (ID_RE.test(s))
        return s;
    const direct = videoIdFromUrl(s);
    if (direct)
        return direct;
    const lines = s.split(/\r?\n/);
    for (const rawLine of lines) {
        const line = rawLine.trim();
        if (!line)
            continue;
        if (ID_RE.test(line))
            return line;
        const fromUrl = videoIdFromUrl(line);
        if (fromUrl)
            return fromUrl;
        // YAML-/key-value-style: id: <id> | url: <url>. Allow quoted
        // and unquoted values; tolerate `:` or `=` as separator.
        const m = line.match(/^\s*(id|videoid|video_id|v|url|videourl|video_url|link)\s*[:=]\s*["']?(.+?)["']?\s*$/i);
        if (m) {
            const val = m[2].trim();
            if (ID_RE.test(val))
                return val;
            const fromVal = videoIdFromUrl(val);
            if (fromVal)
                return fromVal;
        }
    }
    return null;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    mode: 'inline',
    meta: () => ({}),
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['yt-view__source']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "yt-view" },
});
if (!__VLS_ctx.videoId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "yt-view__error" },
    });
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "yt-view__frame-wrap" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.iframe)({
        ...{ class: "yt-view__frame" },
        src: (__VLS_ctx.embedSrc),
        loading: "lazy",
        referrerpolicy: "strict-origin-when-cross-origin",
        allow: "accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share",
        allowfullscreen: true,
        title: "YouTube video",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "yt-view__meta" },
    });
    if (__VLS_ctx.caption) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.caption);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        href: (__VLS_ctx.watchUrl),
        target: "_blank",
        rel: "noopener noreferrer",
        ...{ class: "yt-view__source" },
    });
}
/** @type {__VLS_StyleScopedClasses['yt-view']} */ ;
/** @type {__VLS_StyleScopedClasses['yt-view__error']} */ ;
/** @type {__VLS_StyleScopedClasses['yt-view__frame-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['yt-view__frame']} */ ;
/** @type {__VLS_StyleScopedClasses['yt-view__meta']} */ ;
/** @type {__VLS_StyleScopedClasses['yt-view__source']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            videoId: videoId,
            embedSrc: embedSrc,
            watchUrl: watchUrl,
            caption: caption,
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
//# sourceMappingURL=YouTubeView.vue.js.map