import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { CodeEditor, EditorShell, VAlert, VButton, VCard, VEmptyState, VModal, VSelect, } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useSchedulers } from '@/composables/useSchedulers';
const VANCE_PROJECT = '_vance';
const DEFAULT_TEMPLATE = `description: "Spawns the analyze recipe every weekday at 08:00."
cron: "0 0 8 * * MON-FRI"
timezone: "Europe/Berlin"
recipe: "analyze"
overlap: skip
# runAs: <username>          # defaults to your account
# initialMessage: |
#   Draft the morning briefing.
# params:
#   model: default:fast
`;
const { t } = useI18n();
const tenantProjects = useTenantProjects();
const state = useSchedulers();
const selectedProject = ref(VANCE_PROJECT);
const selectedName = ref(null);
const yamlDraft = ref('');
const banner = ref(null);
const projectOptions = computed(() => {
    const list = [
        { value: VANCE_PROJECT, label: t('scheduler.tenantWide') },
    ];
    for (const p of tenantProjects.projects.value) {
        if (p.name === VANCE_PROJECT)
            continue;
        list.push({
            value: p.name,
            label: (p.title ? p.title + ' ' : '') + '(' + p.name + ')',
        });
    }
    return list;
});
const showNewModal = ref(false);
const newName = ref('');
const newError = ref(null);
const showDeleteModal = ref(false);
const combinedError = computed(() => state.error.value || tenantProjects.error.value || null);
const breadcrumbs = computed(() => {
    const proj = selectedProject.value === VANCE_PROJECT
        ? t('scheduler.tenantWide')
        : selectedProject.value;
    return selectedName.value ? [proj, selectedName.value] : [proj];
});
const isModified = computed(() => {
    if (!state.current.value)
        return false;
    return yamlDraft.value !== state.current.value.yaml;
});
const sortedSchedulers = computed(() => [...state.schedulers.value].sort((a, b) => a.name.localeCompare(b.name)));
onMounted(async () => {
    await Promise.all([
        tenantProjects.reload(),
        state.loadProject(selectedProject.value),
    ]);
});
watch(selectedProject, async (pid) => {
    selectedName.value = null;
    yamlDraft.value = '';
    state.clearCurrent();
    await state.loadProject(pid);
});
async function selectScheduler(name) {
    selectedName.value = name;
    await state.loadOne(selectedProject.value, name);
    yamlDraft.value = state.current.value?.yaml ?? '';
    await state.loadEvents(selectedProject.value, name, 20);
}
async function refreshList() {
    banner.value = null;
    try {
        const registered = await state.refresh(selectedProject.value);
        banner.value = t('scheduler.refreshedHint', { count: registered });
    }
    catch {
        /* error surfaced via state.error */
    }
}
function openNewModal() {
    newName.value = '';
    newError.value = null;
    showNewModal.value = true;
}
async function createScheduler() {
    const name = newName.value.trim().toLowerCase();
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(name)) {
        newError.value = t('scheduler.invalidNameHint');
        return;
    }
    if (state.schedulers.value.some(s => s.name === name)) {
        newError.value = t('scheduler.duplicateNameHint');
        return;
    }
    try {
        await state.save(selectedProject.value, name, DEFAULT_TEMPLATE);
        showNewModal.value = false;
        await selectScheduler(name);
        banner.value = t('scheduler.createdHint', { name });
    }
    catch (e) {
        newError.value = e instanceof Error ? e.message : t('scheduler.saveFailed');
    }
}
async function saveCurrent() {
    if (!selectedName.value)
        return;
    banner.value = null;
    try {
        await state.save(selectedProject.value, selectedName.value, yamlDraft.value);
        banner.value = t('scheduler.savedHint', { name: selectedName.value });
        await state.loadEvents(selectedProject.value, selectedName.value, 20);
    }
    catch {
        /* surfaced via error */
    }
}
async function confirmDelete() {
    if (!selectedName.value)
        return;
    try {
        const name = selectedName.value;
        await state.remove(selectedProject.value, name);
        showDeleteModal.value = false;
        selectedName.value = null;
        yamlDraft.value = '';
        banner.value = t('scheduler.deletedHint', { name });
    }
    catch {
        showDeleteModal.value = false;
    }
}
function formatTimestamp(value) {
    if (!value)
        return '—';
    const d = typeof value === 'string' ? new Date(value) : value;
    return d.toLocaleString();
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.t('scheduler.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: (true),
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.t('scheduler.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: (true),
    wideRightPanel: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-4 space-y-4" },
    });
    const __VLS_5 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        modelValue: (__VLS_ctx.selectedProject),
        options: (__VLS_ctx.projectOptions),
        label: (__VLS_ctx.t('scheduler.projectLabel')),
    }));
    const __VLS_7 = __VLS_6({
        modelValue: (__VLS_ctx.selectedProject),
        options: (__VLS_ctx.projectOptions),
        label: (__VLS_ctx.t('scheduler.projectLabel')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-sm font-semibold" },
    });
    (__VLS_ctx.t('scheduler.listLabel'));
    const __VLS_9 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "ghost",
    }));
    const __VLS_11 = __VLS_10({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    let __VLS_13;
    let __VLS_14;
    let __VLS_15;
    const __VLS_16 = {
        onClick: (__VLS_ctx.refreshList)
    };
    __VLS_12.slots.default;
    var __VLS_12;
    if (__VLS_ctx.sortedSchedulers.length) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "space-y-1" },
        });
        for (const [s] of __VLS_getVForSourceType((__VLS_ctx.sortedSchedulers))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (s.name),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.sortedSchedulers.length))
                            return;
                        __VLS_ctx.selectScheduler(s.name);
                    } },
                ...{ class: ([
                        'w-full text-left p-2 rounded',
                        __VLS_ctx.selectedName === s.name ? 'bg-primary/15' : 'hover:bg-base-200',
                    ]) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center justify-between gap-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono text-sm" },
            });
            (s.name);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: ([
                        'text-xs px-1.5 py-0.5 rounded',
                        s.enabled ? 'bg-success/20 text-success' : 'bg-base-300 text-base-content/60',
                    ]) },
            });
            (s.enabled ? __VLS_ctx.t('scheduler.enabled') : __VLS_ctx.t('scheduler.disabled'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs text-base-content/60 truncate" },
            });
            (s.description);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs text-base-content/50 font-mono" },
            });
            (s.cron);
        }
    }
    else {
        const __VLS_17 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
            headline: (__VLS_ctx.t('scheduler.emptyTitle')),
            body: (__VLS_ctx.t('scheduler.emptyBody')),
        }));
        const __VLS_19 = __VLS_18({
            headline: (__VLS_ctx.t('scheduler.emptyTitle')),
            body: (__VLS_ctx.t('scheduler.emptyBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    }
    const __VLS_21 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "primary",
        block: true,
    }));
    const __VLS_23 = __VLS_22({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "primary",
        block: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    let __VLS_25;
    let __VLS_26;
    let __VLS_27;
    const __VLS_28 = {
        onClick: (__VLS_ctx.openNewModal)
    };
    __VLS_24.slots.default;
    (__VLS_ctx.t('scheduler.new'));
    var __VLS_24;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-4 space-y-4" },
});
if (__VLS_ctx.combinedError) {
    const __VLS_29 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
        variant: "error",
    }));
    const __VLS_31 = __VLS_30({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    __VLS_32.slots.default;
    (__VLS_ctx.combinedError);
    var __VLS_32;
}
if (__VLS_ctx.banner) {
    const __VLS_33 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        variant: "success",
    }));
    const __VLS_35 = __VLS_34({
        variant: "success",
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    __VLS_36.slots.default;
    (__VLS_ctx.banner);
    var __VLS_36;
}
if (__VLS_ctx.state.current.value) {
    const __VLS_37 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({}));
    const __VLS_39 = __VLS_38({}, ...__VLS_functionalComponentArgsRest(__VLS_38));
    __VLS_40.slots.default;
    {
        const { header: __VLS_thisSlot } = __VLS_40.slots;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between w-full" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.state.current.value.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs text-base-content/60" },
        });
        (__VLS_ctx.state.current.value.source);
    }
    const __VLS_41 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
        modelValue: (__VLS_ctx.yamlDraft),
        mimeType: "application/yaml",
        rows: (22),
    }));
    const __VLS_43 = __VLS_42({
        modelValue: (__VLS_ctx.yamlDraft),
        mimeType: "application/yaml",
        rows: (22),
    }, ...__VLS_functionalComponentArgsRest(__VLS_42));
    {
        const { actions: __VLS_thisSlot } = __VLS_40.slots;
        const __VLS_45 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_46 = __VLS_asFunctionalComponent(__VLS_45, new __VLS_45({
            ...{ 'onClick': {} },
            variant: "ghost",
        }));
        const __VLS_47 = __VLS_46({
            ...{ 'onClick': {} },
            variant: "ghost",
        }, ...__VLS_functionalComponentArgsRest(__VLS_46));
        let __VLS_49;
        let __VLS_50;
        let __VLS_51;
        const __VLS_52 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.state.current.value))
                    return;
                __VLS_ctx.showDeleteModal = true;
            }
        };
        __VLS_48.slots.default;
        (__VLS_ctx.t('common.delete'));
        var __VLS_48;
        const __VLS_53 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
            ...{ 'onClick': {} },
            variant: "primary",
            disabled: (!__VLS_ctx.isModified || __VLS_ctx.state.busy.value),
        }));
        const __VLS_55 = __VLS_54({
            ...{ 'onClick': {} },
            variant: "primary",
            disabled: (!__VLS_ctx.isModified || __VLS_ctx.state.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_54));
        let __VLS_57;
        let __VLS_58;
        let __VLS_59;
        const __VLS_60 = {
            onClick: (__VLS_ctx.saveCurrent)
        };
        __VLS_56.slots.default;
        (__VLS_ctx.t('common.save'));
        var __VLS_56;
    }
    var __VLS_40;
}
else {
    const __VLS_61 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
        headline: (__VLS_ctx.t('scheduler.selectTitle')),
        body: (__VLS_ctx.t('scheduler.selectBody')),
    }));
    const __VLS_63 = __VLS_62({
        headline: (__VLS_ctx.t('scheduler.selectTitle')),
        body: (__VLS_ctx.t('scheduler.selectBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_62));
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-4 space-y-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
        ...{ class: "text-sm font-semibold" },
    });
    (__VLS_ctx.t('scheduler.runHistory'));
    if (!__VLS_ctx.state.events.value.length) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm text-base-content/60" },
        });
        (__VLS_ctx.t('scheduler.noEvents'));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "space-y-2" },
        });
        for (const [e] of __VLS_getVForSourceType((__VLS_ctx.state.events.value))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (e.id),
                ...{ class: "border border-base-300 rounded p-2 text-xs space-y-0.5" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center justify-between" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono font-semibold" },
            });
            (e.type);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-base-content/60" },
            });
            (__VLS_ctx.formatTimestamp(e.timestamp));
            if (e.processId) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-base-content/60" },
                });
                (__VLS_ctx.t('scheduler.process'));
                (e.processId);
            }
            if (e.payload?.error) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-error" },
                });
                (e.payload.error);
            }
            if (e.payload?.reason) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-base-content/60" },
                });
                (__VLS_ctx.t('scheduler.reason'));
                (e.payload.reason);
            }
        }
    }
}
const __VLS_65 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
    modelValue: (__VLS_ctx.showNewModal),
    title: (__VLS_ctx.t('scheduler.newTitle')),
}));
const __VLS_67 = __VLS_66({
    modelValue: (__VLS_ctx.showNewModal),
    title: (__VLS_ctx.t('scheduler.newTitle')),
}, ...__VLS_functionalComponentArgsRest(__VLS_66));
__VLS_68.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "space-y-3" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
    ...{ class: "block" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "text-sm font-semibold" },
});
(__VLS_ctx.t('scheduler.nameLabel'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ onKeyup: (__VLS_ctx.createScheduler) },
    ...{ class: "input input-bordered w-full mt-1" },
    placeholder: (__VLS_ctx.t('scheduler.namePlaceholder')),
});
(__VLS_ctx.newName);
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-xs text-base-content/60" },
});
(__VLS_ctx.t('scheduler.namePatternHint'));
if (__VLS_ctx.newError) {
    const __VLS_69 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_70 = __VLS_asFunctionalComponent(__VLS_69, new __VLS_69({
        variant: "error",
    }));
    const __VLS_71 = __VLS_70({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_70));
    __VLS_72.slots.default;
    (__VLS_ctx.newError);
    var __VLS_72;
}
{
    const { actions: __VLS_thisSlot } = __VLS_68.slots;
    const __VLS_73 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_75 = __VLS_74({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_74));
    let __VLS_77;
    let __VLS_78;
    let __VLS_79;
    const __VLS_80 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showNewModal = false;
        }
    };
    __VLS_76.slots.default;
    (__VLS_ctx.t('common.cancel'));
    var __VLS_76;
    const __VLS_81 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.state.busy.value),
    }));
    const __VLS_83 = __VLS_82({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.state.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_82));
    let __VLS_85;
    let __VLS_86;
    let __VLS_87;
    const __VLS_88 = {
        onClick: (__VLS_ctx.createScheduler)
    };
    __VLS_84.slots.default;
    (__VLS_ctx.t('scheduler.create'));
    var __VLS_84;
}
var __VLS_68;
const __VLS_89 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
    modelValue: (__VLS_ctx.showDeleteModal),
    title: (__VLS_ctx.t('scheduler.deleteTitle')),
}));
const __VLS_91 = __VLS_90({
    modelValue: (__VLS_ctx.showDeleteModal),
    title: (__VLS_ctx.t('scheduler.deleteTitle')),
}, ...__VLS_functionalComponentArgsRest(__VLS_90));
__VLS_92.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
(__VLS_ctx.t('scheduler.deleteBody', { name: __VLS_ctx.selectedName }));
{
    const { actions: __VLS_thisSlot } = __VLS_92.slots;
    const __VLS_93 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_94 = __VLS_asFunctionalComponent(__VLS_93, new __VLS_93({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_95 = __VLS_94({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_94));
    let __VLS_97;
    let __VLS_98;
    let __VLS_99;
    const __VLS_100 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showDeleteModal = false;
        }
    };
    __VLS_96.slots.default;
    (__VLS_ctx.t('common.cancel'));
    var __VLS_96;
    const __VLS_101 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_102 = __VLS_asFunctionalComponent(__VLS_101, new __VLS_101({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.state.busy.value),
    }));
    const __VLS_103 = __VLS_102({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.state.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_102));
    let __VLS_105;
    let __VLS_106;
    let __VLS_107;
    const __VLS_108 = {
        onClick: (__VLS_ctx.confirmDelete)
    };
    __VLS_104.slots.default;
    (__VLS_ctx.t('common.delete'));
    var __VLS_104;
}
var __VLS_92;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
/** @type {__VLS_StyleScopedClasses['block']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['input']} */ ;
/** @type {__VLS_StyleScopedClasses['input-bordered']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
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
            VModal: VModal,
            VSelect: VSelect,
            t: t,
            state: state,
            selectedProject: selectedProject,
            selectedName: selectedName,
            yamlDraft: yamlDraft,
            banner: banner,
            projectOptions: projectOptions,
            showNewModal: showNewModal,
            newName: newName,
            newError: newError,
            showDeleteModal: showDeleteModal,
            combinedError: combinedError,
            breadcrumbs: breadcrumbs,
            isModified: isModified,
            sortedSchedulers: sortedSchedulers,
            selectScheduler: selectScheduler,
            refreshList: refreshList,
            openNewModal: openNewModal,
            createScheduler: createScheduler,
            saveCurrent: saveCurrent,
            confirmDelete: confirmDelete,
            formatTimestamp: formatTimestamp,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=SchedulerApp.vue.js.map