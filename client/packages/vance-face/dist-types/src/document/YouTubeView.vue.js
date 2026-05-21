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
function extractVideoId(raw) {
    const s = (raw ?? '').trim();
    if (!s)
        return null;
    // Bare ID — 11 base64-url chars per YouTube convention.
    if (/^[A-Za-z0-9_-]{11}$/.test(s))
        return s;
    try {
        const url = new URL(s);
        const host = url.hostname.replace(/^www\./, '').toLowerCase();
        // youtu.be/<id>
        if (host === 'youtu.be') {
            const id = url.pathname.replace(/^\//, '').split('/')[0];
            return /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
        }
        // youtube.com/watch?v=<id>
        if (host === 'youtube.com' || host === 'm.youtube.com' || host === 'youtube-nocookie.com') {
            const v = url.searchParams.get('v');
            if (v && /^[A-Za-z0-9_-]{11}$/.test(v))
                return v;
            // youtube.com/embed/<id> | shorts/<id> | v/<id>
            const segs = url.pathname.split('/').filter(Boolean);
            const idx = segs.findIndex((p) => p === 'embed' || p === 'shorts' || p === 'v');
            if (idx >= 0 && segs[idx + 1] && /^[A-Za-z0-9_-]{11}$/.test(segs[idx + 1])) {
                return segs[idx + 1];
            }
        }
    }
    catch {
        // not a URL — fall through
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