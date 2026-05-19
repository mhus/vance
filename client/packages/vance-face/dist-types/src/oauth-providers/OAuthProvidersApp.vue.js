import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { CodeEditor, EditorShell, VAlert, VButton, VCard, VEmptyState, VInput, VModal, } from '@/components';
import { useAdminOAuthProviders } from '@/composables/useAdminOAuthProviders';
const PROVIDER_ID_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;
const { t } = useI18n();
const state = useAdminOAuthProviders();
const selectedProviderId = ref(null);
const banner = ref(null);
// Form state — the YAML body is the source of truth; clientSecret is
// optional (only sent when the user explicitly types one).
const form = reactive({
    yaml: '',
    newClientSecret: '',
});
const showNewModal = ref(false);
const newProviderId = ref('');
const newProviderError = ref(null);
const selected = computed(() => {
    if (!selectedProviderId.value)
        return null;
    return state.providers.value.find(p => p.providerId === selectedProviderId.value) ?? null;
});
const breadcrumbs = computed(() => selectedProviderId.value
    ? [t('oauthProviders.breadcrumbRoot'), selectedProviderId.value]
    : [t('oauthProviders.breadcrumbRoot')]);
onMounted(state.reload);
watch(selectedProviderId, () => {
    banner.value = null;
    if (selected.value) {
        form.yaml = selected.value.yaml ?? '';
        form.newClientSecret = '';
    }
    else {
        form.yaml = '';
        form.newClientSecret = '';
    }
});
async function save() {
    if (!selectedProviderId.value)
        return;
    banner.value = null;
    const body = {
        yaml: form.yaml,
    };
    if (form.newClientSecret.length > 0) {
        body.clientSecret = form.newClientSecret;
    }
    try {
        await state.upsert(selectedProviderId.value, body);
        form.newClientSecret = '';
        banner.value = t('oauthProviders.banner.saved');
    }
    catch {
        /* error captured in state.error */
    }
}
async function removeClientSecret() {
    if (!selectedProviderId.value)
        return;
    if (!confirm(t('oauthProviders.confirmRemoveSecret')))
        return;
    const body = {
        yaml: form.yaml,
        clientSecret: '',
    };
    try {
        await state.upsert(selectedProviderId.value, body);
        banner.value = t('oauthProviders.banner.secretRemoved');
    }
    catch {
        /* error captured in state.error */
    }
}
async function deleteProvider() {
    if (!selectedProviderId.value)
        return;
    if (!confirm(t('oauthProviders.confirmDelete', { id: selectedProviderId.value })))
        return;
    try {
        await state.remove(selectedProviderId.value);
        selectedProviderId.value = null;
        banner.value = t('oauthProviders.banner.deleted');
    }
    catch {
        /* error captured in state.error */
    }
}
function openNewProvider() {
    newProviderId.value = '';
    newProviderError.value = null;
    showNewModal.value = true;
}
async function submitNewProvider() {
    newProviderError.value = null;
    const id = newProviderId.value.trim().toLowerCase();
    if (!id) {
        newProviderError.value = t('oauthProviders.newModal.idRequired');
        return;
    }
    if (!PROVIDER_ID_PATTERN.test(id)) {
        newProviderError.value = t('oauthProviders.newModal.idPattern');
        return;
    }
    if (state.providers.value.some(p => p.providerId === id)) {
        newProviderError.value = t('oauthProviders.newModal.idAlreadyExists', { id });
        return;
    }
    const stub = {
        yaml: stubYamlForId(id),
    };
    try {
        await state.upsert(id, stub);
        showNewModal.value = false;
        selectedProviderId.value = id;
        banner.value = t('oauthProviders.banner.created', { id });
    }
    catch (e) {
        newProviderError.value =
            e instanceof Error ? e.message : t('oauthProviders.newModal.createFailed');
    }
}
function stubYamlForId(id) {
    return [
        `# OAuth provider '${id}' — fill in the fields below and add the`,
        `# tenant PASSWORD setting 'oauth.${id}.client_secret' to complete.`,
        'type: oidc',
        'clientId: "REPLACE-ME"',
        'discoveryUrl: "https://idp.example.com/.well-known/openid-configuration"',
        'scopes:',
        '  - openid',
        '  - profile',
        '  - email',
        '',
    ].join('\n');
}
function selectProvider(providerId) {
    selectedProviderId.value = providerId;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['provider-item']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.$t('oauthProviders.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('oauthProviders.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
        ...{ class: "flex flex-col gap-1 p-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between px-2 mb-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs uppercase opacity-50" },
    });
    (__VLS_ctx.$t('oauthProviders.sidebar.providersLabel'));
    const __VLS_5 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_7 = __VLS_6({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    let __VLS_9;
    let __VLS_10;
    let __VLS_11;
    const __VLS_12 = {
        onClick: (__VLS_ctx.openNewProvider)
    };
    __VLS_8.slots.default;
    (__VLS_ctx.$t('oauthProviders.sidebar.addNew'));
    var __VLS_8;
    if (__VLS_ctx.state.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-2 text-xs opacity-60" },
        });
        (__VLS_ctx.$t('oauthProviders.loading'));
    }
    else if (__VLS_ctx.state.providers.value.length === 0) {
        const __VLS_13 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
            headline: (__VLS_ctx.$t('oauthProviders.sidebar.noProvidersHeadline')),
            body: (__VLS_ctx.$t('oauthProviders.sidebar.noProvidersBody')),
        }));
        const __VLS_15 = __VLS_14({
            headline: (__VLS_ctx.$t('oauthProviders.sidebar.noProvidersHeadline')),
            body: (__VLS_ctx.$t('oauthProviders.sidebar.noProvidersBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_14));
    }
    for (const [p] of __VLS_getVForSourceType((__VLS_ctx.state.providers.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectProvider(p.providerId);
                } },
            key: (p.providerId),
            ...{ class: "provider-item" },
            ...{ class: ({ 'provider-item--active': __VLS_ctx.selectedProviderId === p.providerId }) },
            type: "button",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono text-sm truncate" },
        });
        (p.providerId);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs px-1.5 py-0.5 rounded badge-type" },
        });
        (p.typeId);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-2 text-xs opacity-60" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: (p.hasClientSecret ? 'badge-secret-set' : 'badge-secret-missing') },
        });
        (p.hasClientSecret
            ? __VLS_ctx.$t('oauthProviders.sidebar.secretSet')
            : __VLS_ctx.$t('oauthProviders.sidebar.secretMissing'));
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 flex flex-col gap-3 max-w-4xl" },
});
if (__VLS_ctx.state.error.value) {
    const __VLS_17 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        variant: "error",
    }));
    const __VLS_19 = __VLS_18({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    __VLS_20.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.state.error.value);
    var __VLS_20;
}
if (__VLS_ctx.banner) {
    const __VLS_21 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        variant: "success",
    }));
    const __VLS_23 = __VLS_22({
        variant: "success",
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    __VLS_24.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.banner);
    var __VLS_24;
}
if (!__VLS_ctx.selected) {
    const __VLS_25 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
        headline: (__VLS_ctx.$t('oauthProviders.empty.headline')),
        body: (__VLS_ctx.$t('oauthProviders.empty.body')),
    }));
    const __VLS_27 = __VLS_26({
        headline: (__VLS_ctx.$t('oauthProviders.empty.headline')),
        body: (__VLS_ctx.$t('oauthProviders.empty.body')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
}
else {
    const __VLS_29 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({}));
    const __VLS_31 = __VLS_30({}, ...__VLS_functionalComponentArgsRest(__VLS_30));
    __VLS_32.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-mono text-lg" },
    });
    (__VLS_ctx.selected.providerId);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "badge-type" },
    });
    (__VLS_ctx.selected.typeId);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "ml-2" },
    });
    (__VLS_ctx.$t('oauthProviders.detail.clientIdLabel'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    (__VLS_ctx.selected.clientId);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-2" },
    });
    const __VLS_33 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.state.busy.value),
    }));
    const __VLS_35 = __VLS_34({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.state.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    let __VLS_37;
    let __VLS_38;
    let __VLS_39;
    const __VLS_40 = {
        onClick: (__VLS_ctx.deleteProvider)
    };
    __VLS_36.slots.default;
    (__VLS_ctx.$t('oauthProviders.detail.delete'));
    var __VLS_36;
    const __VLS_41 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.state.busy.value),
    }));
    const __VLS_43 = __VLS_42({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.state.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_42));
    let __VLS_45;
    let __VLS_46;
    let __VLS_47;
    const __VLS_48 = {
        onClick: (__VLS_ctx.save)
    };
    __VLS_44.slots.default;
    (__VLS_ctx.$t('oauthProviders.detail.save'));
    var __VLS_44;
    var __VLS_32;
    const __VLS_49 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
        title: (__VLS_ctx.$t('oauthProviders.cards.yamlTitle')),
    }));
    const __VLS_51 = __VLS_50({
        title: (__VLS_ctx.$t('oauthProviders.cards.yamlTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_50));
    __VLS_52.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-70 mb-2" },
    });
    (__VLS_ctx.$t('oauthProviders.cards.yamlHelp'));
    const __VLS_53 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
        modelValue: (__VLS_ctx.form.yaml),
        mimeType: "text/yaml",
        rows: (18),
    }));
    const __VLS_55 = __VLS_54({
        modelValue: (__VLS_ctx.form.yaml),
        mimeType: "text/yaml",
        rows: (18),
    }, ...__VLS_functionalComponentArgsRest(__VLS_54));
    var __VLS_52;
    const __VLS_57 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
        title: (__VLS_ctx.$t('oauthProviders.cards.secretTitle')),
    }));
    const __VLS_59 = __VLS_58({
        title: (__VLS_ctx.$t('oauthProviders.cards.secretTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_58));
    __VLS_60.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-70 mb-2" },
    });
    (__VLS_ctx.selected.hasClientSecret
        ? __VLS_ctx.$t('oauthProviders.cards.secretIsSet')
        : __VLS_ctx.$t('oauthProviders.cards.secretIsMissing'));
    const __VLS_61 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
        modelValue: (__VLS_ctx.form.newClientSecret),
        type: "password",
        label: (__VLS_ctx.$t('oauthProviders.cards.newSecretLabel')),
        help: (__VLS_ctx.$t('oauthProviders.cards.newSecretHelp')),
        autocomplete: "new-password",
    }));
    const __VLS_63 = __VLS_62({
        modelValue: (__VLS_ctx.form.newClientSecret),
        type: "password",
        label: (__VLS_ctx.$t('oauthProviders.cards.newSecretLabel')),
        help: (__VLS_ctx.$t('oauthProviders.cards.newSecretHelp')),
        autocomplete: "new-password",
    }, ...__VLS_functionalComponentArgsRest(__VLS_62));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-2 mt-2" },
    });
    if (__VLS_ctx.selected.hasClientSecret) {
        const __VLS_65 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
            ...{ 'onClick': {} },
            variant: "ghost",
            loading: (__VLS_ctx.state.busy.value),
        }));
        const __VLS_67 = __VLS_66({
            ...{ 'onClick': {} },
            variant: "ghost",
            loading: (__VLS_ctx.state.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_66));
        let __VLS_69;
        let __VLS_70;
        let __VLS_71;
        const __VLS_72 = {
            onClick: (__VLS_ctx.removeClientSecret)
        };
        __VLS_68.slots.default;
        (__VLS_ctx.$t('oauthProviders.cards.removeSecret'));
        var __VLS_68;
    }
    var __VLS_60;
}
const __VLS_73 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
    modelValue: (__VLS_ctx.showNewModal),
    title: (__VLS_ctx.$t('oauthProviders.newModal.title')),
}));
const __VLS_75 = __VLS_74({
    modelValue: (__VLS_ctx.showNewModal),
    title: (__VLS_ctx.$t('oauthProviders.newModal.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_74));
__VLS_76.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.newProviderError) {
    const __VLS_77 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_78 = __VLS_asFunctionalComponent(__VLS_77, new __VLS_77({
        variant: "error",
    }));
    const __VLS_79 = __VLS_78({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_78));
    __VLS_80.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.newProviderError);
    var __VLS_80;
}
const __VLS_81 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
    modelValue: (__VLS_ctx.newProviderId),
    label: (__VLS_ctx.$t('oauthProviders.newModal.idLabel')),
    help: (__VLS_ctx.$t('oauthProviders.newModal.idHelp')),
    required: true,
}));
const __VLS_83 = __VLS_82({
    modelValue: (__VLS_ctx.newProviderId),
    label: (__VLS_ctx.$t('oauthProviders.newModal.idLabel')),
    help: (__VLS_ctx.$t('oauthProviders.newModal.idHelp')),
    required: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_82));
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-xs opacity-70" },
});
(__VLS_ctx.$t('oauthProviders.newModal.stubInfo'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_85 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_86 = __VLS_asFunctionalComponent(__VLS_85, new __VLS_85({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_87 = __VLS_86({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_86));
let __VLS_89;
let __VLS_90;
let __VLS_91;
const __VLS_92 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showNewModal = false;
    }
};
__VLS_88.slots.default;
(__VLS_ctx.$t('oauthProviders.newModal.cancel'));
var __VLS_88;
const __VLS_93 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_94 = __VLS_asFunctionalComponent(__VLS_93, new __VLS_93({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.state.busy.value),
}));
const __VLS_95 = __VLS_94({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.state.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_94));
let __VLS_97;
let __VLS_98;
let __VLS_99;
const __VLS_100 = {
    onClick: (__VLS_ctx.submitNewProvider)
};
__VLS_96.slots.default;
(__VLS_ctx.$t('oauthProviders.newModal.create'));
var __VLS_96;
var __VLS_76;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['provider-item']} */ ;
/** @type {__VLS_StyleScopedClasses['provider-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-type']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-4xl']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-type']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            CodeEditor: CodeEditor,
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            state: state,
            selectedProviderId: selectedProviderId,
            banner: banner,
            form: form,
            showNewModal: showNewModal,
            newProviderId: newProviderId,
            newProviderError: newProviderError,
            selected: selected,
            breadcrumbs: breadcrumbs,
            save: save,
            removeClientSecret: removeClientSecret,
            deleteProvider: deleteProvider,
            openNewProvider: openNewProvider,
            submitNewProvider: submitNewProvider,
            selectProvider: selectProvider,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=OAuthProvidersApp.vue.js.map