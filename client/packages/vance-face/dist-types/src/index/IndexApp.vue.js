import { onMounted, ref } from 'vue';
import { getJwt, isTokenValid, login, LoginError } from '@vance/shared';
import { EditorShell, VAlert, VButton, VCard, VInput } from '@/components';
const mode = ref('login');
const tenant = ref('default');
const username = ref('');
const password = ref('');
const submitting = ref(false);
const error = ref(null);
onMounted(() => {
    const jwt = getJwt();
    if (jwt && isTokenValid(jwt)) {
        const next = readNextParam();
        if (next) {
            window.location.replace(next);
            return;
        }
        mode.value = 'landing';
    }
});
async function onSubmit() {
    error.value = null;
    submitting.value = true;
    try {
        await login({
            tenant: tenant.value.trim(),
            username: username.value.trim(),
            password: password.value,
        });
        const next = readNextParam();
        window.location.replace(next ?? '/index.html');
    }
    catch (e) {
        error.value = e instanceof LoginError ? e.message : 'Login failed.';
    }
    finally {
        submitting.value = false;
    }
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
if (__VLS_ctx.mode === 'login') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "hero min-h-screen bg-base-200" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "hero-content w-full max-w-md flex-col" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({
        ...{ class: "text-3xl font-bold mb-4 font-mono" },
    });
    const __VLS_0 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ class: "w-full" },
    }));
    const __VLS_2 = __VLS_1({
        ...{ class: "w-full" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_3.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
        ...{ onSubmit: (__VLS_ctx.onSubmit) },
        ...{ class: "flex flex-col gap-3" },
    });
    if (__VLS_ctx.error) {
        const __VLS_4 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
            variant: "error",
        }));
        const __VLS_6 = __VLS_5({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_5));
        __VLS_7.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.error);
        var __VLS_7;
    }
    const __VLS_8 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        modelValue: (__VLS_ctx.tenant),
        label: "Tenant",
        required: true,
        autocomplete: "organization",
        disabled: (__VLS_ctx.submitting),
    }));
    const __VLS_10 = __VLS_9({
        modelValue: (__VLS_ctx.tenant),
        label: "Tenant",
        required: true,
        autocomplete: "organization",
        disabled: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    const __VLS_12 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        modelValue: (__VLS_ctx.username),
        label: "Username",
        required: true,
        autocomplete: "username",
        disabled: (__VLS_ctx.submitting),
    }));
    const __VLS_14 = __VLS_13({
        modelValue: (__VLS_ctx.username),
        label: "Username",
        required: true,
        autocomplete: "username",
        disabled: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    const __VLS_16 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        modelValue: (__VLS_ctx.password),
        type: "password",
        label: "Password",
        required: true,
        autocomplete: "current-password",
        disabled: (__VLS_ctx.submitting),
    }));
    const __VLS_18 = __VLS_17({
        modelValue: (__VLS_ctx.password),
        type: "password",
        label: "Password",
        required: true,
        autocomplete: "current-password",
        disabled: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    const __VLS_20 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
        type: "submit",
        variant: "primary",
        loading: (__VLS_ctx.submitting),
        ...{ class: "mt-2" },
        block: true,
    }));
    const __VLS_22 = __VLS_21({
        type: "submit",
        variant: "primary",
        loading: (__VLS_ctx.submitting),
        ...{ class: "mt-2" },
        block: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
    __VLS_23.slots.default;
    var __VLS_23;
    var __VLS_3;
}
else {
    const __VLS_24 = {}.EditorShell;
    /** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
        title: "Home",
    }));
    const __VLS_26 = __VLS_25({
        title: "Home",
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    __VLS_27.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "container mx-auto px-4 py-8 max-w-3xl" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
        ...{ class: "text-lg font-semibold mb-4" },
    });
    const __VLS_28 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({}));
    const __VLS_30 = __VLS_29({}, ...__VLS_functionalComponentArgsRest(__VLS_29));
    __VLS_31.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "flex flex-col gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
        ...{ class: "flex items-center justify-between gap-4" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-semibold" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-70" },
    });
    const __VLS_32 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
        variant: "primary",
        size: "sm",
        href: "/document-editor.html",
    }));
    const __VLS_34 = __VLS_33({
        variant: "primary",
        size: "sm",
        href: "/document-editor.html",
    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
    __VLS_35.slots.default;
    var __VLS_35;
    var __VLS_31;
    var __VLS_27;
}
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
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
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
            VInput: VInput,
            mode: mode,
            tenant: tenant,
            username: username,
            password: password,
            submitting: submitting,
            error: error,
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