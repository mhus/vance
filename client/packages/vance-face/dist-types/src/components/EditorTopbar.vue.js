import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { getTenantId, getUsername, isFacelift, requestAddAccount, requestBackToPicker, requestSwitchAccount, } from '@vance/shared';
import { logout as serverLogout, } from '@/platform';
import { setUiLocale } from '@/i18n';
import FookSupportModal from './FookSupportModal.vue';
import VanceLogo from './VanceLogo.vue';
const props = withDefaults(defineProps(), {
    breadcrumbs: () => [],
    helpOpen: false,
    titleClickable: false,
});
const emit = defineEmits();
const { t, locale } = useI18n();
const tenantId = computed(() => getTenantId());
const username = computed(() => getUsername());
const defaultConnectionTooltip = computed(() => {
    switch (props.connectionState) {
        case 'connected': return t('header.connection.connected');
        case 'occupied': return t('header.connection.occupied');
        case 'idle': return t('header.connection.idle');
        default: return '';
    }
});
function crumbText(c) {
    return typeof c === 'string' ? c : c.text;
}
function crumbOnClick(c) {
    return typeof c === 'string' ? null : (c.onClick ?? null);
}
const LANGUAGES = [
    { code: 'en', label: 'English' },
    { code: 'de', label: 'Deutsch' },
];
const currentLocale = computed(() => String(locale.value));
function selectLanguage(code) {
    // Transient switch for the current page only. The user's persistent
    // default lives on the profile page; switching here reverts on the
    // next page reload because the data cookie is the source of truth.
    setUiLocale(code);
}
async function logout() {
    const tenant = getTenantId();
    if (tenant) {
        await serverLogout(tenant);
    }
    window.location.href = '/index.html';
}
// Facelift wrapper bridges — these are no-ops in a plain browser
// (see @vance/shared/facelift). The menu only renders these entries
// when `isFacelift()` is true.
const inFacelift = computed(() => isFacelift());
function onSwitchAccount() {
    requestSwitchAccount();
}
function onManageAccounts() {
    requestBackToPicker();
}
function onAddAccount() {
    requestAddAccount();
}
function onToggleHelp() {
    if (!props.helpPath)
        return;
    emit('toggle-help');
}
// Fook bug/feature submission dialog — reachable from the user
// menu on every editor page. The modal owns its own state, we just
// flip the boolean.
const fookOpen = ref(false);
function openFook() {
    fookOpen.value = true;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    breadcrumbs: () => [],
    helpOpen: false,
    titleClickable: false,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['crumb-link']} */ ;
/** @type {__VLS_StyleScopedClasses['title-link']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
    ...{ class: "navbar bg-base-100 shadow-sm border-b border-base-300 px-4 gap-2" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
    href: "/index.html",
    ...{ class: "flex-none flex items-center gap-1.5 no-underline hover:opacity-80" },
    title: (__VLS_ctx.$t('common.backToHome')),
});
/** @type {[typeof VanceLogo, ]} */ ;
// @ts-ignore
const __VLS_0 = __VLS_asFunctionalComponent(VanceLogo, new VanceLogo({
    size: "sm",
    ...{ class: "text-primary" },
}));
const __VLS_1 = __VLS_0({
    size: "sm",
    ...{ class: "text-primary" },
}, ...__VLS_functionalComponentArgsRest(__VLS_0));
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-bold text-lg font-mono" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 flex items-center gap-2 text-sm" },
});
if (__VLS_ctx.titleClickable) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onPointerdown: () => { } },
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.titleClickable))
                    return;
                __VLS_ctx.emit('title-click');
            } },
        type: "button",
        ...{ class: "title-link font-semibold" },
    });
    (__VLS_ctx.title);
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-semibold" },
    });
    (__VLS_ctx.title);
}
if (__VLS_ctx.breadcrumbs.length) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-50" },
    });
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "opacity-70 truncate" },
});
for (const [crumb, idx] of __VLS_getVForSourceType((__VLS_ctx.breadcrumbs))) {
    (idx);
    if (__VLS_ctx.crumbOnClick(crumb)) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.crumbOnClick(crumb)))
                        return;
                    __VLS_ctx.crumbOnClick(crumb)?.();
                } },
            type: "button",
            ...{ class: "crumb-link" },
        });
        (__VLS_ctx.crumbText(crumb));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.crumbText(crumb));
    }
    if (idx < __VLS_ctx.breadcrumbs.length - 1) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "mx-1 opacity-40" },
        });
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-none flex items-center gap-3" },
});
if (__VLS_ctx.$slots['topbar-extra']) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center" },
    });
    var __VLS_3 = {};
}
if (__VLS_ctx.connectionState) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: ([
                'inline-block w-2.5 h-2.5 rounded-full',
                __VLS_ctx.connectionState === 'connected' ? 'bg-success' : '',
                __VLS_ctx.connectionState === 'idle' ? 'bg-base-content/40' : '',
                __VLS_ctx.connectionState === 'occupied' ? 'bg-error' : '',
            ]) },
        title: (__VLS_ctx.connectionTooltip ?? __VLS_ctx.defaultConnectionTooltip),
    });
}
if (__VLS_ctx.helpPath) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.onToggleHelp) },
        type: "button",
        ...{ class: "btn btn-ghost btn-sm btn-circle" },
        ...{ class: (__VLS_ctx.helpOpen ? 'btn-active' : '') },
        title: (__VLS_ctx.$t('header.help.toggle')),
        'aria-pressed': (__VLS_ctx.helpOpen),
    });
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "dropdown dropdown-end" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    tabindex: "0",
    role: "button",
    ...{ class: "btn btn-ghost btn-sm" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-mono text-xs opacity-70" },
});
(__VLS_ctx.tenantId);
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
(__VLS_ctx.username);
__VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
    tabindex: "0",
    ...{ class: "dropdown-content menu bg-base-100 rounded-box z-[1] mt-2 w-48 p-2 shadow" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
    ...{ class: "menu-title" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
(__VLS_ctx.$t('header.menu.languageHeader'));
for (const [lang] of __VLS_getVForSourceType((__VLS_ctx.LANGUAGES))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
        key: (lang.code),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (...[$event]) => {
                __VLS_ctx.selectLanguage(lang.code);
            } },
        ...{ class: ({ active: __VLS_ctx.currentLocale === lang.code }) },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono text-xs opacity-50 w-6" },
    });
    (lang.code.toUpperCase());
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (lang.label);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
    ...{ class: "divider-row" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
    ...{ class: "divider my-1" },
});
if (__VLS_ctx.inFacelift) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (__VLS_ctx.onSwitchAccount) },
    });
    (__VLS_ctx.$t('header.menu.switchAccount'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (__VLS_ctx.onManageAccounts) },
    });
    (__VLS_ctx.$t('header.menu.manageAccounts'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (__VLS_ctx.onAddAccount) },
    });
    (__VLS_ctx.$t('header.menu.addAccount'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
        ...{ class: "divider-row" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
        ...{ class: "divider my-1" },
    });
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
    ...{ onClick: (__VLS_ctx.openFook) },
});
(__VLS_ctx.$t('fook.menuLabel'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
    href: "/profile.html",
});
(__VLS_ctx.$t('common.profile'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
    ...{ onClick: (__VLS_ctx.logout) },
});
(__VLS_ctx.$t('common.signOut'));
/** @type {[typeof FookSupportModal, ]} */ ;
// @ts-ignore
const __VLS_5 = __VLS_asFunctionalComponent(FookSupportModal, new FookSupportModal({
    modelValue: (__VLS_ctx.fookOpen),
}));
const __VLS_6 = __VLS_5({
    modelValue: (__VLS_ctx.fookOpen),
}, ...__VLS_functionalComponentArgsRest(__VLS_5));
/** @type {__VLS_StyleScopedClasses['navbar']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-none']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['no-underline']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['text-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['font-bold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['title-link']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['crumb-link']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-40']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-none']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-block']} */ ;
/** @type {__VLS_StyleScopedClasses['w-2.5']} */ ;
/** @type {__VLS_StyleScopedClasses['h-2.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-circle']} */ ;
/** @type {__VLS_StyleScopedClasses['dropdown']} */ ;
/** @type {__VLS_StyleScopedClasses['dropdown-end']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['dropdown-content']} */ ;
/** @type {__VLS_StyleScopedClasses['menu']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-box']} */ ;
/** @type {__VLS_StyleScopedClasses['z-[1]']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-48']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow']} */ ;
/** @type {__VLS_StyleScopedClasses['menu-title']} */ ;
/** @type {__VLS_StyleScopedClasses['active']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['w-6']} */ ;
/** @type {__VLS_StyleScopedClasses['divider-row']} */ ;
/** @type {__VLS_StyleScopedClasses['divider']} */ ;
/** @type {__VLS_StyleScopedClasses['my-1']} */ ;
/** @type {__VLS_StyleScopedClasses['divider-row']} */ ;
/** @type {__VLS_StyleScopedClasses['divider']} */ ;
/** @type {__VLS_StyleScopedClasses['my-1']} */ ;
// @ts-ignore
var __VLS_4 = __VLS_3;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            FookSupportModal: FookSupportModal,
            VanceLogo: VanceLogo,
            emit: emit,
            tenantId: tenantId,
            username: username,
            defaultConnectionTooltip: defaultConnectionTooltip,
            crumbText: crumbText,
            crumbOnClick: crumbOnClick,
            LANGUAGES: LANGUAGES,
            currentLocale: currentLocale,
            selectLanguage: selectLanguage,
            logout: logout,
            inFacelift: inFacelift,
            onSwitchAccount: onSwitchAccount,
            onManageAccounts: onManageAccounts,
            onAddAccount: onAddAccount,
            onToggleHelp: onToggleHelp,
            fookOpen: fookOpen,
            openFook: openFook,
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
//# sourceMappingURL=EditorTopbar.vue.js.map