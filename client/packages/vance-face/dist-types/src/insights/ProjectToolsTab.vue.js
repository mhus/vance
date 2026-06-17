import { computed, ref, watch } from 'vue';
import { VAlert, VCheckbox, VEmptyState, VInput } from '@/components';
import { useEffectiveTools, useToolHealth } from '@/composables/useProjectInsights';
const props = defineProps();
const state = useEffectiveTools();
const health = useToolHealth();
watch(() => props.projectId, (next) => {
    if (next) {
        state.load(next);
        health.load(next);
    }
    else {
        state.clear();
        health.clear();
    }
}, { immediate: true });
// Map: toolName → health entry. Fast lookup during render.
const healthByTool = computed(() => {
    const m = new Map();
    for (const h of health.entries.value)
        m.set(h.toolName, h);
    return m;
});
// Live countdown — re-evaluates every 10s so "in 27 minutes" stays fresh.
const nowMs = ref(Date.now());
let nowTimer = null;
if (typeof window !== 'undefined') {
    nowTimer = setInterval(() => {
        nowMs.value = Date.now();
    }, 10_000);
}
import { onUnmounted } from 'vue';
onUnmounted(() => {
    if (nowTimer)
        clearInterval(nowTimer);
});
function formatCountdown(iso) {
    if (!iso)
        return '—';
    const target = Date.parse(iso);
    if (Number.isNaN(target))
        return iso;
    const diffMs = target - nowMs.value;
    if (diffMs <= 0)
        return 'expired';
    const sec = Math.floor(diffMs / 1000);
    if (sec < 60)
        return `${sec}s`;
    const min = Math.floor(sec / 60);
    if (min < 60)
        return `${min}m`;
    const hr = Math.floor(min / 60);
    const remMin = min % 60;
    if (hr < 24)
        return remMin > 0 ? `${hr}h ${remMin}m` : `${hr}h`;
    const days = Math.floor(hr / 24);
    const remHr = hr % 24;
    return remHr > 0 ? `${days}d ${remHr}h` : `${days}d`;
}
function statusBadgeClass(status) {
    switch (status) {
        case 'DOWN':
            return 'badge-health badge-health--down';
        case 'DEGRADED':
            return 'badge-health badge-health--degraded';
        case 'OK':
        default:
            return 'badge-health badge-health--ok';
    }
}
const expanded = ref(new Set());
function toggleExpand(toolName) {
    const next = new Set(expanded.value);
    if (next.has(toolName))
        next.delete(toolName);
    else
        next.add(toolName);
    expanded.value = next;
}
async function onClearCooldown(toolName, cd) {
    if (!props.projectId)
        return;
    await health.clearCooldown(props.projectId, toolName, cd.errorSignature, cd.userId ?? null);
}
function sourceClass(source) {
    switch (source) {
        case 'PROJECT':
            return 'badge-source badge-source--project';
        case 'VANCE':
            return 'badge-source badge-source--vance';
        case 'BUILTIN':
            return 'badge-source badge-source--builtin';
        default:
            return 'badge-source';
    }
}
function sourceLabel(source) {
    switch (source) {
        case 'PROJECT':
            return 'project';
        case 'VANCE':
            return '_vance';
        case 'BUILTIN':
            return 'built-in';
        default:
            return source.toLowerCase();
    }
}
const search = ref('');
const sortKey = ref('name');
const sortAsc = ref(true);
const showProject = ref(true);
const showVance = ref(true);
const showBuiltin = ref(true);
const primaryOnly = ref(false);
const deferredOnly = ref(false);
const showDisabled = ref(true);
function toggleSort(key) {
    if (sortKey.value === key) {
        sortAsc.value = !sortAsc.value;
    }
    else {
        sortKey.value = key;
        sortAsc.value = true;
    }
}
function arrow(key) {
    if (sortKey.value !== key)
        return '';
    return sortAsc.value ? ' ▲' : ' ▼';
}
const filteredTools = computed(() => {
    const all = state.tools.value;
    const q = search.value.trim().toLowerCase();
    const wanted = new Set();
    if (showProject.value)
        wanted.add('PROJECT');
    if (showVance.value)
        wanted.add('VANCE');
    if (showBuiltin.value)
        wanted.add('BUILTIN');
    const out = all.filter((t) => {
        if (!wanted.has(t.source))
            return false;
        if (primaryOnly.value && !t.primary)
            return false;
        if (deferredOnly.value && !t.deferred)
            return false;
        if (!showDisabled.value && t.disabledByInnerLayer)
            return false;
        if (q.length === 0)
            return true;
        return ((t.name ?? '').toLowerCase().includes(q)
            || (t.description ?? '').toLowerCase().includes(q)
            || (t.type ?? '').toLowerCase().includes(q)
            || (t.searchHint ?? '').toLowerCase().includes(q)
            || (t.labels ?? []).some((l) => l.toLowerCase().includes(q)));
    });
    const dir = sortAsc.value ? 1 : -1;
    return [...out].sort((a, b) => {
        const av = (a[sortKey.value] ?? '');
        const bv = (b[sortKey.value] ?? '');
        return av.localeCompare(bv) * dir;
    });
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
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
else if (__VLS_ctx.state.loading.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
}
else if (__VLS_ctx.state.error.value) {
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
    (__VLS_ctx.state.error.value);
    var __VLS_3;
}
else if (__VLS_ctx.state.tools.value.length === 0) {
    const __VLS_4 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        headline: ('No tools'),
        body: ('No built-in, tenant or project tools resolve for this project.'),
    }));
    const __VLS_6 = __VLS_5({
        headline: ('No tools'),
        body: ('No built-in, tenant or project tools resolve for this project.'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-wrap items-end gap-3 text-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-w-48" },
    });
    const __VLS_8 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        modelValue: (__VLS_ctx.search),
        label: "Search",
        placeholder: "name, description, type, label…",
    }));
    const __VLS_10 = __VLS_9({
        modelValue: (__VLS_ctx.search),
        label: "Search",
        placeholder: "name, description, type, label…",
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-2" },
    });
    const __VLS_12 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        modelValue: (__VLS_ctx.showProject),
        label: "project",
    }));
    const __VLS_14 = __VLS_13({
        modelValue: (__VLS_ctx.showProject),
        label: "project",
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    const __VLS_16 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        modelValue: (__VLS_ctx.showVance),
        label: "_vance",
    }));
    const __VLS_18 = __VLS_17({
        modelValue: (__VLS_ctx.showVance),
        label: "_vance",
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    const __VLS_20 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
        modelValue: (__VLS_ctx.showBuiltin),
        label: "built-in",
    }));
    const __VLS_22 = __VLS_21({
        modelValue: (__VLS_ctx.showBuiltin),
        label: "built-in",
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-2" },
    });
    const __VLS_24 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
        modelValue: (__VLS_ctx.primaryOnly),
        label: "primary only",
    }));
    const __VLS_26 = __VLS_25({
        modelValue: (__VLS_ctx.primaryOnly),
        label: "primary only",
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    const __VLS_28 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
        modelValue: (__VLS_ctx.deferredOnly),
        label: "deferred only",
    }));
    const __VLS_30 = __VLS_29({
        modelValue: (__VLS_ctx.deferredOnly),
        label: "deferred only",
    }, ...__VLS_functionalComponentArgsRest(__VLS_29));
    const __VLS_32 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
        modelValue: (__VLS_ctx.showDisabled),
        label: "show disabled",
    }));
    const __VLS_34 = __VLS_33({
        modelValue: (__VLS_ctx.showDisabled),
        label: "show disabled",
    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-60 ml-auto" },
    });
    (__VLS_ctx.filteredTools.length);
    (__VLS_ctx.state.tools.value.length);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
        ...{ class: "table table-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.thead, __VLS_intrinsicElements.thead)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ onClick: (...[$event]) => {
                if (!!(!__VLS_ctx.projectId))
                    return;
                if (!!(__VLS_ctx.state.loading.value))
                    return;
                if (!!(__VLS_ctx.state.error.value))
                    return;
                if (!!(__VLS_ctx.state.tools.value.length === 0))
                    return;
                __VLS_ctx.toggleSort('name');
            } },
        ...{ class: "w-40 cursor-pointer select-none" },
    });
    (__VLS_ctx.arrow('name'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ onClick: (...[$event]) => {
                if (!!(!__VLS_ctx.projectId))
                    return;
                if (!!(__VLS_ctx.state.loading.value))
                    return;
                if (!!(__VLS_ctx.state.error.value))
                    return;
                if (!!(__VLS_ctx.state.tools.value.length === 0))
                    return;
                __VLS_ctx.toggleSort('source');
            } },
        ...{ class: "w-24 cursor-pointer select-none" },
    });
    (__VLS_ctx.arrow('source'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ onClick: (...[$event]) => {
                if (!!(!__VLS_ctx.projectId))
                    return;
                if (!!(__VLS_ctx.state.loading.value))
                    return;
                if (!!(__VLS_ctx.state.error.value))
                    return;
                if (!!(__VLS_ctx.state.tools.value.length === 0))
                    return;
                __VLS_ctx.toggleSort('type');
            } },
        ...{ class: "w-20 cursor-pointer select-none" },
    });
    (__VLS_ctx.arrow('type'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-24" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-28" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-32" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-12" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
    if (__VLS_ctx.filteredTools.length === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            colspan: "8",
            ...{ class: "opacity-60 text-center py-4" },
        });
    }
    for (const [t] of __VLS_getVForSourceType((__VLS_ctx.filteredTools))) {
        (t.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
            ...{ class: (t.disabledByInnerLayer ? 'opacity-50 line-through' : '') },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "font-mono" },
        });
        (t.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: (__VLS_ctx.sourceClass(t.source)) },
        });
        (__VLS_ctx.sourceLabel(t.source));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs opacity-80" },
        });
        (t.type ?? '—');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs opacity-80" },
        });
        (t.description);
        if (t.deferred && t.searchHint) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-[0.65rem] opacity-60 italic mt-0.5" },
                title: (t.searchHint),
            });
            (t.searchHint);
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs" },
        });
        if (t.deferred) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "badge-deferred" },
                title: "Hidden from manifest until describe_tool activates it",
            });
        }
        else if (t.primary) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-success" },
            });
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-50" },
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs" },
        });
        if (__VLS_ctx.healthByTool.get(t.name)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectId))
                            return;
                        if (!!(__VLS_ctx.state.loading.value))
                            return;
                        if (!!(__VLS_ctx.state.error.value))
                            return;
                        if (!!(__VLS_ctx.state.tools.value.length === 0))
                            return;
                        if (!(__VLS_ctx.healthByTool.get(t.name)))
                            return;
                        __VLS_ctx.toggleExpand(t.name);
                    } },
                type: "button",
                ...{ class: "inline-flex items-center gap-1.5 cursor-pointer" },
                title: (__VLS_ctx.healthByTool.get(t.name)?.note ?? ''),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: (__VLS_ctx.statusBadgeClass(__VLS_ctx.healthByTool.get(t.name)?.status)) },
            });
            (__VLS_ctx.healthByTool.get(t.name)?.status);
            if ((__VLS_ctx.healthByTool.get(t.name)?.activeCooldowns?.length ?? 0) > 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-warning text-[0.65rem]" },
                    title: ((__VLS_ctx.healthByTool.get(t.name)?.activeCooldowns?.length) + ' active cooldown(s)'),
                });
                (__VLS_ctx.healthByTool.get(t.name)?.activeCooldowns?.length);
            }
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-40" },
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs" },
        });
        if (t.labels && t.labels.length) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono opacity-70" },
            });
            (t.labels.join(', '));
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-50" },
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
        if (t.disabledByInnerLayer) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs text-error" },
                title: "Disabled by an inner-layer document",
            });
        }
        if (__VLS_ctx.expanded.has(t.name) && __VLS_ctx.healthByTool.get(t.name)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
                ...{ class: "health-detail-row" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                colspan: "8",
                ...{ class: "p-3" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "bg-base-200 rounded p-3 space-y-2 text-xs" },
            });
            if (__VLS_ctx.healthByTool.get(t.name)?.note) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "opacity-80" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-60" },
                });
                (__VLS_ctx.healthByTool.get(t.name)?.note);
            }
            if (__VLS_ctx.healthByTool.get(t.name)?.expectedRecoveryAt) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "opacity-80" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-60" },
                });
                (__VLS_ctx.healthByTool.get(t.name)?.expectedRecoveryAt);
                (__VLS_ctx.formatCountdown(__VLS_ctx.healthByTool.get(t.name)?.expectedRecoveryAt));
            }
            if ((__VLS_ctx.healthByTool.get(t.name)?.activeCooldowns?.length ?? 0) === 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "opacity-60" },
                });
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
                    ...{ class: "table table-xs" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.thead, __VLS_intrinsicElements.thead)({});
                __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
                    ...{ class: "opacity-60" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
                __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
                __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
                __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
                __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
                __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
                __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
                __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
                for (const [cd] of __VLS_getVForSourceType(((__VLS_ctx.healthByTool.get(t.name)?.activeCooldowns ?? [])))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
                        key: (cd.errorSignature + '|' + (cd.userId ?? '*')),
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                        ...{ class: "font-mono" },
                    });
                    (cd.errorSignature);
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
                    (cd.lastClassification ?? '—');
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
                    (cd.hits);
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
                    (cd.userId ?? '*');
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                        ...{ class: "opacity-80" },
                    });
                    (cd.note ?? '—');
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
                    (__VLS_ctx.formatCountdown(cd.nextSpawnAllowedAt));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "opacity-50" },
                    });
                    (cd.nextSpawnAllowedAt);
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!!(!__VLS_ctx.projectId))
                                    return;
                                if (!!(__VLS_ctx.state.loading.value))
                                    return;
                                if (!!(__VLS_ctx.state.error.value))
                                    return;
                                if (!!(__VLS_ctx.state.tools.value.length === 0))
                                    return;
                                if (!(__VLS_ctx.expanded.has(t.name) && __VLS_ctx.healthByTool.get(t.name)))
                                    return;
                                if (!!((__VLS_ctx.healthByTool.get(t.name)?.activeCooldowns?.length ?? 0) === 0))
                                    return;
                                __VLS_ctx.onClearCooldown(t.name, cd);
                            } },
                        type: "button",
                        ...{ class: "btn btn-xs btn-outline" },
                        title: "Clear this cooldown — the tool can fire again immediately",
                    });
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
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
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
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['table']} */ ;
/** @type {__VLS_StyleScopedClasses['table-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-40']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['select-none']} */ ;
/** @type {__VLS_StyleScopedClasses['w-24']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['select-none']} */ ;
/** @type {__VLS_StyleScopedClasses['w-20']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['select-none']} */ ;
/** @type {__VLS_StyleScopedClasses['w-24']} */ ;
/** @type {__VLS_StyleScopedClasses['w-28']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['w-12']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['py-4']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[0.65rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-deferred']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[0.65rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-40']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['health-detail-row']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['table']} */ ;
/** @type {__VLS_StyleScopedClasses['table-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-outline']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VCheckbox: VCheckbox,
            VEmptyState: VEmptyState,
            VInput: VInput,
            state: state,
            healthByTool: healthByTool,
            formatCountdown: formatCountdown,
            statusBadgeClass: statusBadgeClass,
            expanded: expanded,
            toggleExpand: toggleExpand,
            onClearCooldown: onClearCooldown,
            sourceClass: sourceClass,
            sourceLabel: sourceLabel,
            search: search,
            showProject: showProject,
            showVance: showVance,
            showBuiltin: showBuiltin,
            primaryOnly: primaryOnly,
            deferredOnly: deferredOnly,
            showDisabled: showDisabled,
            toggleSort: toggleSort,
            arrow: arrow,
            filteredTools: filteredTools,
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
//# sourceMappingURL=ProjectToolsTab.vue.js.map