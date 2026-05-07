import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VCheckbox, VEmptyState, VInput } from '@/components';
import { useExecutions, useExecutionTail } from '@/composables/useExecutions';
const props = defineProps();
const state = useExecutions();
const tailState = useExecutionTail();
/** Mongo-style id of the row that's currently expanded; null when none. */
const expandedId = ref(null);
const expandedStream = ref('stdout');
const tailLines = ref(100);
const search = ref('');
watch(() => props.projectId, (next) => {
    expandedId.value = null;
    tailState.clear();
    if (next)
        void state.load(next);
    else
        state.clear();
}, { immediate: true });
function refresh() {
    if (props.projectId)
        void state.load(props.projectId);
}
async function toggleExpand(row) {
    if (expandedId.value === row.id) {
        expandedId.value = null;
        tailState.clear();
        return;
    }
    expandedId.value = row.id;
    expandedStream.value = 'stdout';
    await loadTail(row.id);
}
async function loadTail(id) {
    if (!props.projectId)
        return;
    await tailState.load(props.projectId, id, tailLines.value, expandedStream.value);
}
async function switchStream(stream) {
    expandedStream.value = stream;
    if (expandedId.value)
        await loadTail(expandedId.value);
}
const filteredRows = computed(() => {
    const q = search.value.trim().toLowerCase();
    if (!q)
        return state.list.value;
    return state.list.value.filter(r => (r.command ?? '').toLowerCase().includes(q)
        || (r.id ?? '').toLowerCase().includes(q)
        || (r.owner ?? '').toLowerCase().includes(q)
        || (r.sessionId ?? '').toLowerCase().includes(q));
});
function statusClass(status) {
    switch (status) {
        case 'RUNNING': return 'badge-status badge-status--running';
        case 'COMPLETED': return 'badge-status badge-status--completed';
        case 'FAILED': return 'badge-status badge-status--failed';
        case 'KILLED': return 'badge-status badge-status--killed';
        case 'ORPHANED': return 'badge-status badge-status--orphaned';
        default: return 'badge-status';
    }
}
function fmtTime(value) {
    if (value == null)
        return '—';
    if (value instanceof Date)
        return value.toISOString().replace('T', ' ').slice(0, 19);
    // brainFetch returns ISO strings; trim to date+time for the table.
    return String(value).replace('T', ' ').slice(0, 19);
}
function shortId(id) {
    // Long uuid-style ids get a leading-/trailing-slice render so the table stays narrow.
    return id.length > 14 ? id.slice(0, 6) + '…' + id.slice(-6) : id;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['stream-btn']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3 p-4" },
});
if (!__VLS_ctx.projectId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-60 text-sm" },
    });
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-wrap items-end gap-3 text-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-w-48" },
    });
    const __VLS_0 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        modelValue: (__VLS_ctx.search),
        label: "Search",
        placeholder: "command, id, owner, session…",
    }));
    const __VLS_2 = __VLS_1({
        modelValue: (__VLS_ctx.search),
        label: "Search",
        placeholder: "command, id, owner, session…",
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-2 items-center" },
    });
    const __VLS_4 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.state.filters.onlyRunning),
        label: "running only",
    }));
    const __VLS_6 = __VLS_5({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.state.filters.onlyRunning),
        label: "running only",
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
    let __VLS_8;
    let __VLS_9;
    let __VLS_10;
    const __VLS_11 = {
        'onUpdate:modelValue': ((v) => { __VLS_ctx.state.filters.onlyRunning = v; __VLS_ctx.refresh(); })
    };
    var __VLS_7;
    const __VLS_12 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_14 = __VLS_13({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    let __VLS_16;
    let __VLS_17;
    let __VLS_18;
    const __VLS_19 = {
        onClick: (__VLS_ctx.refresh)
    };
    __VLS_15.slots.default;
    var __VLS_15;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-60 ml-auto" },
    });
    (__VLS_ctx.filteredRows.length);
    (__VLS_ctx.state.list.value.length);
    if (__VLS_ctx.state.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-60" },
        });
    }
    else if (__VLS_ctx.state.error.value) {
        const __VLS_20 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
            variant: "error",
        }));
        const __VLS_22 = __VLS_21({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_21));
        __VLS_23.slots.default;
        (__VLS_ctx.state.error.value);
        var __VLS_23;
    }
    else if (__VLS_ctx.state.list.value.length === 0) {
        const __VLS_24 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
            headline: ('No executions'),
            body: ('No shell jobs are tracked for this project yet. Start one via exec_run / client_exec_run.'),
        }));
        const __VLS_26 = __VLS_25({
            headline: ('No executions'),
            body: ('No shell jobs are tracked for this project yet. Start one via exec_run / client_exec_run.'),
        }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
            ...{ class: "table table-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.thead, __VLS_intrinsicElements.thead)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "w-32" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "w-24" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "w-24" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "w-44" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "w-44" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "w-16" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "w-12" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
        if (__VLS_ctx.filteredRows.length === 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                colspan: "8",
                ...{ class: "opacity-60 text-center py-4" },
            });
        }
        for (const [row] of __VLS_getVForSourceType((__VLS_ctx.filteredRows))) {
            (row.id);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectId))
                            return;
                        if (!!(__VLS_ctx.state.loading.value))
                            return;
                        if (!!(__VLS_ctx.state.error.value))
                            return;
                        if (!!(__VLS_ctx.state.list.value.length === 0))
                            return;
                        __VLS_ctx.toggleExpand(row);
                    } },
                ...{ class: "cursor-pointer hover:bg-base-200/40" },
                ...{ class: ({ 'bg-base-200/40': __VLS_ctx.expandedId === row.id }) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "font-mono text-xs" },
                title: (row.id),
            });
            (__VLS_ctx.shortId(row.id));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "text-xs opacity-80" },
            });
            (row.owner);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: (__VLS_ctx.statusClass(row.status)) },
            });
            (row.status.toLowerCase());
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "font-mono text-xs truncate max-w-[28rem]" },
                title: (row.command),
            });
            (row.command);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "text-xs opacity-70" },
            });
            (__VLS_ctx.fmtTime(row.startedAt));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "text-xs opacity-70" },
            });
            (__VLS_ctx.fmtTime(row.lastOutputAt));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "text-xs opacity-80" },
            });
            (row.exitCode ?? '—');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "text-xs" },
            });
            (__VLS_ctx.expandedId === row.id ? '▾' : '▸');
            if (__VLS_ctx.expandedId === row.id) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
                __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                    colspan: "8",
                    ...{ class: "bg-base-200/20 py-3" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex flex-col gap-2" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex flex-wrap gap-x-4 gap-y-1 text-xs" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-60" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "font-mono" },
                });
                (row.id);
                if (row.sessionId) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "opacity-60" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono" },
                    });
                    (row.sessionId);
                }
                if (row.processId) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "opacity-60" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono" },
                    });
                    (row.processId);
                }
                if (row.dirName) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "opacity-60" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono" },
                    });
                    (row.dirName);
                }
                if (row.endedAt) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "opacity-60" },
                    });
                    (__VLS_ctx.fmtTime(row.endedAt));
                }
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex items-center gap-3 text-xs" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex gap-1" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                    ...{ onClick: (...[$event]) => {
                            if (!!(!__VLS_ctx.projectId))
                                return;
                            if (!!(__VLS_ctx.state.loading.value))
                                return;
                            if (!!(__VLS_ctx.state.error.value))
                                return;
                            if (!!(__VLS_ctx.state.list.value.length === 0))
                                return;
                            if (!(__VLS_ctx.expandedId === row.id))
                                return;
                            __VLS_ctx.switchStream('stdout');
                        } },
                    type: "button",
                    ...{ class: "stream-btn" },
                    ...{ class: ({ 'stream-btn--active': __VLS_ctx.expandedStream === 'stdout' }) },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                    ...{ onClick: (...[$event]) => {
                            if (!!(!__VLS_ctx.projectId))
                                return;
                            if (!!(__VLS_ctx.state.loading.value))
                                return;
                            if (!!(__VLS_ctx.state.error.value))
                                return;
                            if (!!(__VLS_ctx.state.list.value.length === 0))
                                return;
                            if (!(__VLS_ctx.expandedId === row.id))
                                return;
                            __VLS_ctx.switchStream('stderr');
                        } },
                    type: "button",
                    ...{ class: "stream-btn" },
                    ...{ class: ({ 'stream-btn--active': __VLS_ctx.expandedStream === 'stderr' }) },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
                    ...{ class: "flex items-center gap-1" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-70" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
                    ...{ onChange: (...[$event]) => {
                            if (!!(!__VLS_ctx.projectId))
                                return;
                            if (!!(__VLS_ctx.state.loading.value))
                                return;
                            if (!!(__VLS_ctx.state.error.value))
                                return;
                            if (!!(__VLS_ctx.state.list.value.length === 0))
                                return;
                            if (!(__VLS_ctx.expandedId === row.id))
                                return;
                            __VLS_ctx.loadTail(row.id);
                        } },
                    type: "number",
                    min: "10",
                    max: "1000",
                    ...{ class: "w-16 px-1 py-0.5 rounded border border-base-300 bg-base-100 text-xs" },
                });
                (__VLS_ctx.tailLines);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-70" },
                });
                const __VLS_28 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_30 = __VLS_29({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_29));
                let __VLS_32;
                let __VLS_33;
                let __VLS_34;
                const __VLS_35 = {
                    onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectId))
                            return;
                        if (!!(__VLS_ctx.state.loading.value))
                            return;
                        if (!!(__VLS_ctx.state.error.value))
                            return;
                        if (!!(__VLS_ctx.state.list.value.length === 0))
                            return;
                        if (!(__VLS_ctx.expandedId === row.id))
                            return;
                        __VLS_ctx.loadTail(row.id);
                    }
                };
                __VLS_31.slots.default;
                var __VLS_31;
                if (__VLS_ctx.tailState.error.value) {
                    const __VLS_36 = {}.VAlert;
                    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                    // @ts-ignore
                    const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
                        variant: "error",
                    }));
                    const __VLS_38 = __VLS_37({
                        variant: "error",
                    }, ...__VLS_functionalComponentArgsRest(__VLS_37));
                    __VLS_39.slots.default;
                    (__VLS_ctx.tailState.error.value);
                    var __VLS_39;
                }
                else if (__VLS_ctx.tailState.loading.value) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "text-xs opacity-60" },
                    });
                    (__VLS_ctx.expandedStream);
                }
                else if (__VLS_ctx.tailState.tail.value) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                        ...{ class: "output-pane" },
                    });
                    (__VLS_ctx.tailState.tail.value.lines.length === 0 ? '(empty)' : __VLS_ctx.tailState.tail.value.lines.join('\n'));
                }
            }
        }
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['items-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-48']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['table']} */ ;
/** @type {__VLS_StyleScopedClasses['table-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['w-24']} */ ;
/** @type {__VLS_StyleScopedClasses['w-24']} */ ;
/** @type {__VLS_StyleScopedClasses['w-44']} */ ;
/** @type {__VLS_StyleScopedClasses['w-44']} */ ;
/** @type {__VLS_StyleScopedClasses['w-16']} */ ;
/** @type {__VLS_StyleScopedClasses['w-12']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['py-4']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-[28rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/20']} */ ;
/** @type {__VLS_StyleScopedClasses['py-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['stream-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['stream-btn--active']} */ ;
/** @type {__VLS_StyleScopedClasses['stream-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['stream-btn--active']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['w-16']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['output-pane']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VCheckbox: VCheckbox,
            VEmptyState: VEmptyState,
            VInput: VInput,
            state: state,
            tailState: tailState,
            expandedId: expandedId,
            expandedStream: expandedStream,
            tailLines: tailLines,
            search: search,
            refresh: refresh,
            toggleExpand: toggleExpand,
            loadTail: loadTail,
            switchStream: switchStream,
            filteredRows: filteredRows,
            statusClass: statusClass,
            fmtTime: fmtTime,
            shortId: shortId,
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
//# sourceMappingURL=ExecutionsTab.vue.js.map