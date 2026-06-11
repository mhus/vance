import { computed, watch } from 'vue';
import { VAlert, VEmptyState } from '@/components';
import { useZarniwoopInsights } from '@/composables/useProjectInsights';
const props = defineProps();
const state = useZarniwoopInsights();
watch(() => props.projectId, (next) => {
    if (next)
        state.load(next);
    else
        state.clear();
}, { immediate: true });
function reload() {
    if (props.projectId)
        state.load(props.projectId);
}
async function toggleInstance(inst) {
    if (!props.projectId)
        return;
    // Effective state right now (after gate resolution) drives the flip;
    // forcing the opposite value is what the operator means.
    await state.setOverride(props.projectId, inst.id, !inst.effectivelyEnabled);
}
async function resetOverride(inst) {
    if (!props.projectId)
        return;
    await state.clearOverride(props.projectId, inst.id);
}
function availabilityClass(availability) {
    switch (availability) {
        case 'READY':
            return 'badge badge--ok';
        case 'NO_CREDENTIALS':
            return 'badge badge--warning';
        case 'QUOTA_EXHAUSTED':
        case 'COOLDOWN':
            return 'badge badge--error';
        case 'DISABLED':
        default:
            return 'badge badge--muted';
    }
}
function formatTimestamp(iso) {
    if (!iso)
        return '—';
    const t = Date.parse(iso);
    if (Number.isNaN(t))
        return iso;
    const d = new Date(t);
    return d.toLocaleString();
}
function formatDuration(now, iso) {
    if (!iso)
        return '';
    const target = Date.parse(iso);
    if (Number.isNaN(target))
        return '';
    const ms = target - now;
    if (ms <= 0)
        return ' (elapsed)';
    const minutes = Math.round(ms / 60_000);
    if (minutes < 60)
        return ` (in ${minutes}m)`;
    const hours = Math.round(minutes / 60);
    if (hours < 24)
        return ` (in ${hours}h)`;
    return ` (in ${Math.round(hours / 24)}d)`;
}
const sorted = computed(() => {
    const out = [...state.instances.value];
    out.sort((a, b) => a.id.localeCompare(b.id));
    return out;
});
const totals = computed(() => {
    let calls = 0;
    let ok = 0;
    let errors = 0;
    for (const inst of state.instances.value) {
        calls += inst.callCount;
        ok += inst.okCount;
        errors += inst.errorCount;
    }
    return { calls, ok, errors };
});
const now = Date.now();
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
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
else if (__VLS_ctx.state.instances.value.length === 0) {
    const __VLS_4 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        headline: "No search providers configured",
        body: "This project has no research.endpoint.* entries. Add one in the settings editor — e.g. research.endpoint.serper-main.protocol = serper.",
    }));
    const __VLS_6 = __VLS_5({
        headline: "No search providers configured",
        body: "This project has no research.endpoint.* entries. Add one in the settings editor — e.g. research.endpoint.serper-main.protocol = serper.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-end gap-4 text-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-70" },
    });
    (__VLS_ctx.sorted.length);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-70" },
    });
    (__VLS_ctx.totals.calls);
    (__VLS_ctx.totals.calls === 1 ? '' : 's');
    (__VLS_ctx.totals.ok);
    (__VLS_ctx.totals.errors);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.reload) },
        ...{ class: "btn btn-xs ml-auto" },
        disabled: (__VLS_ctx.state.loading.value),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
        ...{ class: "table table-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.thead, __VLS_intrinsicElements.thead)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-32" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-40" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-24" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-32" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-32 text-right" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ class: "w-40" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
    for (const [inst] of __VLS_getVForSourceType((__VLS_ctx.sorted))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
            key: (inst.id),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
            ...{ class: "flex items-center gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
            ...{ onChange: (...[$event]) => {
                    if (!!(!__VLS_ctx.projectId))
                        return;
                    if (!!(__VLS_ctx.state.loading.value))
                        return;
                    if (!!(__VLS_ctx.state.error.value))
                        return;
                    if (!!(__VLS_ctx.state.instances.value.length === 0))
                        return;
                    __VLS_ctx.toggleInstance(inst);
                } },
            type: "checkbox",
            ...{ class: "checkbox checkbox-sm" },
            checked: (inst.effectivelyEnabled),
            disabled: (__VLS_ctx.state.loading.value),
        });
        if (inst.manualOverride) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "badge badge--tag" },
                title: (`Manual override: ${inst.manualOverride}. Settings default: ${inst.defaultEnabled ? 'enabled' : 'disabled'}.`),
            });
        }
        else if (!inst.defaultEnabled) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-60 text-xs" },
                title: "Settings have research.endpoint.<id>.enabled=false",
            });
        }
        if (inst.manualOverride) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectId))
                            return;
                        if (!!(__VLS_ctx.state.loading.value))
                            return;
                        if (!!(__VLS_ctx.state.error.value))
                            return;
                        if (!!(__VLS_ctx.state.instances.value.length === 0))
                            return;
                        if (!(inst.manualOverride))
                            return;
                        __VLS_ctx.resetOverride(inst);
                    } },
                ...{ class: "btn btn-xs btn-link mt-1" },
                disabled: (__VLS_ctx.state.loading.value),
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "font-mono" },
        });
        (inst.id);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
        (inst.displayName);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "font-mono opacity-80" },
        });
        (inst.protocol);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs" },
        });
        for (const [m] of __VLS_getVForSourceType((inst.modalities))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                key: (m),
                ...{ class: "badge badge--tag mr-1" },
            });
            (m.toLowerCase());
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: (__VLS_ctx.availabilityClass(inst.availability)) },
        });
        (inst.availability);
        if (inst.activeCooldownUntil) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60 mt-1" },
                title: (inst.activeCooldownSignature ?? ''),
            });
            (__VLS_ctx.formatTimestamp(inst.activeCooldownUntil));
            (__VLS_ctx.formatDuration(__VLS_ctx.now, inst.activeCooldownUntil));
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs opacity-80" },
        });
        if (inst.statusText) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (inst.statusText);
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-50" },
            });
        }
        if (inst.lastErrorMessage) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs text-error mt-1" },
                title: (inst.lastErrorAt ?? ''),
            });
            (inst.lastErrorMessage);
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-right text-xs" },
        });
        (inst.callCount);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-60" },
        });
        (inst.okCount);
        (inst.errorCount);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs opacity-80" },
        });
        (__VLS_ctx.formatTimestamp(inst.lastUsedAt));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-50" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono" },
    });
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
/** @type {__VLS_StyleScopedClasses['items-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['table']} */ ;
/** @type {__VLS_StyleScopedClasses['table-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['w-40']} */ ;
/** @type {__VLS_StyleScopedClasses['w-24']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['text-right']} */ ;
/** @type {__VLS_StyleScopedClasses['w-40']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['checkbox']} */ ;
/** @type {__VLS_StyleScopedClasses['checkbox-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge--tag']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-link']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge--tag']} */ ;
/** @type {__VLS_StyleScopedClasses['mr-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-right']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VEmptyState: VEmptyState,
            state: state,
            reload: reload,
            toggleInstance: toggleInstance,
            resetOverride: resetOverride,
            availabilityClass: availabilityClass,
            formatTimestamp: formatTimestamp,
            formatDuration: formatDuration,
            sorted: sorted,
            totals: totals,
            now: now,
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
//# sourceMappingURL=ZarniwoopTab.vue.js.map