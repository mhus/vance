import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { CodeEditor, VAlert, VButton, VCard, VEmptyState, VModal, } from '@/components';
import { useSchedulers } from '@/composables/useSchedulers';
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
const props = defineProps();
const { t } = useI18n();
const state = useSchedulers();
const selectedName = ref(null);
const yamlDraft = ref('');
const banner = ref(null);
const showNewModal = ref(false);
const newName = ref('');
const newError = ref(null);
const showDeleteModal = ref(false);
const isModified = computed(() => {
    if (!state.current.value)
        return false;
    return yamlDraft.value !== state.current.value.yaml;
});
const sortedSchedulers = computed(() => [...state.schedulers.value].sort((a, b) => a.name.localeCompare(b.name)));
watch(() => props.projectId, (next) => {
    selectedName.value = null;
    yamlDraft.value = '';
    banner.value = null;
    state.clearCurrent();
    if (next) {
        void state.loadProject(next);
    }
    else {
        state.schedulers.value = [];
    }
}, { immediate: true });
async function selectScheduler(name) {
    if (!props.projectId)
        return;
    selectedName.value = name;
    await state.loadOne(props.projectId, name);
    yamlDraft.value = state.current.value?.yaml ?? '';
    await state.loadEvents(props.projectId, name, 20);
}
async function refreshList() {
    if (!props.projectId)
        return;
    banner.value = null;
    try {
        const registered = await state.refresh(props.projectId);
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
    if (!props.projectId)
        return;
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
        await state.save(props.projectId, name, DEFAULT_TEMPLATE);
        showNewModal.value = false;
        await selectScheduler(name);
        banner.value = t('scheduler.createdHint', { name });
    }
    catch (e) {
        newError.value = e instanceof Error ? e.message : t('scheduler.saveFailed');
    }
}
async function fireCurrent() {
    if (!props.projectId || !selectedName.value)
        return;
    banner.value = null;
    try {
        const result = await state.fire(props.projectId, selectedName.value);
        banner.value = t('scheduler.firedHint', {
            name: selectedName.value,
            correlationId: result.correlationId,
        });
    }
    catch {
        /* surfaced via state.error */
    }
}
async function saveCurrent() {
    if (!props.projectId || !selectedName.value)
        return;
    banner.value = null;
    try {
        await state.save(props.projectId, selectedName.value, yamlDraft.value);
        banner.value = t('scheduler.savedHint', { name: selectedName.value });
        await state.loadEvents(props.projectId, selectedName.value, 20);
    }
    catch {
        /* surfaced via error */
    }
}
async function confirmDelete() {
    if (!props.projectId || !selectedName.value)
        return;
    try {
        const name = selectedName.value;
        await state.remove(props.projectId, name);
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
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3 p-2" },
});
if (!__VLS_ctx.projectId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-60 text-sm" },
    });
    (__VLS_ctx.$t('scheduler.pickProject'));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-2 flex-wrap" },
    });
    const __VLS_0 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "ghost",
    }));
    const __VLS_2 = __VLS_1({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        onClick: (__VLS_ctx.refreshList)
    };
    __VLS_3.slots.default;
    (__VLS_ctx.t('scheduler.refresh'));
    var __VLS_3;
    const __VLS_8 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "primary",
    }));
    const __VLS_10 = __VLS_9({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "primary",
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    let __VLS_12;
    let __VLS_13;
    let __VLS_14;
    const __VLS_15 = {
        onClick: (__VLS_ctx.openNewModal)
    };
    __VLS_11.slots.default;
    (__VLS_ctx.t('scheduler.new'));
    var __VLS_11;
    if (__VLS_ctx.state.error.value) {
        const __VLS_16 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
            variant: "error",
        }));
        const __VLS_18 = __VLS_17({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_17));
        __VLS_19.slots.default;
        (__VLS_ctx.state.error.value);
        var __VLS_19;
    }
    if (__VLS_ctx.banner) {
        const __VLS_20 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
            variant: "success",
        }));
        const __VLS_22 = __VLS_21({
            variant: "success",
        }, ...__VLS_functionalComponentArgsRest(__VLS_21));
        __VLS_23.slots.default;
        (__VLS_ctx.banner);
        var __VLS_23;
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "grid grid-cols-12 gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
        ...{ class: "col-span-3 flex flex-col gap-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-sm font-semibold mb-1" },
    });
    (__VLS_ctx.t('scheduler.listLabel'));
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
                        if (!!(!__VLS_ctx.projectId))
                            return;
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
        const __VLS_24 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
            headline: (__VLS_ctx.t('scheduler.emptyTitle')),
            body: (__VLS_ctx.t('scheduler.emptyBody')),
        }));
        const __VLS_26 = __VLS_25({
            headline: (__VLS_ctx.t('scheduler.emptyTitle')),
            body: (__VLS_ctx.t('scheduler.emptyBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "col-span-6 flex flex-col gap-3" },
    });
    if (__VLS_ctx.state.current.value) {
        const __VLS_28 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({}));
        const __VLS_30 = __VLS_29({}, ...__VLS_functionalComponentArgsRest(__VLS_29));
        __VLS_31.slots.default;
        {
            const { header: __VLS_thisSlot } = __VLS_31.slots;
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
        const __VLS_32 = {}.CodeEditor;
        /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
        // @ts-ignore
        const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
            modelValue: (__VLS_ctx.yamlDraft),
            mimeType: "application/yaml",
            rows: (22),
        }));
        const __VLS_34 = __VLS_33({
            modelValue: (__VLS_ctx.yamlDraft),
            mimeType: "application/yaml",
            rows: (22),
        }, ...__VLS_functionalComponentArgsRest(__VLS_33));
        {
            const { actions: __VLS_thisSlot } = __VLS_31.slots;
            const __VLS_36 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
                ...{ 'onClick': {} },
                variant: "ghost",
            }));
            const __VLS_38 = __VLS_37({
                ...{ 'onClick': {} },
                variant: "ghost",
            }, ...__VLS_functionalComponentArgsRest(__VLS_37));
            let __VLS_40;
            let __VLS_41;
            let __VLS_42;
            const __VLS_43 = {
                onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.projectId))
                        return;
                    if (!(__VLS_ctx.state.current.value))
                        return;
                    __VLS_ctx.showDeleteModal = true;
                }
            };
            __VLS_39.slots.default;
            (__VLS_ctx.t('common.delete'));
            var __VLS_39;
            const __VLS_44 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_45 = __VLS_asFunctionalComponent(__VLS_44, new __VLS_44({
                ...{ 'onClick': {} },
                variant: "ghost",
                disabled: (__VLS_ctx.isModified || __VLS_ctx.state.busy.value),
                title: (__VLS_ctx.t('scheduler.fireHint')),
            }));
            const __VLS_46 = __VLS_45({
                ...{ 'onClick': {} },
                variant: "ghost",
                disabled: (__VLS_ctx.isModified || __VLS_ctx.state.busy.value),
                title: (__VLS_ctx.t('scheduler.fireHint')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_45));
            let __VLS_48;
            let __VLS_49;
            let __VLS_50;
            const __VLS_51 = {
                onClick: (__VLS_ctx.fireCurrent)
            };
            __VLS_47.slots.default;
            (__VLS_ctx.t('scheduler.fire'));
            var __VLS_47;
            const __VLS_52 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
                ...{ 'onClick': {} },
                variant: "primary",
                disabled: (!__VLS_ctx.isModified || __VLS_ctx.state.busy.value),
            }));
            const __VLS_54 = __VLS_53({
                ...{ 'onClick': {} },
                variant: "primary",
                disabled: (!__VLS_ctx.isModified || __VLS_ctx.state.busy.value),
            }, ...__VLS_functionalComponentArgsRest(__VLS_53));
            let __VLS_56;
            let __VLS_57;
            let __VLS_58;
            const __VLS_59 = {
                onClick: (__VLS_ctx.saveCurrent)
            };
            __VLS_55.slots.default;
            (__VLS_ctx.t('common.save'));
            var __VLS_55;
        }
        var __VLS_31;
    }
    else {
        const __VLS_60 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
            headline: (__VLS_ctx.t('scheduler.selectTitle')),
            body: (__VLS_ctx.t('scheduler.selectBody')),
        }));
        const __VLS_62 = __VLS_61({
            headline: (__VLS_ctx.t('scheduler.selectTitle')),
            body: (__VLS_ctx.t('scheduler.selectBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_61));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ class: "col-span-3 flex flex-col gap-2" },
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
const __VLS_64 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
    modelValue: (__VLS_ctx.showNewModal),
    title: (__VLS_ctx.t('scheduler.newTitle')),
}));
const __VLS_66 = __VLS_65({
    modelValue: (__VLS_ctx.showNewModal),
    title: (__VLS_ctx.t('scheduler.newTitle')),
}, ...__VLS_functionalComponentArgsRest(__VLS_65));
__VLS_67.slots.default;
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
    const __VLS_68 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_69 = __VLS_asFunctionalComponent(__VLS_68, new __VLS_68({
        variant: "error",
    }));
    const __VLS_70 = __VLS_69({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_69));
    __VLS_71.slots.default;
    (__VLS_ctx.newError);
    var __VLS_71;
}
{
    const { actions: __VLS_thisSlot } = __VLS_67.slots;
    const __VLS_72 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_74 = __VLS_73({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_73));
    let __VLS_76;
    let __VLS_77;
    let __VLS_78;
    const __VLS_79 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showNewModal = false;
        }
    };
    __VLS_75.slots.default;
    (__VLS_ctx.t('common.cancel'));
    var __VLS_75;
    const __VLS_80 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_81 = __VLS_asFunctionalComponent(__VLS_80, new __VLS_80({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.state.busy.value),
    }));
    const __VLS_82 = __VLS_81({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.state.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_81));
    let __VLS_84;
    let __VLS_85;
    let __VLS_86;
    const __VLS_87 = {
        onClick: (__VLS_ctx.createScheduler)
    };
    __VLS_83.slots.default;
    (__VLS_ctx.t('scheduler.create'));
    var __VLS_83;
}
var __VLS_67;
const __VLS_88 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_89 = __VLS_asFunctionalComponent(__VLS_88, new __VLS_88({
    modelValue: (__VLS_ctx.showDeleteModal),
    title: (__VLS_ctx.t('scheduler.deleteTitle')),
}));
const __VLS_90 = __VLS_89({
    modelValue: (__VLS_ctx.showDeleteModal),
    title: (__VLS_ctx.t('scheduler.deleteTitle')),
}, ...__VLS_functionalComponentArgsRest(__VLS_89));
__VLS_91.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
(__VLS_ctx.t('scheduler.deleteBody', { name: __VLS_ctx.selectedName }));
{
    const { actions: __VLS_thisSlot } = __VLS_91.slots;
    const __VLS_92 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_93 = __VLS_asFunctionalComponent(__VLS_92, new __VLS_92({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_94 = __VLS_93({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_93));
    let __VLS_96;
    let __VLS_97;
    let __VLS_98;
    const __VLS_99 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showDeleteModal = false;
        }
    };
    __VLS_95.slots.default;
    (__VLS_ctx.t('common.cancel'));
    var __VLS_95;
    const __VLS_100 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_101 = __VLS_asFunctionalComponent(__VLS_100, new __VLS_100({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.state.busy.value),
    }));
    const __VLS_102 = __VLS_101({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.state.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_101));
    let __VLS_104;
    let __VLS_105;
    let __VLS_106;
    const __VLS_107 = {
        onClick: (__VLS_ctx.confirmDelete)
    };
    __VLS_103.slots.default;
    (__VLS_ctx.t('common.delete'));
    var __VLS_103;
}
var __VLS_91;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-12']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
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
/** @type {__VLS_StyleScopedClasses['col-span-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
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
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VEmptyState: VEmptyState,
            VModal: VModal,
            t: t,
            state: state,
            selectedName: selectedName,
            yamlDraft: yamlDraft,
            banner: banner,
            showNewModal: showNewModal,
            newName: newName,
            newError: newError,
            showDeleteModal: showDeleteModal,
            isModified: isModified,
            sortedSchedulers: sortedSchedulers,
            selectScheduler: selectScheduler,
            refreshList: refreshList,
            openNewModal: openNewModal,
            createScheduler: createScheduler,
            fireCurrent: fireCurrent,
            saveCurrent: saveCurrent,
            confirmDelete: confirmDelete,
            formatTimestamp: formatTimestamp,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=SchedulerTab.vue.js.map