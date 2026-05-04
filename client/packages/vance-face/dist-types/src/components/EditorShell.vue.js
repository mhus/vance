import { computed, onMounted, onBeforeUnmount } from 'vue';
import { useI18n } from 'vue-i18n';
import { getSessionData, getTenantId, getUsername, isAccessAlive, isRefreshAlive, logout as serverLogout, refreshAccessCookie, setActiveLanguage, } from '@vance/shared';
import { setUiLocale } from '@/i18n';
const props = withDefaults(defineProps(), {
    breadcrumbs: () => [],
    wideRightPanel: false,
    fullHeight: false,
});
function crumbText(c) {
    return typeof c === 'string' ? c : c.text;
}
function crumbOnClick(c) {
    return typeof c === 'string' ? null : (c.onClick ?? null);
}
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
const LANGUAGES = [
    { code: 'en', label: 'English' },
    { code: 'de', label: 'Deutsch' },
];
const currentLocale = computed(() => String(locale.value));
function selectLanguage(code) {
    setActiveLanguage(code);
    setUiLocale(code);
}
async function logout() {
    const tenant = getTenantId();
    if (tenant) {
        await serverLogout(tenant);
    }
    window.location.href = '/index.html';
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
});
onBeforeUnmount(() => {
    if (expiryTimer != null) {
        window.clearInterval(expiryTimer);
        expiryTimer = null;
    }
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    breadcrumbs: () => [],
    wideRightPanel: false,
    fullHeight: false,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['crumb-link']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-screen overflow-hidden flex flex-col bg-base-200" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
    ...{ class: "navbar bg-base-100 shadow-sm border-b border-base-300 px-4 gap-2" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
    href: "/index.html",
    ...{ class: "flex-none font-bold text-lg font-mono no-underline hover:opacity-80" },
    title: (__VLS_ctx.$t('common.backToHome')),
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 flex items-center gap-2 text-sm" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-semibold" },
});
(__VLS_ctx.title);
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
    var __VLS_0 = {};
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
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 flex min-h-0" },
});
if (__VLS_ctx.$slots.sidebar) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ class: "w-64 shrink-0 border-r border-base-300 bg-base-100 overflow-y-auto" },
    });
    var __VLS_2 = {};
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.main, __VLS_intrinsicElements.main)({
    ...{ class: (['flex-1 min-w-0 min-h-0', __VLS_ctx.fullHeight ? 'overflow-hidden' : 'overflow-y-auto']) },
});
var __VLS_4 = {};
if (__VLS_ctx.$slots['right-panel']) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ class: ([
                'shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto',
                __VLS_ctx.wideRightPanel ? 'w-[40rem]' : 'w-80',
            ]) },
    });
    var __VLS_6 = {};
}
/** @type {__VLS_StyleScopedClasses['h-screen']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['navbar']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-none']} */ ;
/** @type {__VLS_StyleScopedClasses['font-bold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['no-underline']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
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
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['w-64']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-r']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-l']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
// @ts-ignore
var __VLS_1 = __VLS_0, __VLS_3 = __VLS_2, __VLS_5 = __VLS_4, __VLS_7 = __VLS_6;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            crumbText: crumbText,
            crumbOnClick: crumbOnClick,
            tenantId: tenantId,
            username: username,
            defaultConnectionTooltip: defaultConnectionTooltip,
            LANGUAGES: LANGUAGES,
            currentLocale: currentLocale,
            selectLanguage: selectLanguage,
            logout: logout,
        };
    },
    __typeProps: {},
    props: {},
});
const __VLS_component = (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
    props: {},
});
export default {};
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=EditorShell.vue.js.map