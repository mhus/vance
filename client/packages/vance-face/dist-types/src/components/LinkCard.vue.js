import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { fetchLinkPreview } from '@vance/shared';
const props = defineProps();
const cardRoot = ref(null);
const preview = ref(null);
const loading = ref(false);
const failed = ref(false);
let observer = null;
/**
 * Resolves the favicon URL for the link's host via Google's s2
 * service. CORS-stable across browsers, gives a usable 64px icon
 * for nearly every domain that has one in its HTML head. Falls
 * back to {@code null} only when the URL doesn't parse.
 */
function faviconUrl() {
    try {
        const host = new URL(props.url).hostname;
        if (!host)
            return null;
        return `https://www.google.com/s2/favicons?domain=${encodeURIComponent(host)}&sz=64`;
    }
    catch {
        return null;
    }
}
/**
 * Picks the best thumbnail for the card: OG image first (full
 * 96x72 box), falling back to a 48x48 favicon. Cards without OG
 * data still get a visual anchor on the left — closer to the
 * Slack / Telegram look the user expects.
 */
const thumbnail = computed(() => {
    const ogImage = preview.value?.image;
    if (ogImage && ogImage.trim().length > 0) {
        return { url: ogImage, kind: 'image' };
    }
    const fav = faviconUrl();
    return fav ? { url: fav, kind: 'icon' } : null;
});
async function loadPreview() {
    if (preview.value || loading.value)
        return;
    loading.value = true;
    try {
        preview.value = await fetchLinkPreview(props.url);
    }
    catch (e) {
        // Network/auth errors are non-fatal for previews — render the
        // muted fallback rather than throwing into the parent. The
        // inline link itself is still clickable.
        console.warn('LinkCard: preview fetch failed', props.url, e);
        failed.value = true;
    }
    finally {
        loading.value = false;
    }
}
onMounted(() => {
    if (!cardRoot.value)
        return;
    // IntersectionObserver: only fetch when the card scrolls into
    // view. Long chat histories with many links shouldn't trigger
    // dozens of unused fetches.
    observer = new IntersectionObserver((entries) => {
        for (const entry of entries) {
            if (entry.isIntersecting) {
                void loadPreview();
                observer?.disconnect();
                observer = null;
                break;
            }
        }
    }, { rootMargin: '100px' });
    observer.observe(cardRoot.value);
});
onBeforeUnmount(() => {
    observer?.disconnect();
    observer = null;
});
function hostnameLabel() {
    try {
        return new URL(props.url).hostname.replace(/^www\./, '');
    }
    catch {
        return props.url;
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['link-card__inner']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__inner--muted']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ref: "cardRoot",
    ...{ class: "link-card" },
});
/** @type {typeof __VLS_ctx.cardRoot} */ ;
if (__VLS_ctx.preview && __VLS_ctx.preview.ok) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        href: (__VLS_ctx.preview.finalUrl ?? __VLS_ctx.url),
        target: "_blank",
        rel: "noopener noreferrer",
        ...{ class: "link-card__inner" },
    });
    if (__VLS_ctx.thumbnail) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.img)({
            src: (__VLS_ctx.thumbnail.url),
            alt: (__VLS_ctx.preview.title ?? ''),
            ...{ class: (__VLS_ctx.thumbnail.kind === 'image' ? 'link-card__image' : 'link-card__icon') },
            loading: "lazy",
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__body" },
    });
    if (__VLS_ctx.preview.siteName) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "link-card__site" },
        });
        (__VLS_ctx.preview.siteName);
    }
    if (__VLS_ctx.preview.title) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "link-card__title" },
        });
        (__VLS_ctx.preview.title);
    }
    if (__VLS_ctx.preview.description) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "link-card__desc" },
        });
        (__VLS_ctx.preview.description);
    }
}
else if (__VLS_ctx.preview && !__VLS_ctx.preview.ok && __VLS_ctx.preview.failureReason === 'access_restricted') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        href: (__VLS_ctx.preview.finalUrl ?? __VLS_ctx.url),
        target: "_blank",
        rel: "noopener noreferrer",
        ...{ class: "link-card__inner link-card__inner--restricted" },
        title: (`Host refused preview (HTTP ${__VLS_ctx.preview.status}); open in browser`),
    });
    if (__VLS_ctx.thumbnail) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.img)({
            src: (__VLS_ctx.thumbnail.url),
            alt: "",
            ...{ class: (__VLS_ctx.thumbnail.kind === 'image' ? 'link-card__image' : 'link-card__icon') },
            loading: "lazy",
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__body" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__site" },
    });
    (__VLS_ctx.preview.siteName ?? __VLS_ctx.hostnameLabel());
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__title link-card__title--restricted" },
    });
}
else if (__VLS_ctx.preview && !__VLS_ctx.preview.ok) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__inner link-card__inner--muted" },
        title: (__VLS_ctx.preview.failureReason ?? ''),
    });
    if (__VLS_ctx.thumbnail) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.img)({
            src: (__VLS_ctx.thumbnail.url),
            alt: "",
            ...{ class: (__VLS_ctx.thumbnail.kind === 'image' ? 'link-card__image' : 'link-card__icon') },
            loading: "lazy",
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__body" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__site" },
    });
    (__VLS_ctx.preview.siteName ?? __VLS_ctx.hostnameLabel());
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__title link-card__title--muted" },
    });
}
else if (__VLS_ctx.failed) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__inner link-card__inner--muted" },
    });
    if (__VLS_ctx.thumbnail) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.img)({
            src: (__VLS_ctx.thumbnail.url),
            alt: "",
            ...{ class: (__VLS_ctx.thumbnail.kind === 'image' ? 'link-card__image' : 'link-card__icon') },
            loading: "lazy",
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__body" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__site" },
    });
    (__VLS_ctx.hostnameLabel());
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__title link-card__title--muted" },
    });
}
else if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__inner link-card__inner--loading" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__body" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__site" },
    });
    (__VLS_ctx.hostnameLabel());
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "link-card__title link-card__title--muted" },
    });
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
        ...{ class: "link-card__placeholder" },
    });
}
/** @type {__VLS_StyleScopedClasses['link-card']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__inner']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__body']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__site']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__title']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__desc']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__inner']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__inner--restricted']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__body']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__site']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__title']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__title--restricted']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__inner']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__inner--muted']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__body']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__site']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__title']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__title--muted']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__inner']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__inner--muted']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__body']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__site']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__title']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__title--muted']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__inner']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__inner--loading']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__body']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__site']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__title']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__title--muted']} */ ;
/** @type {__VLS_StyleScopedClasses['link-card__placeholder']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            cardRoot: cardRoot,
            preview: preview,
            loading: loading,
            failed: failed,
            thumbnail: thumbnail,
            hostnameLabel: hostnameLabel,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=LinkCard.vue.js.map