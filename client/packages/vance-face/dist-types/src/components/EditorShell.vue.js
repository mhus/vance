import { computed, onMounted, onBeforeUnmount, ref, useSlots, watch } from 'vue';
import { getSessionData, isAccessAlive, isRefreshAlive, refreshAccessCookie, } from '@/platform';
import { useHelp } from '@/composables/useHelp';
import EditorTopbar from './EditorTopbar.vue';
import MarkdownView from './MarkdownView.vue';
import NotificationToasts from '@/notification/NotificationToasts.vue';
const props = withDefaults(defineProps(), {
    breadcrumbs: () => [],
    wideRightPanel: false,
    fullHeight: false,
    helpOpen: false,
    titleClickable: false,
    focusModel: 'off',
});
const emit = defineEmits();
const focusZone = defineModel('focusZone', { default: 'main' });
const showHelp = ref(false);
const help = useHelp();
watch(() => props.helpPath, (path) => {
    showHelp.value = props.helpOpen && !!path;
    if (path && showHelp.value && help.content.value == null) {
        void help.load(path);
    }
}, { immediate: true });
function toggleHelp() {
    if (!props.helpPath)
        return;
    showHelp.value = !showHelp.value;
    if (showHelp.value && help.content.value == null && !help.loading.value) {
        void help.load(props.helpPath);
    }
}
/**
 * Body layout — CSS Grid with up to three columns (sidebar, main,
 * right) and up to two rows (main row, footer row). Columns appear
 * only when their slot is filled (or the help drawer is open, which
 * borrows the right-panel column). Footer is full-width in row 2.
 *
 * <p>Actual track widths live in CSS variables in the scoped
 * {@code <style>} block below — driven by the {@code data-focus} /
 * {@code data-wide-right} / {@code data-help-open} attributes set on
 * the grid container. See `specification/web-ui.md` §7.2.1.
 */
const slots = useSlots();
const hasSidebarSlot = computed(() => {
    if (props.showSidebar === false)
        return false;
    return !!slots.sidebar;
});
const hasRightCell = computed(() => {
    if (props.showRightPanel === false && !showHelp.value)
        return false;
    return showHelp.value || !!slots['right-panel'];
});
const hasFooterSlot = computed(() => {
    if (props.showFooter === false)
        return false;
    return !!slots.footer;
});
const gridTemplateColumns = computed(() => {
    const cols = [];
    if (hasSidebarSlot.value)
        cols.push('var(--shell-sidebar-w)');
    cols.push('1fr');
    if (hasRightCell.value)
        cols.push('var(--shell-right-w)');
    return cols.join(' ');
});
const gridTemplateRows = computed(() => {
    return hasFooterSlot.value ? '1fr var(--shell-footer-h)' : '1fr';
});
/**
 * Encodes the current focus zone as the {@code data-focus} attribute
 * on the grid root — but only when focus-model is active. CSS rules
 * key off this attribute for column widths, backgrounds, and reclaim
 * handle visibility.
 */
const effectiveFocusZone = computed(() => props.focusModel === 'auto' ? (focusZone.value ?? null) : null);
function onZonePointerdown(zone) {
    if (props.focusModel !== 'auto')
        return;
    focusZone.value = zone;
}
function onFooterFocusin() {
    if (props.focusModel !== 'auto')
        return;
    focusZone.value = 'footer';
}
/**
 * Escape returns focus to {@code 'main'} — the implicit "home" zone.
 * Only active when focus-model is on, so editors that don't opt in
 * are unaffected by global keyboard captures.
 */
function onGlobalKeydown(ev) {
    if (props.focusModel !== 'auto')
        return;
    if (ev.key === 'Escape' && focusZone.value !== 'main') {
        focusZone.value = 'main';
    }
}
/**
 * Pointerdown outside the editor-shell grid (notably the topbar) has
 * no zone it belongs to — default to {@code 'main'} so the main zone
 * is always the implicit "home" when nothing else asked for focus.
 */
function onGlobalPointerdown(ev) {
    if (props.focusModel !== 'auto')
        return;
    const target = ev.target;
    if (!target)
        return;
    if (!target.closest('.editor-shell-grid')) {
        focusZone.value = 'main';
    }
}
/**
 * Per-page-load access-cookie check. The shell is rendered on every
 * editor (apart from the login page itself), so guarding here is
 * equivalent to "guard on every page load".
 *
 * <p>If the access cookie has expired we try a silent refresh via the
 * still-alive refresh cookie. On failure we redirect to the login
 * page with the current URL as the {@code next} parameter so the user
 * comes back to where they were after re-authenticating.
 *
 * <p>A timer keeps polling every 60 seconds — long-running editor
 * sessions (chat tab left open over the lunch break) get the same
 * guard mid-session, not only on initial mount.
 */
let expiryTimer = null;
async function guardAccessCookie() {
    if (isAccessAlive())
        return;
    if (getSessionData() && isRefreshAlive()) {
        const ok = await refreshAccessCookie();
        if (ok && isAccessAlive())
            return;
    }
    redirectToLogin();
}
function redirectToLogin() {
    const currentUrl = window.location.pathname + window.location.search + window.location.hash;
    const next = encodeURIComponent(currentUrl);
    window.location.href = `/index.html?next=${next}`;
}
onMounted(() => {
    void guardAccessCookie();
    expiryTimer = window.setInterval(() => {
        void guardAccessCookie();
    }, 60_000);
    // Listeners always attached; the handlers themselves no-op when
    // focusModel !== 'auto'. Cheaper than churning add/remove on prop
    // changes and lets a parent flip focusModel at runtime cleanly.
    window.addEventListener('keydown', onGlobalKeydown);
    window.addEventListener('pointerdown', onGlobalPointerdown);
});
onBeforeUnmount(() => {
    if (expiryTimer != null) {
        window.clearInterval(expiryTimer);
        expiryTimer = null;
    }
    window.removeEventListener('keydown', onGlobalKeydown);
    window.removeEventListener('pointerdown', onGlobalPointerdown);
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    breadcrumbs: () => [],
    wideRightPanel: false,
    fullHeight: false,
    helpOpen: false,
    titleClickable: false,
    focusModel: 'off',
});
const __VLS_defaults = {
    'focusZone': 'main',
};
const __VLS_modelEmit = defineEmits();
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['zone-sidebar']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['zone-right']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['zone-footer']} */ ;
/** @type {__VLS_StyleScopedClasses['reclaim-handle']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-screen h-dvh overflow-hidden flex flex-col bg-base-200" },
});
/** @type {[typeof EditorTopbar, typeof EditorTopbar, ]} */ ;
// @ts-ignore
const __VLS_0 = __VLS_asFunctionalComponent(EditorTopbar, new EditorTopbar({
    ...{ 'onToggleHelp': {} },
    ...{ 'onTitleClick': {} },
    title: (__VLS_ctx.title),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    connectionState: (__VLS_ctx.connectionState),
    connectionTooltip: (__VLS_ctx.connectionTooltip),
    helpPath: (__VLS_ctx.helpPath),
    helpOpen: (__VLS_ctx.showHelp),
    titleClickable: (__VLS_ctx.titleClickable),
}));
const __VLS_1 = __VLS_0({
    ...{ 'onToggleHelp': {} },
    ...{ 'onTitleClick': {} },
    title: (__VLS_ctx.title),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    connectionState: (__VLS_ctx.connectionState),
    connectionTooltip: (__VLS_ctx.connectionTooltip),
    helpPath: (__VLS_ctx.helpPath),
    helpOpen: (__VLS_ctx.showHelp),
    titleClickable: (__VLS_ctx.titleClickable),
}, ...__VLS_functionalComponentArgsRest(__VLS_0));
let __VLS_3;
let __VLS_4;
let __VLS_5;
const __VLS_6 = {
    onToggleHelp: (__VLS_ctx.toggleHelp)
};
const __VLS_7 = {
    onTitleClick: (...[$event]) => {
        __VLS_ctx.emit('title-click');
    }
};
__VLS_2.slots.default;
if (__VLS_ctx.$slots['topbar-extra']) {
    {
        const { 'topbar-extra': __VLS_thisSlot } = __VLS_2.slots;
        var __VLS_8 = {};
    }
}
var __VLS_2;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "editor-shell-grid flex-1 min-h-0" },
    'data-focus': (__VLS_ctx.effectiveFocusZone),
    'data-wide-right': (__VLS_ctx.focusModel === 'off' && __VLS_ctx.wideRightPanel ? '' : null),
    'data-help-open': (__VLS_ctx.showHelp ? '' : null),
    ...{ style: ({ gridTemplateColumns: __VLS_ctx.gridTemplateColumns, gridTemplateRows: __VLS_ctx.gridTemplateRows }) },
});
if (__VLS_ctx.hasSidebarSlot) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ onPointerdown: (...[$event]) => {
                if (!(__VLS_ctx.hasSidebarSlot))
                    return;
                __VLS_ctx.onZonePointerdown('sidebar');
            } },
        ...{ class: "zone zone-sidebar min-w-0 min-h-0 border-r border-base-300 overflow-y-auto" },
    });
    var __VLS_10 = {};
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.main, __VLS_intrinsicElements.main)({
    ...{ onPointerdown: (...[$event]) => {
            __VLS_ctx.onZonePointerdown('main');
        } },
    ...{ class: ([
            'zone zone-main min-w-0 min-h-0',
            __VLS_ctx.fullHeight ? 'overflow-hidden' : 'overflow-y-auto',
        ]) },
});
var __VLS_12 = {};
if (__VLS_ctx.hasRightCell) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ onPointerdown: (...[$event]) => {
                if (!(__VLS_ctx.hasRightCell))
                    return;
                __VLS_ctx.onZonePointerdown('right');
            } },
        ...{ class: "zone zone-right min-w-0 min-h-0 border-l border-base-300 overflow-y-auto" },
    });
    if (__VLS_ctx.showHelp) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-4 flex flex-col gap-3 h-full" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "text-xs uppercase opacity-60" },
        });
        (__VLS_ctx.$t('header.help.title'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.toggleHelp) },
            type: "button",
            ...{ class: "btn btn-ghost btn-xs btn-circle" },
            title: (__VLS_ctx.$t('header.help.close')),
        });
        if (__VLS_ctx.help.loading.value) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60" },
            });
            (__VLS_ctx.$t('header.help.loading'));
        }
        else if (__VLS_ctx.help.error.value) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60" },
            });
            (__VLS_ctx.$t('header.help.unavailable', { error: __VLS_ctx.help.error.value }));
        }
        else if (!__VLS_ctx.help.content.value) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60" },
            });
            (__VLS_ctx.$t('header.help.empty'));
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "overflow-y-auto pr-2" },
            });
            /** @type {[typeof MarkdownView, ]} */ ;
            // @ts-ignore
            const __VLS_14 = __VLS_asFunctionalComponent(MarkdownView, new MarkdownView({
                source: (__VLS_ctx.help.content.value),
            }));
            const __VLS_15 = __VLS_14({
                source: (__VLS_ctx.help.content.value),
            }, ...__VLS_functionalComponentArgsRest(__VLS_14));
        }
    }
    else {
        var __VLS_17 = {};
    }
}
if (__VLS_ctx.hasFooterSlot) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.footer, __VLS_intrinsicElements.footer)({
        ...{ onFocusin: (__VLS_ctx.onFooterFocusin) },
        ...{ class: "zone zone-footer col-span-full min-w-0 min-h-0 border-t border-base-300 overflow-x-clip overflow-y-visible" },
    });
    var __VLS_19 = {};
}
if (__VLS_ctx.focusModel === 'auto') {
    if (__VLS_ctx.hasSidebarSlot) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.focusModel === 'auto'))
                        return;
                    if (!(__VLS_ctx.hasSidebarSlot))
                        return;
                    __VLS_ctx.focusZone = 'sidebar';
                } },
            type: "button",
            ...{ class: "reclaim-handle reclaim-handle-sidebar" },
            ...{ class: ({ 'reclaim-handle--hidden': __VLS_ctx.focusZone === 'sidebar' }) },
            'aria-label': "Expand sidebar",
        });
    }
    if (__VLS_ctx.hasRightCell) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.focusModel === 'auto'))
                        return;
                    if (!(__VLS_ctx.hasRightCell))
                        return;
                    __VLS_ctx.focusZone = 'right';
                } },
            type: "button",
            ...{ class: "reclaim-handle reclaim-handle-right" },
            ...{ class: ({ 'reclaim-handle--hidden': __VLS_ctx.focusZone === 'right' }) },
            'aria-label': "Expand right panel",
        });
    }
}
/** @type {[typeof NotificationToasts, ]} */ ;
// @ts-ignore
const __VLS_21 = __VLS_asFunctionalComponent(NotificationToasts, new NotificationToasts({}));
const __VLS_22 = __VLS_21({}, ...__VLS_functionalComponentArgsRest(__VLS_21));
/** @type {__VLS_StyleScopedClasses['h-screen']} */ ;
/** @type {__VLS_StyleScopedClasses['h-dvh']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['editor-shell-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['zone']} */ ;
/** @type {__VLS_StyleScopedClasses['zone-sidebar']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-r']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['zone']} */ ;
/** @type {__VLS_StyleScopedClasses['zone-main']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['zone']} */ ;
/** @type {__VLS_StyleScopedClasses['zone-right']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-l']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-circle']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['pr-2']} */ ;
/** @type {__VLS_StyleScopedClasses['zone']} */ ;
/** @type {__VLS_StyleScopedClasses['zone-footer']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-x-clip']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-visible']} */ ;
/** @type {__VLS_StyleScopedClasses['reclaim-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['reclaim-handle-sidebar']} */ ;
/** @type {__VLS_StyleScopedClasses['reclaim-handle--hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['reclaim-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['reclaim-handle-right']} */ ;
/** @type {__VLS_StyleScopedClasses['reclaim-handle--hidden']} */ ;
// @ts-ignore
var __VLS_9 = __VLS_8, __VLS_11 = __VLS_10, __VLS_13 = __VLS_12, __VLS_18 = __VLS_17, __VLS_20 = __VLS_19;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorTopbar: EditorTopbar,
            MarkdownView: MarkdownView,
            NotificationToasts: NotificationToasts,
            emit: emit,
            focusZone: focusZone,
            showHelp: showHelp,
            help: help,
            toggleHelp: toggleHelp,
            hasSidebarSlot: hasSidebarSlot,
            hasRightCell: hasRightCell,
            hasFooterSlot: hasFooterSlot,
            gridTemplateColumns: gridTemplateColumns,
            gridTemplateRows: gridTemplateRows,
            effectiveFocusZone: effectiveFocusZone,
            onZonePointerdown: onZonePointerdown,
            onFooterFocusin: onFooterFocusin,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
const __VLS_component = (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default {};
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=EditorShell.vue.js.map