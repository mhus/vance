import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VTextarea, } from '@/components';
import { useWorkflows } from '@/composables/useWorkflows';
const props = defineProps();
const { t } = useI18n();
const state = useWorkflows();
const selectedName = ref(null);
const subTab = ref('definition');
/**
 * Per-parameter input state. Keyed by parameter name; values are
 * always strings in the UI and parsed against the schema on submit
 * (integer/boolean coercion + JSON parse for object/array).
 */
const paramInputs = ref({});
const paramBooleans = ref({});
/** Trigger result / error displayed in the Definition tab. */
const triggerError = ref(null);
watch(() => props.projectId, (next) => {
    selectedName.value = null;
    subTab.value = 'definition';
    state.clearCurrent();
    state.clearLastResult();
    triggerError.value = null;
    paramInputs.value = {};
    paramBooleans.value = {};
    if (next) {
        void state.loadProject(next);
    }
    else {
        state.workflows.value = [];
    }
}, { immediate: true });
async function selectWorkflow(name) {
    if (!props.projectId)
        return;
    selectedName.value = name;
    subTab.value = 'definition';
    state.clearLastResult();
    triggerError.value = null;
    paramInputs.value = {};
    paramBooleans.value = {};
    await state.loadOne(props.projectId, name);
    // Eagerly load runs too — counter in the tab label needs them.
    await state.loadRuns(props.projectId, name);
    // Seed param inputs with defaults so the form is pre-populated.
    const params = state.current.value?.parameters;
    if (params) {
        for (const [key, spec] of Object.entries(params)) {
            if (spec.type === 'boolean') {
                paramBooleans.value[key] = Boolean(spec.defaultValue ?? false);
            }
            else if (spec.defaultValue != null) {
                paramInputs.value[key] = typeof spec.defaultValue === 'string'
                    ? spec.defaultValue
                    : JSON.stringify(spec.defaultValue);
            }
            else {
                paramInputs.value[key] = '';
            }
        }
    }
}
function sourceLabel(source) {
    return String(source) === 'PROJECT' ? 'project' : '_vance';
}
function sourceClass(source) {
    return String(source) === 'PROJECT'
        ? 'badge-source badge-source--project'
        : 'badge-source badge-source--vance';
}
function runStatusClass(status) {
    switch (String(status)) {
        case 'DONE': return 'badge-run badge-run--done';
        case 'FAILED': return 'badge-run badge-run--failed';
        case 'TERMINATED': return 'badge-run badge-run--terminated';
        case 'PAUSED': return 'badge-run badge-run--paused';
        case 'RUNNING':
        default:
            return 'badge-run badge-run--running';
    }
}
const detail = computed(() => state.current.value);
const sortedWorkflows = computed(() => [...state.workflows.value].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? '')));
/**
 * Number of runs currently in {@code RUNNING} status. Drives the
 * "Runs (N running)" badge in the sub-tab label so the operator
 * doesn't have to switch tabs to know whether something is live.
 */
const runningCount = computed(() => state.runs.value.filter((r) => String(r.status) === 'RUNNING').length);
const runsTabLabel = computed(() => {
    const total = state.runs.value.length;
    if (runningCount.value > 0) {
        return t('insights.workflows.runsTabWithRunning', {
            total,
            running: runningCount.value,
        });
    }
    return t('insights.workflows.runsTab', { total });
});
const paramEntries = computed(() => {
    const params = detail.value?.parameters;
    if (!params)
        return [];
    return Object.entries(params);
});
/**
 * Parses a param value from the form input. Throws with a
 * field-context message on coercion failure so the user sees which
 * field is bad. Returns {@code undefined} for an empty optional —
 * lets the caller omit the key entirely.
 */
function parseParamValue(key, spec) {
    if (spec.type === 'boolean') {
        return paramBooleans.value[key] ?? false;
    }
    const raw = (paramInputs.value[key] ?? '').trim();
    if (raw === '') {
        if (spec.required) {
            throw new Error(t('insights.workflows.paramRequired', { key }));
        }
        return undefined;
    }
    switch (spec.type) {
        case 'integer': {
            const n = Number.parseInt(raw, 10);
            if (Number.isNaN(n)) {
                throw new Error(t('insights.workflows.paramNotInteger', { key, raw }));
            }
            return n;
        }
        case 'object':
        case 'array': {
            try {
                return JSON.parse(raw);
            }
            catch (e) {
                throw new Error(t('insights.workflows.paramNotJson', {
                    key,
                    error: e instanceof Error ? e.message : String(e),
                }));
            }
        }
        default:
            return raw;
    }
}
async function onTrigger() {
    if (!props.projectId || !detail.value)
        return;
    triggerError.value = null;
    // Walk the parameter schema; an unparseable required field is fatal.
    const collected = {};
    try {
        for (const [key, spec] of paramEntries.value) {
            const v = parseParamValue(key, spec);
            if (v !== undefined)
                collected[key] = v;
        }
    }
    catch (e) {
        triggerError.value = e instanceof Error ? e.message : String(e);
        return;
    }
    try {
        await state.start(props.projectId, detail.value.name, collected);
        // Refresh the runs list so the new run appears under the Runs tab.
        await state.loadRuns(props.projectId, detail.value.name);
    }
    catch (e) {
        triggerError.value =
            e instanceof Error ? e.message : t('insights.workflows.triggerGenericError');
    }
}
async function refreshRuns() {
    if (!props.projectId || !detail.value)
        return;
    await state.loadRuns(props.projectId, detail.value.name);
}
function fmt(value) {
    if (value == null)
        return '—';
    if (value instanceof Date)
        return value.toISOString();
    return String(value);
}
function paramFieldType(spec) {
    switch (spec.type) {
        case 'integer': return 'integer';
        case 'boolean': return 'boolean';
        case 'object':
        case 'array':
            return 'json';
        default:
            return 'text';
    }
}
function processSelectHandler(run) {
    // No-op for now — clicking a run could open a journal modal later.
    // Keep the click handler in place so callers wire it without a re-fetch.
    void run;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['wf-row']} */ ;
/** @type {__VLS_StyleScopedClasses['sub-tab']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3 p-2" },
});
if (!__VLS_ctx.projectId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-60 text-sm" },
    });
    (__VLS_ctx.$t('insights.workflows.pickProject'));
}
else {
    if (__VLS_ctx.state.error.value) {
        const __VLS_0 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
            variant: "error",
        }));
        const __VLS_2 = __VLS_1({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
        __VLS_3.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.state.error.value);
        var __VLS_3;
    }
    if (__VLS_ctx.state.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-60" },
        });
        (__VLS_ctx.$t('insights.workflows.loading'));
    }
    else if (__VLS_ctx.state.workflows.value.length === 0) {
        const __VLS_4 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
            headline: (__VLS_ctx.$t('insights.workflows.emptyHeadline')),
            body: (__VLS_ctx.$t('insights.workflows.emptyBody')),
        }));
        const __VLS_6 = __VLS_5({
            headline: (__VLS_ctx.$t('insights.workflows.emptyHeadline')),
            body: (__VLS_ctx.$t('insights.workflows.emptyBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_5));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "grid grid-cols-12 gap-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
            ...{ class: "col-span-5 flex flex-col gap-1" },
        });
        for (const [wf] of __VLS_getVForSourceType((__VLS_ctx.sortedWorkflows))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectId))
                            return;
                        if (!!(__VLS_ctx.state.loading.value))
                            return;
                        if (!!(__VLS_ctx.state.workflows.value.length === 0))
                            return;
                        __VLS_ctx.selectWorkflow(wf.name);
                    } },
                key: (wf.name),
                type: "button",
                ...{ class: "wf-row" },
                ...{ class: ({ 'wf-row--active': __VLS_ctx.selectedName === wf.name }) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center justify-between gap-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono text-sm truncate" },
            });
            (wf.name);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: (__VLS_ctx.sourceClass(wf.source)) },
                ...{ class: "text-xs" },
            });
            (__VLS_ctx.sourceLabel(wf.source));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60 truncate mt-0.5" },
            });
            (__VLS_ctx.$t('insights.workflows.summaryLine', {
                params: wf.paramCount,
                states: wf.stateCount,
            }));
            if (wf.version) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (wf.version);
            }
            if (wf.description) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-xs opacity-60 truncate mt-0.5" },
                    title: (wf.description),
                });
                (wf.description);
            }
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "col-span-7 flex flex-col gap-3" },
        });
        if (!__VLS_ctx.detail) {
            const __VLS_8 = {}.VEmptyState;
            /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
            // @ts-ignore
            const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
                headline: (__VLS_ctx.$t('insights.workflows.selectHeadline')),
                body: (__VLS_ctx.$t('insights.workflows.selectBody')),
            }));
            const __VLS_10 = __VLS_9({
                headline: (__VLS_ctx.$t('insights.workflows.selectHeadline')),
                body: (__VLS_ctx.$t('insights.workflows.selectBody')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_9));
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "sub-tab-bar" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectId))
                            return;
                        if (!!(__VLS_ctx.state.loading.value))
                            return;
                        if (!!(__VLS_ctx.state.workflows.value.length === 0))
                            return;
                        if (!!(!__VLS_ctx.detail))
                            return;
                        __VLS_ctx.subTab = 'definition';
                    } },
                ...{ class: "sub-tab" },
                ...{ class: ({ 'sub-tab--active': __VLS_ctx.subTab === 'definition' }) },
            });
            (__VLS_ctx.$t('insights.workflows.definitionTab'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectId))
                            return;
                        if (!!(__VLS_ctx.state.loading.value))
                            return;
                        if (!!(__VLS_ctx.state.workflows.value.length === 0))
                            return;
                        if (!!(!__VLS_ctx.detail))
                            return;
                        __VLS_ctx.subTab = 'runs';
                    } },
                ...{ class: "sub-tab" },
                ...{ class: ({ 'sub-tab--active': __VLS_ctx.subTab === 'runs' }) },
            });
            (__VLS_ctx.runsTabLabel);
            if (__VLS_ctx.subTab === 'definition') {
                const __VLS_12 = {}.VCard;
                /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                // @ts-ignore
                const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
                    title: (__VLS_ctx.detail.name),
                }));
                const __VLS_14 = __VLS_13({
                    title: (__VLS_ctx.detail.name),
                }, ...__VLS_functionalComponentArgsRest(__VLS_13));
                __VLS_15.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
                    ...{ class: "grid grid-cols-3 gap-x-3 gap-y-1 text-sm" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                    ...{ class: "opacity-60 col-span-1" },
                });
                (__VLS_ctx.$t('insights.workflows.detail.start'));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                    ...{ class: "col-span-2 font-mono" },
                });
                (__VLS_ctx.detail.start ?? '—');
                __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                    ...{ class: "opacity-60 col-span-1" },
                });
                (__VLS_ctx.$t('insights.workflows.detail.source'));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                    ...{ class: "col-span-2" },
                });
                (__VLS_ctx.sourceLabel(__VLS_ctx.detail.source));
                if (__VLS_ctx.detail.version) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                        ...{ class: "opacity-60 col-span-1" },
                    });
                    (__VLS_ctx.$t('insights.workflows.detail.version'));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                        ...{ class: "col-span-2" },
                    });
                    (__VLS_ctx.detail.version);
                }
                if (__VLS_ctx.detail.description) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                        ...{ class: "opacity-60 col-span-1" },
                    });
                    (__VLS_ctx.$t('insights.workflows.detail.description'));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                        ...{ class: "col-span-2" },
                    });
                    (__VLS_ctx.detail.description);
                }
                if (__VLS_ctx.detail.allowedTools && __VLS_ctx.detail.allowedTools.length > 0) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                        ...{ class: "opacity-60 col-span-1" },
                    });
                    (__VLS_ctx.$t('insights.workflows.detail.allowedTools'));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                        ...{ class: "col-span-2 text-xs" },
                    });
                    for (const [tool] of __VLS_getVForSourceType((__VLS_ctx.detail.allowedTools))) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            key: (tool),
                            ...{ class: "inline-block mr-1 px-1.5 py-0.5 rounded bg-base-300/50 font-mono" },
                        });
                        (tool);
                    }
                }
                if (__VLS_ctx.detail.yaml) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.details, __VLS_intrinsicElements.details)({
                        ...{ class: "mt-3" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.summary, __VLS_intrinsicElements.summary)({
                        ...{ class: "text-xs opacity-70 cursor-pointer" },
                    });
                    (__VLS_ctx.$t('insights.workflows.detail.rawYaml'));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                        ...{ class: "json-block" },
                    });
                    (__VLS_ctx.detail.yaml);
                }
                var __VLS_15;
                const __VLS_16 = {}.VCard;
                /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                // @ts-ignore
                const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
                    title: (__VLS_ctx.$t('insights.workflows.trigger.title')),
                }));
                const __VLS_18 = __VLS_17({
                    title: (__VLS_ctx.$t('insights.workflows.trigger.title')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_17));
                __VLS_19.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                    ...{ class: "text-xs opacity-70 mb-2" },
                });
                (__VLS_ctx.$t('insights.workflows.trigger.help'));
                if (__VLS_ctx.paramEntries.length === 0) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "text-xs opacity-60 italic" },
                    });
                    (__VLS_ctx.$t('insights.workflows.trigger.noParams'));
                }
                else {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "flex flex-col gap-2" },
                    });
                    for (const [[key, spec]] of __VLS_getVForSourceType((__VLS_ctx.paramEntries))) {
                        (key);
                        if (__VLS_ctx.paramFieldType(spec) === 'boolean') {
                            const __VLS_20 = {}.VCheckbox;
                            /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
                            // @ts-ignore
                            const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
                                modelValue: (__VLS_ctx.paramBooleans[key]),
                                label: (`${key}${spec.required ? ' *' : ''}`),
                            }));
                            const __VLS_22 = __VLS_21({
                                modelValue: (__VLS_ctx.paramBooleans[key]),
                                label: (`${key}${spec.required ? ' *' : ''}`),
                            }, ...__VLS_functionalComponentArgsRest(__VLS_21));
                        }
                        else if (__VLS_ctx.paramFieldType(spec) === 'json') {
                            const __VLS_24 = {}.VTextarea;
                            /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
                            // @ts-ignore
                            const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
                                modelValue: (__VLS_ctx.paramInputs[key]),
                                label: (`${key}${spec.required ? ' *' : ''} (${spec.type})`),
                                rows: (4),
                                ...{ class: "font-mono" },
                            }));
                            const __VLS_26 = __VLS_25({
                                modelValue: (__VLS_ctx.paramInputs[key]),
                                label: (`${key}${spec.required ? ' *' : ''} (${spec.type})`),
                                rows: (4),
                                ...{ class: "font-mono" },
                            }, ...__VLS_functionalComponentArgsRest(__VLS_25));
                        }
                        else {
                            const __VLS_28 = {}.VInput;
                            /** @type {[typeof __VLS_components.VInput, ]} */ ;
                            // @ts-ignore
                            const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
                                modelValue: (__VLS_ctx.paramInputs[key]),
                                label: (`${key}${spec.required ? ' *' : ''} (${spec.type})`),
                                placeholder: (spec.defaultValue != null
                                    ? String(spec.defaultValue)
                                    : ''),
                            }));
                            const __VLS_30 = __VLS_29({
                                modelValue: (__VLS_ctx.paramInputs[key]),
                                label: (`${key}${spec.required ? ' *' : ''} (${spec.type})`),
                                placeholder: (spec.defaultValue != null
                                    ? String(spec.defaultValue)
                                    : ''),
                            }, ...__VLS_functionalComponentArgsRest(__VLS_29));
                        }
                    }
                }
                if (__VLS_ctx.triggerError) {
                    const __VLS_32 = {}.VAlert;
                    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                    // @ts-ignore
                    const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
                        variant: "error",
                        ...{ class: "mt-3" },
                    }));
                    const __VLS_34 = __VLS_33({
                        variant: "error",
                        ...{ class: "mt-3" },
                    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
                    __VLS_35.slots.default;
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    (__VLS_ctx.triggerError);
                    var __VLS_35;
                }
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex items-center gap-2 mt-3" },
                });
                const __VLS_36 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
                    ...{ 'onClick': {} },
                    loading: (__VLS_ctx.state.busy.value),
                    variant: "primary",
                }));
                const __VLS_38 = __VLS_37({
                    ...{ 'onClick': {} },
                    loading: (__VLS_ctx.state.busy.value),
                    variant: "primary",
                }, ...__VLS_functionalComponentArgsRest(__VLS_37));
                let __VLS_40;
                let __VLS_41;
                let __VLS_42;
                const __VLS_43 = {
                    onClick: (__VLS_ctx.onTrigger)
                };
                __VLS_39.slots.default;
                (__VLS_ctx.$t('insights.workflows.trigger.button'));
                var __VLS_39;
                if (__VLS_ctx.state.lastResult.value) {
                    const __VLS_44 = {}.VAlert;
                    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                    // @ts-ignore
                    const __VLS_45 = __VLS_asFunctionalComponent(__VLS_44, new __VLS_44({
                        variant: "success",
                        ...{ class: "mt-3" },
                    }));
                    const __VLS_46 = __VLS_45({
                        variant: "success",
                        ...{ class: "mt-3" },
                    }, ...__VLS_functionalComponentArgsRest(__VLS_45));
                    __VLS_47.slots.default;
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "flex flex-col gap-1 text-sm" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    (__VLS_ctx.$t('insights.workflows.trigger.spawnedPrefix'));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono" },
                    });
                    (__VLS_ctx.state.lastResult.value.workflowName);
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    (__VLS_ctx.$t('insights.workflows.trigger.runIdLabel'));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!!(!__VLS_ctx.projectId))
                                    return;
                                if (!!(__VLS_ctx.state.loading.value))
                                    return;
                                if (!!(__VLS_ctx.state.workflows.value.length === 0))
                                    return;
                                if (!!(!__VLS_ctx.detail))
                                    return;
                                if (!(__VLS_ctx.subTab === 'definition'))
                                    return;
                                if (!(__VLS_ctx.state.lastResult.value))
                                    return;
                                __VLS_ctx.subTab = 'runs';
                            } },
                        ...{ class: "link" },
                    });
                    (__VLS_ctx.state.lastResult.value.workflowRunId);
                    var __VLS_47;
                }
                var __VLS_19;
            }
            else {
                const __VLS_48 = {}.VCard;
                /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                // @ts-ignore
                const __VLS_49 = __VLS_asFunctionalComponent(__VLS_48, new __VLS_48({
                    title: (__VLS_ctx.$t('insights.workflows.runs.title')),
                }));
                const __VLS_50 = __VLS_49({
                    title: (__VLS_ctx.$t('insights.workflows.runs.title')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_49));
                __VLS_51.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex items-center justify-between mb-2" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs opacity-70" },
                });
                (__VLS_ctx.$t('insights.workflows.runs.subtitle', {
                    total: __VLS_ctx.state.runs.value.length,
                }));
                const __VLS_52 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_54 = __VLS_53({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_53));
                let __VLS_56;
                let __VLS_57;
                let __VLS_58;
                const __VLS_59 = {
                    onClick: (__VLS_ctx.refreshRuns)
                };
                __VLS_55.slots.default;
                (__VLS_ctx.$t('insights.workflows.runs.refresh'));
                var __VLS_55;
                if (__VLS_ctx.state.runs.value.length === 0) {
                    const __VLS_60 = {}.VEmptyState;
                    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                    // @ts-ignore
                    const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
                        headline: (__VLS_ctx.$t('insights.workflows.runs.emptyHeadline')),
                        body: (__VLS_ctx.$t('insights.workflows.runs.emptyBody')),
                    }));
                    const __VLS_62 = __VLS_61({
                        headline: (__VLS_ctx.$t('insights.workflows.runs.emptyHeadline')),
                        body: (__VLS_ctx.$t('insights.workflows.runs.emptyBody')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_61));
                }
                else {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
                        ...{ class: "flex flex-col divide-y divide-base-300" },
                    });
                    for (const [run] of __VLS_getVForSourceType((__VLS_ctx.state.runs.value))) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                            ...{ onClick: (...[$event]) => {
                                    if (!!(!__VLS_ctx.projectId))
                                        return;
                                    if (!!(__VLS_ctx.state.loading.value))
                                        return;
                                    if (!!(__VLS_ctx.state.workflows.value.length === 0))
                                        return;
                                    if (!!(!__VLS_ctx.detail))
                                        return;
                                    if (!!(__VLS_ctx.subTab === 'definition'))
                                        return;
                                    if (!!(__VLS_ctx.state.runs.value.length === 0))
                                        return;
                                    __VLS_ctx.processSelectHandler(run);
                                } },
                            key: (run.workflowRunId),
                            ...{ class: "py-2 px-2 hover:bg-base-200/40 rounded cursor-default" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: "flex items-center justify-between gap-2" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            ...{ class: "font-mono text-sm" },
                        });
                        (run.workflowRunId);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            ...{ class: (__VLS_ctx.runStatusClass(run.status)) },
                            ...{ class: "text-xs" },
                        });
                        (run.status);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: "text-xs opacity-60 mt-0.5 flex flex-wrap gap-x-3" },
                        });
                        if (run.currentState) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                            (__VLS_ctx.$t('insights.workflows.runs.state'));
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                                ...{ class: "font-mono" },
                            });
                            (run.currentState);
                        }
                        if (run.startedBy) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                            (__VLS_ctx.$t('insights.workflows.runs.startedBy'));
                            (run.startedBy);
                        }
                        if (run.createdAt) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                            (__VLS_ctx.$t('insights.workflows.runs.startedAt'));
                            (__VLS_ctx.fmt(run.createdAt));
                        }
                    }
                }
                var __VLS_51;
            }
        }
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-12']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['wf-row']} */ ;
/** @type {__VLS_StyleScopedClasses['wf-row--active']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-7']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['sub-tab-bar']} */ ;
/** @type {__VLS_StyleScopedClasses['sub-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['sub-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['sub-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['sub-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-block']} */ ;
/** @type {__VLS_StyleScopedClasses['mr-1']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-300/50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['json-block']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['link']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-y']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-default']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VCheckbox: VCheckbox,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VTextarea: VTextarea,
            state: state,
            selectedName: selectedName,
            subTab: subTab,
            paramInputs: paramInputs,
            paramBooleans: paramBooleans,
            triggerError: triggerError,
            selectWorkflow: selectWorkflow,
            sourceLabel: sourceLabel,
            sourceClass: sourceClass,
            runStatusClass: runStatusClass,
            detail: detail,
            sortedWorkflows: sortedWorkflows,
            runsTabLabel: runsTabLabel,
            paramEntries: paramEntries,
            onTrigger: onTrigger,
            refreshRuns: refreshRuns,
            fmt: fmt,
            paramFieldType: paramFieldType,
            processSelectHandler: processSelectHandler,
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
//# sourceMappingURL=WorkflowsTab.vue.js.map