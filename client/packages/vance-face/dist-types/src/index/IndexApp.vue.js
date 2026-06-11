import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { clearLegacyAuth, clearRememberedLogin, getRememberedLogin, setRememberedLogin, } from '@vance/shared';
import { getActiveUiLevel, getSessionData, hydrateIdentity, isAccessAlive, isRefreshAlive, login, LoginError, rankOf, refreshAccessCookie, } from '@/platform';
import { setUiLocale } from '@/i18n';
import { EditorShell, VAlert, VButton, VCard, VCheckbox, VInput } from '@/components';
const { t } = useI18n();
const mode = ref('login');
const tenant = ref('default');
const username = ref('');
const password = ref('');
const submitting = ref(false);
const error = ref(null);
const autoLoginNotice = ref(null);
// "Remember user" — when checked, the (tenant, username) pair is
// persisted to localStorage and pre-fills the form on the next visit.
// Default-on once a remembered pair exists, default-off on a fresh
// browser. Never persists the password — that one stays out of
// localStorage on principle.
const rememberUser = ref(false);
// Active UI level for tile filtering. Mirrors the value the user has
// chosen in the profile page; the 'landing' branch reads it straight
// from the data cookie via {@link getActiveUiLevel}.
//
// Tiers:
//   * standard — chat / documents / inbox  (everyday)
//   * expert   — + scopes / tools / insights  (power user)
//   * admin    — + users  (tenant admin)
//
// Server-side authorization remains the authoritative gate; this
// just keeps the index page tidy for accounts that never need the
// power tiles.
const uiLevel = ref('standard');
const showExpertTiles = computed(() => rankOf(uiLevel.value) >= rankOf('expert'));
const showAdminTiles = computed(() => rankOf(uiLevel.value) >= rankOf('admin'));
onMounted(async () => {
    // Drop any stale localStorage tokens from the pre-cookie build.
    // Idempotent — no-op when already cleared.
    clearLegacyAuth();
    // Pre-fill the form from a previous "Remember user" tick. Tick the
    // checkbox by default once we know the user opted in last time —
    // unchecking it on the next login removes the entry.
    const remembered = getRememberedLogin();
    if (remembered) {
        tenant.value = remembered.tenant;
        username.value = remembered.username;
        rememberUser.value = true;
    }
    if (isAccessAlive()) {
        // Already-alive cookie path (user opened a fresh tab while
        // logged in) — the data cookie already carries the user's
        // language/theme/uiLevel, read straight from it.
        syncUiLocaleFromSession();
        uiLevel.value = getActiveUiLevel();
        redirectAfterLogin();
        return;
    }
    // Access cookie expired but the refresh cookie may still be alive.
    // Try a silent re-mint — on success, flash a one-second
    // "Sie wurden eingeloggt" notice before redirecting so the user
    // sees that the page loaded fresh.
    if (getSessionData() && isRefreshAlive()) {
        mode.value = 'auto-login';
        autoLoginNotice.value = t('login.autoLoginNotice');
        const ok = await refreshAccessCookie();
        if (ok && isAccessAlive()) {
            // Refresh re-issued the data cookie — pick the fresh values
            // up before the redirect mounts the next editor.
            hydrateIdentity();
            syncUiLocaleFromSession();
            uiLevel.value = getActiveUiLevel();
            window.setTimeout(redirectAfterLogin, 1000);
            return;
        }
        // Silent refresh failed — fall through to the login form.
        autoLoginNotice.value = null;
        mode.value = 'login';
        error.value = t('login.autoLoginFailed');
    }
});
/**
 * Pull the language from the data cookie and feed it into the i18n
 * instance. Called after every successful login or auto-login so the
 * {@code mode === 'landing'} editor list renders in the user's
 * chosen language.
 */
function syncUiLocaleFromSession() {
    const lang = getSessionData()?.webUiSettings?.['webui.language'];
    setUiLocale(lang ?? null);
}
async function onSubmit() {
    error.value = null;
    submitting.value = true;
    const trimmedTenant = tenant.value.trim();
    const trimmedUsername = username.value.trim();
    try {
        await login({
            tenant: trimmedTenant,
            username: trimmedUsername,
            password: password.value,
        });
        // Cookies are now set — read the fresh webui.* settings straight
        // from the data cookie before the redirect mounts the next editor.
        syncUiLocaleFromSession();
        uiLevel.value = getActiveUiLevel();
        // Persist or clear the (tenant, username) hint based on the
        // checkbox. Only a successful login is allowed to write — a
        // failed attempt mustn't leak its inputs into localStorage.
        if (rememberUser.value) {
            setRememberedLogin({ tenant: trimmedTenant, username: trimmedUsername });
        }
        else {
            clearRememberedLogin();
        }
        redirectAfterLogin();
    }
    catch (e) {
        if (e instanceof LoginError) {
            error.value = e.status === 401
                ? t('login.invalidCredentials')
                : t('login.loginFailedWithStatus', { status: e.status });
        }
        else {
            error.value = t('login.loginFailed');
        }
    }
    finally {
        submitting.value = false;
    }
}
function redirectAfterLogin() {
    const next = readNextParam();
    if (next) {
        window.location.replace(next);
        return;
    }
    // Default landing: keep this page mounted in 'landing' mode so the
    // user lands on the editor list rather than bouncing through a
    // separate URL.
    mode.value = 'landing';
}
/**
 * Pull and validate the `next` query parameter. We accept only same-origin
 * relative paths (must start with `/`, must not start with `//` or `\\`,
 * must not be a protocol-relative URL) — anything else is an open-redirect
 * risk and gets rejected to a `null` (which makes the caller fall back to
 * the default landing page).
 */
function readNextParam() {
    const raw = new URLSearchParams(window.location.search).get('next');
    if (!raw)
        return null;
    if (!raw.startsWith('/'))
        return null;
    if (raw.startsWith('//') || raw.startsWith('/\\'))
        return null;
    return raw;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['tile-row']} */ ;
// CSS variable injection 
// CSS variable injection end 
if (__VLS_ctx.mode === 'auto-login') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "hero min-h-screen bg-base-200" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "hero-content flex-col" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({
        ...{ class: "text-3xl font-bold mb-4 font-mono" },
    });
    const __VLS_0 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ class: "w-full max-w-md" },
    }));
    const __VLS_2 = __VLS_1({
        ...{ class: "w-full max-w-md" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_3.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-3 py-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: "loading loading-spinner loading-md" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.autoLoginNotice);
    var __VLS_3;
}
else if (__VLS_ctx.mode === 'login') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "hero min-h-screen bg-base-200" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "hero-content w-full max-w-md flex-col" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({
        ...{ class: "text-3xl font-bold mb-4 font-mono" },
    });
    const __VLS_4 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        ...{ class: "w-full" },
    }));
    const __VLS_6 = __VLS_5({
        ...{ class: "w-full" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
    __VLS_7.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
        ...{ onSubmit: (__VLS_ctx.onSubmit) },
        ...{ class: "flex flex-col gap-3" },
    });
    if (__VLS_ctx.error) {
        const __VLS_8 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
            variant: "error",
        }));
        const __VLS_10 = __VLS_9({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_9));
        __VLS_11.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.error);
        var __VLS_11;
    }
    const __VLS_12 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        modelValue: (__VLS_ctx.tenant),
        label: (__VLS_ctx.$t('login.tenant')),
        required: true,
        autocomplete: "organization",
        disabled: (__VLS_ctx.submitting),
    }));
    const __VLS_14 = __VLS_13({
        modelValue: (__VLS_ctx.tenant),
        label: (__VLS_ctx.$t('login.tenant')),
        required: true,
        autocomplete: "organization",
        disabled: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    const __VLS_16 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        modelValue: (__VLS_ctx.username),
        label: (__VLS_ctx.$t('login.username')),
        required: true,
        autocomplete: "username",
        disabled: (__VLS_ctx.submitting),
    }));
    const __VLS_18 = __VLS_17({
        modelValue: (__VLS_ctx.username),
        label: (__VLS_ctx.$t('login.username')),
        required: true,
        autocomplete: "username",
        disabled: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    const __VLS_20 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
        modelValue: (__VLS_ctx.password),
        type: "password",
        label: (__VLS_ctx.$t('login.password')),
        required: true,
        autocomplete: "current-password",
        disabled: (__VLS_ctx.submitting),
    }));
    const __VLS_22 = __VLS_21({
        modelValue: (__VLS_ctx.password),
        type: "password",
        label: (__VLS_ctx.$t('login.password')),
        required: true,
        autocomplete: "current-password",
        disabled: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
    const __VLS_24 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
        modelValue: (__VLS_ctx.rememberUser),
        label: (__VLS_ctx.$t('login.rememberUser')),
        disabled: (__VLS_ctx.submitting),
    }));
    const __VLS_26 = __VLS_25({
        modelValue: (__VLS_ctx.rememberUser),
        label: (__VLS_ctx.$t('login.rememberUser')),
        disabled: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    const __VLS_28 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
        type: "submit",
        variant: "primary",
        loading: (__VLS_ctx.submitting),
        ...{ class: "mt-2" },
        block: true,
    }));
    const __VLS_30 = __VLS_29({
        type: "submit",
        variant: "primary",
        loading: (__VLS_ctx.submitting),
        ...{ class: "mt-2" },
        block: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_29));
    __VLS_31.slots.default;
    (__VLS_ctx.$t('common.signIn'));
    var __VLS_31;
    var __VLS_7;
}
else {
    const __VLS_32 = {}.EditorShell;
    /** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
    // @ts-ignore
    const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
        title: (__VLS_ctx.$t('common.home')),
    }));
    const __VLS_34 = __VLS_33({
        title: (__VLS_ctx.$t('common.home')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
    __VLS_35.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "container mx-auto px-4 py-8 max-w-3xl" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
        ...{ class: "text-lg font-semibold mb-4" },
    });
    (__VLS_ctx.$t('index.sectionTitle'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "flex flex-col gap-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ class: "tile-row" },
        href: "/chat.html",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-semibold" },
    });
    (__VLS_ctx.$t('index.chat.title'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-70" },
    });
    (__VLS_ctx.$t('index.chat.description'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ class: "tile-row" },
        href: "/documents.html",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-semibold" },
    });
    (__VLS_ctx.$t('index.documents.title'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-70" },
    });
    (__VLS_ctx.$t('index.documents.description'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ class: "tile-row" },
        href: "/inbox.html",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-semibold" },
    });
    (__VLS_ctx.$t('index.inbox.title'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-70" },
    });
    (__VLS_ctx.$t('index.inbox.description'));
    if (__VLS_ctx.showExpertTiles) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
            ...{ class: "tile-row" },
            href: "/scopes.html",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "font-semibold" },
        });
        (__VLS_ctx.$t('index.scopes.title'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-70" },
        });
        (__VLS_ctx.$t('index.scopes.description'));
    }
    if (__VLS_ctx.showExpertTiles) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
            ...{ class: "tile-row" },
            href: "/tools.html",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "font-semibold" },
        });
        (__VLS_ctx.$t('index.tools.title'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-70" },
        });
        (__VLS_ctx.$t('index.tools.description'));
    }
    if (__VLS_ctx.showExpertTiles) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
            ...{ class: "tile-row" },
            href: "/insights.html",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "font-semibold" },
        });
        (__VLS_ctx.$t('index.insights.title'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-70" },
        });
        (__VLS_ctx.$t('index.insights.description'));
    }
    if (__VLS_ctx.showAdminTiles) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
            ...{ class: "tile-row" },
            href: "/users.html",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "font-semibold" },
        });
        (__VLS_ctx.$t('index.users.title'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-70" },
        });
        (__VLS_ctx.$t('index.users.description'));
    }
    if (__VLS_ctx.showAdminTiles) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
            ...{ class: "tile-row" },
            href: "/tool-templates.html",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "font-semibold" },
        });
        (__VLS_ctx.$t('toolTemplates.pageTitle'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-70" },
        });
        (__VLS_ctx.$t('toolTemplates.intro'));
    }
    var __VLS_35;
}
/** @type {__VLS_StyleScopedClasses['hero']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-screen']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hero-content']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['text-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['font-bold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-md']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['loading']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-spinner']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-md']} */ ;
/** @type {__VLS_StyleScopedClasses['hero']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-screen']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hero-content']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-md']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['text-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['font-bold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['container']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-8']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['tile-row']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['tile-row']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['tile-row']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['tile-row']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['tile-row']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['tile-row']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['tile-row']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['tile-row']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VCheckbox: VCheckbox,
            VInput: VInput,
            mode: mode,
            tenant: tenant,
            username: username,
            password: password,
            submitting: submitting,
            error: error,
            autoLoginNotice: autoLoginNotice,
            rememberUser: rememberUser,
            showExpertTiles: showExpertTiles,
            showAdminTiles: showAdminTiles,
            onSubmit: onSubmit,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=IndexApp.vue.js.map