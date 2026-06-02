import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { VAlert, VButton, VEmptyState } from '@vance/components';
import { documentContentUrl } from '@vance/shared';
import { getSlideshow, rebuildSlideshow } from './api';
const props = defineProps();
const show = ref(null);
const loading = ref(true);
const error = ref(null);
const currentIndex = ref(0);
const autoplaying = ref(false);
const fullscreen = ref(false);
const viewportEl = ref(null);
let autoplayTimer = null;
const currentSlide = computed(() => {
    if (!show.value || show.value.slides.length === 0)
        return null;
    return show.value.slides[currentIndex.value] ?? null;
});
// Aspect ratio for the viewport. Manifest hint wins; otherwise use
// the current slide's pixel dimensions so the chrome doesn't shift
// between slides of identical shape. Fallback 16:9.
const aspectRatio = computed(() => {
    if (show.value?.aspectRatio)
        return show.value.aspectRatio.replace(':', '/');
    const slide = currentSlide.value;
    if (slide?.width && slide?.height)
        return `${slide.width} / ${slide.height}`;
    return '16 / 9';
});
const slideUrl = computed(() => currentSlide.value ? documentContentUrl(currentSlide.value.documentId) : null);
async function load() {
    loading.value = true;
    error.value = null;
    try {
        show.value = await getSlideshow(props.projectId, props.folder);
        if (currentIndex.value >= show.value.slides.length) {
            currentIndex.value = Math.max(0, show.value.slides.length - 1);
        }
    }
    catch (e) {
        error.value = `Could not load slideshow: ${e.message}`;
    }
    finally {
        loading.value = false;
    }
}
async function rebuild() {
    try {
        await rebuildSlideshow(props.projectId, props.folder);
        await load();
    }
    catch (e) {
        error.value = `Rebuild failed: ${e.message}`;
    }
}
function next() {
    if (!show.value || show.value.slides.length === 0)
        return;
    currentIndex.value = (currentIndex.value + 1) % show.value.slides.length;
}
function prev() {
    if (!show.value || show.value.slides.length === 0)
        return;
    currentIndex.value =
        (currentIndex.value - 1 + show.value.slides.length) % show.value.slides.length;
}
function jump(idx) {
    if (!show.value)
        return;
    currentIndex.value = Math.max(0, Math.min(idx, show.value.slides.length - 1));
}
function toggleAutoplay() {
    if (autoplaying.value) {
        stopAutoplay();
    }
    else {
        startAutoplay();
    }
}
function startAutoplay() {
    if (!show.value || show.value.autoplaySeconds <= 0)
        return;
    stopAutoplay();
    autoplaying.value = true;
    autoplayTimer = window.setInterval(() => next(), show.value.autoplaySeconds * 1000);
}
function stopAutoplay() {
    if (autoplayTimer != null) {
        window.clearInterval(autoplayTimer);
        autoplayTimer = null;
    }
    autoplaying.value = false;
}
async function toggleFullscreen() {
    if (!document.fullscreenElement) {
        if (viewportEl.value) {
            await viewportEl.value.requestFullscreen?.();
        }
    }
    else {
        await document.exitFullscreen?.();
    }
}
function onFullscreenChange() {
    fullscreen.value = document.fullscreenElement != null;
}
function onKeydown(e) {
    // Don't hijack typing in an input (search field somewhere, etc.).
    const target = e.target;
    if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
        return;
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
            if (show.value)
                jump(show.value.slides.length - 1);
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
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col h-full bg-base-100" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center justify-between p-4 border-b border-base-300" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({
    ...{ class: "text-xl font-semibold" },
});
(__VLS_ctx.title ?? __VLS_ctx.folder);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-sm text-base-content/60 mt-0.5" },
});
(__VLS_ctx.folder);
if (__VLS_ctx.show && __VLS_ctx.show.slides.length > 0) {
    (__VLS_ctx.currentIndex + 1);
    (__VLS_ctx.show.slides.length);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2 items-center" },
});
if (__VLS_ctx.show && __VLS_ctx.show.autoplaySeconds > 0) {
    const __VLS_0 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ 'onClick': {} },
        size: "sm",
        variant: (__VLS_ctx.autoplaying ? 'primary' : 'ghost'),
    }));
    const __VLS_2 = __VLS_1({
        ...{ 'onClick': {} },
        size: "sm",
        variant: (__VLS_ctx.autoplaying ? 'primary' : 'ghost'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        onClick: (__VLS_ctx.toggleAutoplay)
    };
    __VLS_3.slots.default;
    (__VLS_ctx.autoplaying ? '⏸ Pause' : `▶ Play (${__VLS_ctx.show.autoplaySeconds}s)`);
    var __VLS_3;
}
const __VLS_8 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}));
const __VLS_10 = __VLS_9({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
let __VLS_12;
let __VLS_13;
let __VLS_14;
const __VLS_15 = {
    onClick: (__VLS_ctx.toggleFullscreen)
};
__VLS_11.slots.default;
(__VLS_ctx.fullscreen ? 'Exit fullscreen' : 'Fullscreen (F)');
var __VLS_11;
const __VLS_16 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}));
const __VLS_18 = __VLS_17({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_17));
let __VLS_20;
let __VLS_21;
let __VLS_22;
const __VLS_23 = {
    onClick: (__VLS_ctx.load)
};
__VLS_19.slots.default;
var __VLS_19;
const __VLS_24 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}));
const __VLS_26 = __VLS_25({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_25));
let __VLS_28;
let __VLS_29;
let __VLS_30;
const __VLS_31 = {
    onClick: (__VLS_ctx.rebuild)
};
__VLS_27.slots.default;
var __VLS_27;
if (__VLS_ctx.error) {
    const __VLS_32 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
        variant: "error",
        ...{ class: "m-4" },
    }));
    const __VLS_34 = __VLS_33({
        variant: "error",
        ...{ class: "m-4" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
    __VLS_35.slots.default;
    (__VLS_ctx.error);
    var __VLS_35;
}
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-8 text-base-content/70" },
    });
}
else if (__VLS_ctx.show && __VLS_ctx.show.slides.length === 0) {
    const __VLS_36 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
        ...{ class: "m-4" },
        headline: "No slides",
        body: "Upload images into this folder, then click 'Rebuild index' to refresh the slideshow.",
    }));
    const __VLS_38 = __VLS_37({
        ...{ class: "m-4" },
        headline: "No slides",
        body: "Upload images into this folder, then click 'Rebuild index' to refresh the slideshow.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_37));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ onClick: (__VLS_ctx.next) },
        ref: "viewportEl",
        ...{ class: "flex-1 flex flex-col items-center justify-center bg-neutral text-neutral-content p-6 gap-4 relative overflow-hidden" },
    });
    /** @type {typeof __VLS_ctx.viewportEl} */ ;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 w-full flex items-center justify-center min-h-0" },
    });
    if (__VLS_ctx.slideUrl) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.img)({
            src: (__VLS_ctx.slideUrl),
            alt: (__VLS_ctx.currentSlide?.caption ?? ''),
            ...{ style: ({ aspectRatio: __VLS_ctx.aspectRatio }) },
            ...{ class: "max-w-full max-h-full object-contain shadow-2xl" },
            draggable: "false",
        });
    }
    if (__VLS_ctx.currentSlide?.caption) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-center text-sm opacity-80 max-w-2xl" },
        });
        (__VLS_ctx.currentSlide.caption);
    }
    if (__VLS_ctx.show && __VLS_ctx.show.slides.length > 1) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.prev) },
            ...{ class: "absolute left-4 top-1/2 -translate-y-1/2 bg-base-100/20 hover:bg-base-100/40 text-base-content rounded-full w-12 h-12 flex items-center justify-center text-2xl" },
            title: "Previous slide (←)",
        });
    }
    if (__VLS_ctx.show && __VLS_ctx.show.slides.length > 1) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.next) },
            ...{ class: "absolute right-4 top-1/2 -translate-y-1/2 bg-base-100/20 hover:bg-base-100/40 text-base-content rounded-full w-12 h-12 flex items-center justify-center text-2xl" },
            title: "Next slide (→)",
        });
    }
    if (__VLS_ctx.show && __VLS_ctx.show.slides.length > 1 && __VLS_ctx.show.slides.length <= 30) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex gap-1.5" },
        });
        for (const [_, idx] of __VLS_getVForSourceType((__VLS_ctx.show.slides))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.show && __VLS_ctx.show.slides.length === 0))
                            return;
                        if (!(__VLS_ctx.show && __VLS_ctx.show.slides.length > 1 && __VLS_ctx.show.slides.length <= 30))
                            return;
                        __VLS_ctx.jump(idx);
                    } },
                key: (idx),
                ...{ class: "w-2 h-2 rounded-full transition-all" },
                ...{ class: (idx === __VLS_ctx.currentIndex ? 'bg-primary w-6' : 'bg-base-content/30 hover:bg-base-content/60') },
                title: (`Slide ${idx + 1}`),
            });
        }
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['m-4']} */ ;
/** @type {__VLS_StyleScopedClasses['p-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['m-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-neutral']} */ ;
/** @type {__VLS_StyleScopedClasses['text-neutral-content']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['object-contain']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['absolute']} */ ;
/** @type {__VLS_StyleScopedClasses['left-4']} */ ;
/** @type {__VLS_StyleScopedClasses['top-1/2']} */ ;
/** @type {__VLS_StyleScopedClasses['-translate-y-1/2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100/20']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-100/40']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['w-12']} */ ;
/** @type {__VLS_StyleScopedClasses['h-12']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['absolute']} */ ;
/** @type {__VLS_StyleScopedClasses['right-4']} */ ;
/** @type {__VLS_StyleScopedClasses['top-1/2']} */ ;
/** @type {__VLS_StyleScopedClasses['-translate-y-1/2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100/20']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-100/40']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['w-12']} */ ;
/** @type {__VLS_StyleScopedClasses['h-12']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['w-2']} */ ;
/** @type {__VLS_StyleScopedClasses['h-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-all']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            show: show,
            loading: loading,
            error: error,
            currentIndex: currentIndex,
            autoplaying: autoplaying,
            fullscreen: fullscreen,
            viewportEl: viewportEl,
            currentSlide: currentSlide,
            aspectRatio: aspectRatio,
            slideUrl: slideUrl,
            load: load,
            rebuild: rebuild,
            next: next,
            prev: prev,
            jump: jump,
            toggleAutoplay: toggleAutoplay,
            toggleFullscreen: toggleFullscreen,
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
//# sourceMappingURL=SlideshowApp.vue.js.map