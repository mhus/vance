import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, SettingFormView, VAlert, VButton, VEmptyState, VSelect, } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { listSettingForms, RestError } from '@vance/shared';
/** Sentinel project name representing "tenant-wide". */
const TENANT_PROJECT = '_tenant';
const { t } = useI18n();
const tenantProjects = useTenantProjects();
const selectedProject = ref(TENANT_PROJECT);
const listing = ref([]);
const listLoading = ref(false);
const listError = ref(null);
const selectedForm = ref(null);
/** Bumped after apply/reset to force the active form to reload its cascade values. */
const reloadKey = ref(0);
const projectOptions = computed(() => {
    const list = [
        { value: TENANT_PROJECT, label: t('settingForms.tenantWide') },
    ];
    for (const p of tenantProjects.projects.value) {
        if (p.name === TENANT_PROJECT)
            continue;
        if (p.name.startsWith('_user_'))
            continue;
        list.push({
            value: p.name,
            label: (p.title ? p.title + ' ' : '') + '(' + p.name + ')',
        });
    }
    return list;
});
const groupedByCategory = computed(() => {
    const groups = new Map();
    for (const f of listing.value) {
        const cat = f.category ?? '';
        if (!groups.has(cat))
            groups.set(cat, []);
        groups.get(cat).push(f);
    }
    for (const list of groups.values()) {
        list.sort((a, b) => a.title.localeCompare(b.title));
    }
    return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));
});
/** Pass `undefined` to the brain when the user picked "tenant-wide". */
const effectiveProjectId = computed(() => selectedProject.value === TENANT_PROJECT ? undefined : selectedProject.value);
const activeForm = computed(() => {
    if (!selectedForm.value)
        return null;
    return listing.value.find((f) => f.name === selectedForm.value) ?? null;
});
async function refreshListing() {
    listLoading.value = true;
    listError.value = null;
    try {
        const res = await listSettingForms(effectiveProjectId.value);
        listing.value = res.forms ?? [];
        // If the previously selected form is no longer in the list, drop it.
        if (selectedForm.value && !listing.value.some((f) => f.name === selectedForm.value)) {
            selectedForm.value = null;
        }
    }
    catch (err) {
        listError.value = err instanceof RestError ? err.message : String(err);
        listing.value = [];
    }
    finally {
        listLoading.value = false;
    }
}
function selectForm(name) {
    selectedForm.value = name;
}
function onApplied() {
    // Reload listing (a form's clearable/source might change after a write) and
    // bump the reloadKey so the inner view re-fetches its currentValue map.
    reloadKey.value += 1;
    void refreshListing();
}
onMounted(async () => {
    await tenantProjects.reload();
    await refreshListing();
});
watch(selectedProject, () => {
    selectedForm.value = null;
    void refreshListing();
});
const breadcrumbs = computed(() => {
    const proj = selectedProject.value === TENANT_PROJECT
        ? t('settingForms.tenantWide')
        : selectedProject.value;
    return activeForm.value ? [proj, activeForm.value.title] : [proj];
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.t('settingForms.title')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.t('settingForms.title')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3 p-3 min-h-0" },
    });
    const __VLS_5 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        modelValue: (__VLS_ctx.selectedProject),
        options: (__VLS_ctx.projectOptions),
        label: (__VLS_ctx.t('settingForms.projectLabel')),
        size: "sm",
    }));
    const __VLS_7 = __VLS_6({
        modelValue: (__VLS_ctx.selectedProject),
        options: (__VLS_ctx.projectOptions),
        label: (__VLS_ctx.t('settingForms.projectLabel')),
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between px-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold" },
    });
    (__VLS_ctx.t('settingForms.formsLabel'));
    const __VLS_9 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        loading: (__VLS_ctx.listLoading),
    }));
    const __VLS_11 = __VLS_10({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        loading: (__VLS_ctx.listLoading),
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    let __VLS_13;
    let __VLS_14;
    let __VLS_15;
    const __VLS_16 = {
        onClick: (__VLS_ctx.refreshListing)
    };
    __VLS_12.slots.default;
    var __VLS_12;
    if (__VLS_ctx.listError) {
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
        (__VLS_ctx.listError);
        var __VLS_20;
    }
    else if (!__VLS_ctx.listLoading && __VLS_ctx.listing.length === 0) {
        const __VLS_21 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
            headline: (__VLS_ctx.t('settingForms.emptyHeadline')),
            body: (__VLS_ctx.t('settingForms.emptyBody')),
        }));
        const __VLS_23 = __VLS_22({
            headline: (__VLS_ctx.t('settingForms.emptyHeadline')),
            body: (__VLS_ctx.t('settingForms.emptyBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    }
    for (const [[cat, group]] of __VLS_getVForSourceType((__VLS_ctx.groupedByCategory))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (cat),
            ...{ class: "flex flex-col gap-1" },
        });
        if (cat) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-[10px] uppercase tracking-wide opacity-50 font-semibold px-1 mt-1" },
            });
            (cat);
        }
        for (const [f] of __VLS_getVForSourceType((group))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        __VLS_ctx.selectForm(f.name);
                    } },
                key: (f.name),
                type: "button",
                ...{ class: "text-left px-2.5 py-2 text-sm rounded transition-colors" },
                ...{ class: ({
                        'bg-primary/15 hover:bg-primary/20': __VLS_ctx.selectedForm === f.name,
                        'bg-base-200 hover:bg-base-300': __VLS_ctx.selectedForm !== f.name,
                    }) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center gap-1.5" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-semibold truncate" },
            });
            (f.title);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-70 mt-0.5 line-clamp-2" },
            });
            (f.description);
        }
    }
}
{
    const { default: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-4 max-w-3xl" },
    });
    if (__VLS_ctx.selectedForm) {
        const __VLS_25 = {}.SettingFormView;
        /** @type {[typeof __VLS_components.SettingFormView, ]} */ ;
        // @ts-ignore
        const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
            ...{ 'onApplied': {} },
            name: (__VLS_ctx.selectedForm),
            projectId: (__VLS_ctx.effectiveProjectId),
            reloadKey: (__VLS_ctx.reloadKey),
        }));
        const __VLS_27 = __VLS_26({
            ...{ 'onApplied': {} },
            name: (__VLS_ctx.selectedForm),
            projectId: (__VLS_ctx.effectiveProjectId),
            reloadKey: (__VLS_ctx.reloadKey),
        }, ...__VLS_functionalComponentArgsRest(__VLS_26));
        let __VLS_29;
        let __VLS_30;
        let __VLS_31;
        const __VLS_32 = {
            onApplied: (__VLS_ctx.onApplied)
        };
        var __VLS_28;
    }
    else {
        const __VLS_33 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
            headline: (__VLS_ctx.t('settingForms.pickFormHeadline')),
            body: (__VLS_ctx.t('settingForms.pickFormBody')),
        }));
        const __VLS_35 = __VLS_34({
            headline: (__VLS_ctx.t('settingForms.pickFormHeadline')),
            body: (__VLS_ctx.t('settingForms.pickFormBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    }
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-primary/15']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-primary/20']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['line-clamp-2']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            SettingFormView: SettingFormView,
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            VSelect: VSelect,
            t: t,
            selectedProject: selectedProject,
            listing: listing,
            listLoading: listLoading,
            listError: listError,
            selectedForm: selectedForm,
            reloadKey: reloadKey,
            projectOptions: projectOptions,
            groupedByCategory: groupedByCategory,
            effectiveProjectId: effectiveProjectId,
            refreshListing: refreshListing,
            selectForm: selectForm,
            onApplied: onApplied,
            breadcrumbs: breadcrumbs,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=SettingFormsApp.vue.js.map