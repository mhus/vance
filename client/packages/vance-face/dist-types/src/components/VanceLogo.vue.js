const props = withDefaults(defineProps(), { size: 'md' });
const sizeClass = {
    sm: 'h-5', // ~20px — topbar inline
    md: 'h-8', // ~32px — section headers
    lg: 'h-16', // ~64px — login form
    xl: 'h-32', // ~128px — splash / hero
};
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ size: 'md' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.svg, __VLS_intrinsicElements.svg)({
    xmlns: "http://www.w3.org/2000/svg",
    viewBox: "0 0 468 536",
    ...{ class: (['inline-block align-middle', __VLS_ctx.sizeClass[props.size]]) },
    'aria-label': "Vance",
    role: "img",
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.path)({
    fill: "currentColor",
    transform: "matrix(1 0 0 -1 -32 503)",
    d: "M200 476 53 465V437C53 437 83 440 99 440C117 440 123 426 123 409C123 389 116 365 113 354L93 278C82 236 71 183 71 135C71 58 100 -9 200 -9C380 -9 479 206 479 358C479 429 448 479 403 479C382 479 365 464 365 437C365 393 426 369 426 299C426 206 368 55 231 55C170 55 153 99 153 151C153 196 166 249 175 284L222 476Z",
});
/** @type {__VLS_StyleScopedClasses['inline-block']} */ ;
/** @type {__VLS_StyleScopedClasses['align-middle']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            sizeClass: sizeClass,
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
//# sourceMappingURL=VanceLogo.vue.js.map