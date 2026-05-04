import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { setActiveLanguage } from '@/platform';
import { setUiLocale } from '@/i18n';
import { EditorShell, VAlert, VButton, VCard, VInput, VSelect } from '@components/index';
import { useProfile } from '@composables/useProfile';
const { t } = useI18n();
const { profile, loading, error, load, saveIdentity, saveSetting } = useProfile();
const titleDraft = ref('');
const emailDraft = ref('');
const languageDraft = ref('');
const identitySaved = ref(null);
const languageSaved = ref(null);
const LANGUAGE_KEY = 'webui.language';
// "Browser default" is the only label that needs translating; the
// other entries are language names already shown in their native
// form so users can recognise the option independent of the current
// UI language.
const languageOptions = computed(() => [
    { value: '', label: t('profile.preferences.languageBrowserDefault') },
    { value: 'de', label: 'Deutsch' },
    { value: 'en', label: 'English' },
    { value: 'fr', label: 'Français' },
    { value: 'es', label: 'Español' },
    { value: 'it', label: 'Italiano' },
]);
onMounted(load);
// Sync the form drafts whenever the underlying profile object changes —
// happens on initial load and after every successful save (the
// composable replaces the ref with the server response).
watch(profile, (current) => {
    if (!current)
        return;
    titleDraft.value = current.title ?? '';
    emailDraft.value = current.email ?? '';
    languageDraft.value = current.webUiSettings?.[LANGUAGE_KEY] ?? '';
}, { immediate: true });
async function onSaveIdentity() {
    identitySaved.value = null;
    await saveIdentity({
        title: titleDraft.value.trim(),
        email: emailDraft.value.trim(),
    }).catch(() => undefined);
    if (!error.value) {
        identitySaved.value = t('profile.identity.saved');
    }
}
async function onLanguageChanged(value) {
    languageSaved.value = null;
    const next = value ?? '';
    languageDraft.value = next;
    await saveSetting(LANGUAGE_KEY, next === '' ? null : next).catch(() => undefined);
    if (!error.value) {
        // Mirror the new value into sessionStorage so the rest of the
        // app picks it up via {@code getActiveLanguage} immediately —
        // no re-login, no page reload. The data cookie still carries
        // the login-time snapshot; sessionStorage wins for live reads.
        setActiveLanguage(next === '' ? null : next);
        // Switch the live i18n locale too so the page re-renders in the
        // newly chosen language right away.
        setUiLocale(next === '' ? null : next);
        languageSaved.value = t('profile.preferences.languageSaved');
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.$t('profile.pageTitle')),
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('profile.pageTitle')),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "container mx-auto px-4 py-8 max-w-3xl flex flex-col gap-6" },
});
if (__VLS_ctx.error) {
    const __VLS_5 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        variant: "error",
    }));
    const __VLS_7 = __VLS_6({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    __VLS_8.slots.default;
    (__VLS_ctx.error);
    var __VLS_8;
}
if (__VLS_ctx.loading && !__VLS_ctx.profile) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('profile.loading'));
}
else if (__VLS_ctx.profile) {
    const __VLS_9 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({}));
    const __VLS_11 = __VLS_10({}, ...__VLS_functionalComponentArgsRest(__VLS_10));
    __VLS_12.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
        ...{ class: "text-lg font-semibold mb-3" },
    });
    (__VLS_ctx.$t('profile.identity.title'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono" },
    });
    (__VLS_ctx.profile.tenantId);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "mx-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono" },
    });
    (__VLS_ctx.profile.name);
    const __VLS_13 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        modelValue: (__VLS_ctx.titleDraft),
        label: (__VLS_ctx.$t('profile.identity.displayName')),
        disabled: (__VLS_ctx.loading),
        placeholder: (__VLS_ctx.$t('profile.identity.displayNamePlaceholder')),
    }));
    const __VLS_15 = __VLS_14({
        modelValue: (__VLS_ctx.titleDraft),
        label: (__VLS_ctx.$t('profile.identity.displayName')),
        disabled: (__VLS_ctx.loading),
        placeholder: (__VLS_ctx.$t('profile.identity.displayNamePlaceholder')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
    const __VLS_17 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        modelValue: (__VLS_ctx.emailDraft),
        label: (__VLS_ctx.$t('profile.identity.email')),
        type: "email",
        disabled: (__VLS_ctx.loading),
        autocomplete: "email",
    }));
    const __VLS_19 = __VLS_18({
        modelValue: (__VLS_ctx.emailDraft),
        label: (__VLS_ctx.$t('profile.identity.email')),
        type: "email",
        disabled: (__VLS_ctx.loading),
        autocomplete: "email",
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-3" },
    });
    const __VLS_21 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.loading),
    }));
    const __VLS_23 = __VLS_22({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.loading),
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    let __VLS_25;
    let __VLS_26;
    let __VLS_27;
    const __VLS_28 = {
        onClick: (__VLS_ctx.onSaveIdentity)
    };
    __VLS_24.slots.default;
    (__VLS_ctx.$t('common.save'));
    var __VLS_24;
    if (__VLS_ctx.identitySaved) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-success text-sm" },
        });
        (__VLS_ctx.identitySaved);
    }
    var __VLS_12;
    const __VLS_29 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({}));
    const __VLS_31 = __VLS_30({}, ...__VLS_functionalComponentArgsRest(__VLS_30));
    __VLS_32.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
        ...{ class: "text-lg font-semibold mb-3" },
    });
    (__VLS_ctx.$t('profile.preferences.title'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-sm opacity-70 mb-3" },
    });
    (__VLS_ctx.$t('profile.preferences.description'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3" },
    });
    const __VLS_33 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.languageDraft),
        options: (__VLS_ctx.languageOptions),
        label: (__VLS_ctx.$t('profile.preferences.language')),
        disabled: (__VLS_ctx.loading),
    }));
    const __VLS_35 = __VLS_34({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.languageDraft),
        options: (__VLS_ctx.languageOptions),
        label: (__VLS_ctx.$t('profile.preferences.language')),
        disabled: (__VLS_ctx.loading),
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    let __VLS_37;
    let __VLS_38;
    let __VLS_39;
    const __VLS_40 = {
        'onUpdate:modelValue': (__VLS_ctx.onLanguageChanged)
    };
    var __VLS_36;
    if (__VLS_ctx.languageSaved) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-success text-sm" },
        });
        (__VLS_ctx.languageSaved);
    }
    var __VLS_32;
    const __VLS_41 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({}));
    const __VLS_43 = __VLS_42({}, ...__VLS_functionalComponentArgsRest(__VLS_42));
    __VLS_44.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
        ...{ class: "text-lg font-semibold mb-3" },
    });
    (__VLS_ctx.$t('profile.teams.title'));
    if (__VLS_ctx.profile.teams.length === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-sm opacity-70" },
        });
        (__VLS_ctx.$t('profile.teams.empty'));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "flex flex-col gap-2" },
        });
        for (const [team] of __VLS_getVForSourceType((__VLS_ctx.profile.teams))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (team.id || team.name),
                ...{ class: "flex items-center justify-between" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "font-semibold" },
            });
            (team.title || team.name);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-70 font-mono" },
            });
            (team.name);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center gap-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs opacity-70" },
            });
            (team.members.length === 1
                ? __VLS_ctx.$t('profile.teams.memberCountOne', { count: team.members.length })
                : __VLS_ctx.$t('profile.teams.memberCountOther', { count: team.members.length }));
            if (!team.enabled) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "badge badge-warning badge-sm" },
                    title: (__VLS_ctx.$t('profile.teams.disabledTooltip')),
                });
                (__VLS_ctx.$t('profile.teams.disabled'));
            }
        }
    }
    var __VLS_44;
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['container']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-8']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-6']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VInput: VInput,
            VSelect: VSelect,
            profile: profile,
            loading: loading,
            error: error,
            titleDraft: titleDraft,
            emailDraft: emailDraft,
            languageDraft: languageDraft,
            identitySaved: identitySaved,
            languageSaved: languageSaved,
            languageOptions: languageOptions,
            onSaveIdentity: onSaveIdentity,
            onLanguageChanged: onLanguageChanged,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ProfileApp.vue.js.map