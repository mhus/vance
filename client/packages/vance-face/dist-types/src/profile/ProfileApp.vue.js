import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { applyTheme, } from '@/platform';
import { setUiLocale } from '@/i18n';
import { EditorShell, VAlert, VButton, VCard, VInput, VSelect } from '@components/index';
import { useProfile } from '@composables/useProfile';
const { t } = useI18n();
const { profile, loading, error, load, saveIdentity, saveSetting, deleteSetting } = useProfile();
const titleDraft = ref('');
const emailDraft = ref('');
const languageDraft = ref('');
const chatLanguageDraft = ref('');
const themeDraft = ref('auto');
const uiLevelDraft = ref('standard');
const identitySaved = ref(null);
const languageSaved = ref(null);
const chatLanguageSaved = ref(null);
const themeSaved = ref(null);
const uiLevelSaved = ref(null);
const LANGUAGE_KEY = 'webui.language';
const CHAT_LANGUAGE_KEY = 'chat.language';
const THEME_KEY = 'webui.theme';
const UI_LEVEL_KEY = 'webui.uiLevel';
function asTheme(value) {
    // Accept anything stored on the server but normalise unknown
    // values back to "auto" rather than rendering an empty selector.
    return value === 'light' || value === 'dark' ? value : 'auto';
}
function asUiLevel(value) {
    return value === 'expert' || value === 'admin' ? value : 'standard';
}
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
// Chat language ("not set" → no user-level value → cascade falls
// through to the tenant or to LanguageResolver.DEFAULT_LANGUAGE).
// The native language names match the webui.language dropdown so
// users get the same picker pattern across both fields.
const chatLanguageOptions = computed(() => [
    { value: '', label: t('profile.preferences.chatLanguageNotSet') },
    { value: 'de', label: 'Deutsch' },
    { value: 'en', label: 'English' },
    { value: 'fr', label: 'Français' },
    { value: 'es', label: 'Español' },
    { value: 'it', label: 'Italiano' },
]);
const themeOptions = computed(() => [
    { value: 'auto', label: t('profile.preferences.themeAuto') },
    { value: 'light', label: t('profile.preferences.themeLight') },
    { value: 'dark', label: t('profile.preferences.themeDark') },
]);
const uiLevelOptions = computed(() => [
    { value: 'standard', label: t('profile.preferences.uiLevelStandard') },
    { value: 'expert', label: t('profile.preferences.uiLevelExpert') },
    { value: 'admin', label: t('profile.preferences.uiLevelAdmin') },
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
    chatLanguageDraft.value = current.webUiSettings?.[CHAT_LANGUAGE_KEY] ?? '';
    themeDraft.value = asTheme(current.webUiSettings?.[THEME_KEY]);
    uiLevelDraft.value = asUiLevel(current.webUiSettings?.[UI_LEVEL_KEY]);
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
        // The PUT response refreshes the data cookie server-side, so
        // {@link getActiveLanguage} sees the new value on the next read.
        // Switch the live i18n locale here so the page re-renders in the
        // newly chosen language right away.
        setUiLocale(next === '' ? null : next);
        languageSaved.value = t('profile.preferences.languageSaved');
    }
}
async function onChatLanguageChanged(value) {
    chatLanguageSaved.value = null;
    const next = value ?? '';
    chatLanguageDraft.value = next;
    // "Not set" → DELETE the user-scope setting so the cascade falls
    // through to the tenant default (or LanguageResolver.DEFAULT_LANGUAGE).
    // Any concrete code is a PUT — same path as the other prefs.
    if (next === '') {
        await deleteSetting(CHAT_LANGUAGE_KEY).catch(() => undefined);
    }
    else {
        await saveSetting(CHAT_LANGUAGE_KEY, next).catch(() => undefined);
    }
    if (!error.value) {
        chatLanguageSaved.value = t('profile.preferences.chatLanguageSaved');
    }
}
async function onThemeChanged(value) {
    themeSaved.value = null;
    const next = asTheme(value);
    themeDraft.value = next;
    // "auto" is encoded server-side as the absence of the setting —
    // pass null so the brain DELETEs it. light / dark are stored as-is.
    await saveSetting(THEME_KEY, next === 'auto' ? null : next).catch(() => undefined);
    if (!error.value) {
        // PUT refreshes the data cookie; flip the DOM theme here so the
        // change is visible without waiting for a page reload.
        applyTheme(next);
        themeSaved.value = t('profile.preferences.themeSaved');
    }
}
async function onUiLevelChanged(value) {
    uiLevelSaved.value = null;
    const next = asUiLevel(value);
    uiLevelDraft.value = next;
    // "standard" is the default and stored as the absence of the
    // setting — same convention as theme=auto / language="".
    await saveSetting(UI_LEVEL_KEY, next === 'standard' ? null : next).catch(() => undefined);
    if (!error.value) {
        // Index-page tile filtering reads {@link getActiveUiLevel} from
        // the data cookie on its next mount, which the PUT response just
        // refreshed.
        uiLevelSaved.value = t('profile.preferences.uiLevelSaved');
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
    const __VLS_41 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.chatLanguageDraft),
        options: (__VLS_ctx.chatLanguageOptions),
        label: (__VLS_ctx.$t('profile.preferences.chatLanguage')),
        disabled: (__VLS_ctx.loading),
    }));
    const __VLS_43 = __VLS_42({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.chatLanguageDraft),
        options: (__VLS_ctx.chatLanguageOptions),
        label: (__VLS_ctx.$t('profile.preferences.chatLanguage')),
        disabled: (__VLS_ctx.loading),
    }, ...__VLS_functionalComponentArgsRest(__VLS_42));
    let __VLS_45;
    let __VLS_46;
    let __VLS_47;
    const __VLS_48 = {
        'onUpdate:modelValue': (__VLS_ctx.onChatLanguageChanged)
    };
    var __VLS_44;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-60 -mt-2" },
    });
    (__VLS_ctx.$t('profile.preferences.chatLanguageDescription'));
    if (__VLS_ctx.chatLanguageSaved) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-success text-sm" },
        });
        (__VLS_ctx.chatLanguageSaved);
    }
    const __VLS_49 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.themeDraft),
        options: (__VLS_ctx.themeOptions),
        label: (__VLS_ctx.$t('profile.preferences.theme')),
        disabled: (__VLS_ctx.loading),
    }));
    const __VLS_51 = __VLS_50({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.themeDraft),
        options: (__VLS_ctx.themeOptions),
        label: (__VLS_ctx.$t('profile.preferences.theme')),
        disabled: (__VLS_ctx.loading),
    }, ...__VLS_functionalComponentArgsRest(__VLS_50));
    let __VLS_53;
    let __VLS_54;
    let __VLS_55;
    const __VLS_56 = {
        'onUpdate:modelValue': (__VLS_ctx.onThemeChanged)
    };
    var __VLS_52;
    if (__VLS_ctx.themeSaved) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-success text-sm" },
        });
        (__VLS_ctx.themeSaved);
    }
    const __VLS_57 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.uiLevelDraft),
        options: (__VLS_ctx.uiLevelOptions),
        label: (__VLS_ctx.$t('profile.preferences.uiLevel')),
        disabled: (__VLS_ctx.loading),
    }));
    const __VLS_59 = __VLS_58({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.uiLevelDraft),
        options: (__VLS_ctx.uiLevelOptions),
        label: (__VLS_ctx.$t('profile.preferences.uiLevel')),
        disabled: (__VLS_ctx.loading),
    }, ...__VLS_functionalComponentArgsRest(__VLS_58));
    let __VLS_61;
    let __VLS_62;
    let __VLS_63;
    const __VLS_64 = {
        'onUpdate:modelValue': (__VLS_ctx.onUiLevelChanged)
    };
    var __VLS_60;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-60 -mt-2" },
    });
    (__VLS_ctx.$t('profile.preferences.uiLevelDescription'));
    if (__VLS_ctx.uiLevelSaved) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-success text-sm" },
        });
        (__VLS_ctx.uiLevelSaved);
    }
    var __VLS_32;
    const __VLS_65 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({}));
    const __VLS_67 = __VLS_66({}, ...__VLS_functionalComponentArgsRest(__VLS_66));
    __VLS_68.slots.default;
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
    var __VLS_68;
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
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['-mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['-mt-2']} */ ;
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
            chatLanguageDraft: chatLanguageDraft,
            themeDraft: themeDraft,
            uiLevelDraft: uiLevelDraft,
            identitySaved: identitySaved,
            languageSaved: languageSaved,
            chatLanguageSaved: chatLanguageSaved,
            themeSaved: themeSaved,
            uiLevelSaved: uiLevelSaved,
            languageOptions: languageOptions,
            chatLanguageOptions: chatLanguageOptions,
            themeOptions: themeOptions,
            uiLevelOptions: uiLevelOptions,
            onSaveIdentity: onSaveIdentity,
            onLanguageChanged: onLanguageChanged,
            onChatLanguageChanged: onChatLanguageChanged,
            onThemeChanged: onThemeChanged,
            onUiLevelChanged: onUiLevelChanged,
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