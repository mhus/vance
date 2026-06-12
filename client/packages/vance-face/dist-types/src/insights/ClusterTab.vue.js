import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VEmptyState } from '@/components';
import { useClusterPods } from '@/composables/useCluster';
const state = useClusterPods();
/**
 * Default sort matches the server: by node name ascending. Click a
 * header to switch column, click the same header again to flip
 * direction.
 */
const sortKey = ref('node');
const sortDir = ref('asc');
function toggleSort(key) {
    if (sortKey.value === key) {
        sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc';
    }
    else {
        sortKey.value = key;
        sortDir.value = defaultDirection(key);
    }
}
/** Numeric/time columns default to descending — recent/loaded first. */
function defaultDirection(key) {
    switch (key) {
        case 'heartbeat':
        case 'booted':
        case 'projects':
            return 'desc';
        default:
            return 'asc';
    }
}
function sortIndicator(key) {
    if (sortKey.value !== key)
        return '';
    return sortDir.value === 'asc' ? '▲' : '▼';
}
onMounted(() => {
    void state.load();
});
function refresh() {
    void state.load();
}
/**
 * Effective status for the badge: STALE wins over the pod's own
 * self-reported status. The wire still carries both — STALE comes
 * derived from {@code lastHeartbeatAt} on the answering brain.
 */
function effectiveStatus(pod) {
    return pod.stale ? 'STALE' : pod.status;
}
function statusClass(pod) {
    const eff = effectiveStatus(pod);
    switch (eff) {
        case 'RUNNING': return 'badge-status badge-status--running';
        case 'STARTING': return 'badge-status badge-status--starting';
        case 'STOPPING': return 'badge-status badge-status--stopping';
        case 'STOPPED': return 'badge-status badge-status--stopped';
        case 'STALE': return 'badge-status badge-status--stale';
        default: return 'badge-status';
    }
}
function projectChipClass(p) {
    switch (p.lifecycleType) {
        case 'PERMANENT': return 'project-chip project-chip--permanent';
        case 'EPHEMERAL': return 'project-chip project-chip--ephemeral';
        case 'HOMELESS': return 'project-chip project-chip--homeless';
        default: return 'project-chip';
    }
}
function projectChipTitle(p) {
    const parts = [
        `status: ${p.status ?? '—'}`,
        `lifecycle: ${p.lifecycleType ?? '—'}`,
        `score: ${p.homeResourceScore}`,
    ];
    return parts.join(' · ');
}
function fmtTime(value) {
    if (value == null)
        return '—';
    if (value instanceof Date)
        return value.toISOString().replace('T', ' ').slice(0, 19);
    return String(value).replace('T', ' ').slice(0, 19);
}
/** Relative age "12s ago" / "3m ago" / "2h ago" — handy for heartbeats. */
function fmtAge(value) {
    if (value == null)
        return '—';
    const ts = value instanceof Date ? value.getTime() : Date.parse(String(value));
    if (Number.isNaN(ts))
        return '—';
    const deltaSec = Math.max(0, Math.floor((Date.now() - ts) / 1000));
    if (deltaSec < 60)
        return `${deltaSec}s ago`;
    if (deltaSec < 3600)
        return `${Math.floor(deltaSec / 60)}m ago`;
    if (deltaSec < 86400)
        return `${Math.floor(deltaSec / 3600)}h ago`;
    return `${Math.floor(deltaSec / 86400)}d ago`;
}
/** Relative remaining lifetime "in 12s" / "in 3m" / "expired". */
function fmtRemaining(value) {
    if (value == null)
        return '—';
    const ts = value instanceof Date ? value.getTime() : Date.parse(String(value));
    if (Number.isNaN(ts))
        return '—';
    const deltaSec = Math.floor((ts - Date.now()) / 1000);
    if (deltaSec <= 0)
        return 'expired';
    if (deltaSec < 60)
        return `in ${deltaSec}s`;
    if (deltaSec < 3600)
        return `in ${Math.floor(deltaSec / 60)}m`;
    if (deltaSec < 86400)
        return `in ${Math.floor(deltaSec / 3600)}h`;
    return `in ${Math.floor(deltaSec / 86400)}d`;
}
const totalProjects = computed(() => state.pods.value.reduce((sum, p) => sum + (p.tenantProjects?.length ?? 0), 0));
const masterPresent = computed(() => state.cluster.value?.masterPodId != null && state.cluster.value.masterPodId !== '');
/**
 * Returns the sort key as a tuple so we can lift {@code null}s to the
 * tail regardless of direction — missing timestamps/versions belong at
 * the bottom whether you sort asc or desc. The first element flags
 * null (0/1), the second carries the real comparable value.
 */
function sortValue(pod) {
    switch (sortKey.value) {
        case 'node':
            return [0, (pod.nodeName ?? '').toLowerCase()];
        case 'status':
            return [0, effectiveStatus(pod).toLowerCase()];
        case 'endpoint':
            return [pod.endpoint ? 0 : 1, (pod.endpoint ?? '').toLowerCase()];
        case 'heartbeat':
            return tsTuple(pod.lastHeartbeatAt);
        case 'booted':
            return tsTuple(pod.bootedAt);
        case 'version':
            return [pod.version ? 0 : 1, (pod.version ?? '').toLowerCase()];
        case 'projects':
            return [0, pod.tenantProjects?.length ?? 0];
    }
}
function tsTuple(value) {
    if (value == null)
        return [1, 0];
    const ts = value instanceof Date ? value.getTime() : Date.parse(String(value));
    return Number.isNaN(ts) ? [1, 0] : [0, ts];
}
const sortedPods = computed(() => {
    const out = [...state.pods.value];
    const dir = sortDir.value === 'asc' ? 1 : -1;
    out.sort((a, b) => {
        const [aNull, aVal] = sortValue(a);
        const [bNull, bVal] = sortValue(b);
        if (aNull !== bNull)
            return aNull - bNull; // nulls always last
        if (aVal < bVal)
            return -1 * dir;
        if (aVal > bVal)
            return 1 * dir;
        // Stable tiebreaker so toggling direction on equal values doesn't shuffle.
        return (a.nodeName ?? '').localeCompare(b.nodeName ?? '');
    });
    return out;
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['th-sort']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3 p-4" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-wrap items-end gap-3 text-sm" },
});
const __VLS_0 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
}));
const __VLS_2 = __VLS_1({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onClick: (__VLS_ctx.refresh)
};
__VLS_3.slots.default;
var __VLS_3;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-xs opacity-60 ml-auto" },
});
(__VLS_ctx.state.pods.value.length);
(__VLS_ctx.state.pods.value.length === 1 ? '' : 's');
(__VLS_ctx.totalProjects);
(__VLS_ctx.totalProjects === 1 ? '' : 's');
if (__VLS_ctx.state.cluster.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "master-banner" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "master-banner__label" },
    });
    if (__VLS_ctx.masterPresent) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "master-banner__node font-mono" },
        });
        (__VLS_ctx.state.cluster.value.masterNodeName ?? '—');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "master-banner__endpoint font-mono" },
        });
        (__VLS_ctx.state.cluster.value.masterEndpoint ?? '');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "master-banner__lease" },
            title: (__VLS_ctx.fmtTime(__VLS_ctx.state.cluster.value.masterLeaseUntil)),
        });
        (__VLS_ctx.fmtRemaining(__VLS_ctx.state.cluster.value.masterLeaseUntil));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "master-banner__none" },
        });
    }
}
if (__VLS_ctx.state.loading.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
}
else if (__VLS_ctx.state.error.value) {
    const __VLS_8 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        variant: "error",
    }));
    const __VLS_10 = __VLS_9({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    __VLS_11.slots.default;
    (__VLS_ctx.state.error.value);
    var __VLS_11;
}
else if (__VLS_ctx.state.pods.value.length === 0) {
    const __VLS_12 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        headline: ('No pods'),
        body: ('No brain pods are registered in this cluster.'),
    }));
    const __VLS_14 = __VLS_13({
        headline: ('No pods'),
        body: ('No brain pods are registered in this cluster.'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
        ...{ class: "table table-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.thead, __VLS_intrinsicElements.thead)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ onClick: (...[$event]) => {
                if (!!(__VLS_ctx.state.loading.value))
                    return;
                if (!!(__VLS_ctx.state.error.value))
                    return;
                if (!!(__VLS_ctx.state.pods.value.length === 0))
                    return;
                __VLS_ctx.toggleSort('node');
            } },
        ...{ class: "w-44 th-sort" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "th-sort__arrow" },
    });
    (__VLS_ctx.sortIndicator('node'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ onClick: (...[$event]) => {
                if (!!(__VLS_ctx.state.loading.value))
                    return;
                if (!!(__VLS_ctx.state.error.value))
                    return;
                if (!!(__VLS_ctx.state.pods.value.length === 0))
                    return;
                __VLS_ctx.toggleSort('status');
            } },
        ...{ class: "w-24 th-sort" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "th-sort__arrow" },
    });
    (__VLS_ctx.sortIndicator('status'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ onClick: (...[$event]) => {
                if (!!(__VLS_ctx.state.loading.value))
                    return;
                if (!!(__VLS_ctx.state.error.value))
                    return;
                if (!!(__VLS_ctx.state.pods.value.length === 0))
                    return;
                __VLS_ctx.toggleSort('endpoint');
            } },
        ...{ class: "th-sort" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "th-sort__arrow" },
    });
    (__VLS_ctx.sortIndicator('endpoint'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ onClick: (...[$event]) => {
                if (!!(__VLS_ctx.state.loading.value))
                    return;
                if (!!(__VLS_ctx.state.error.value))
                    return;
                if (!!(__VLS_ctx.state.pods.value.length === 0))
                    return;
                __VLS_ctx.toggleSort('heartbeat');
            } },
        ...{ class: "w-32 th-sort" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "th-sort__arrow" },
    });
    (__VLS_ctx.sortIndicator('heartbeat'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ onClick: (...[$event]) => {
                if (!!(__VLS_ctx.state.loading.value))
                    return;
                if (!!(__VLS_ctx.state.error.value))
                    return;
                if (!!(__VLS_ctx.state.pods.value.length === 0))
                    return;
                __VLS_ctx.toggleSort('booted');
            } },
        ...{ class: "w-32 th-sort" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "th-sort__arrow" },
    });
    (__VLS_ctx.sortIndicator('booted'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ onClick: (...[$event]) => {
                if (!!(__VLS_ctx.state.loading.value))
                    return;
                if (!!(__VLS_ctx.state.error.value))
                    return;
                if (!!(__VLS_ctx.state.pods.value.length === 0))
                    return;
                __VLS_ctx.toggleSort('version');
            } },
        ...{ class: "w-24 th-sort" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "th-sort__arrow" },
    });
    (__VLS_ctx.sortIndicator('version'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
        ...{ onClick: (...[$event]) => {
                if (!!(__VLS_ctx.state.loading.value))
                    return;
                if (!!(__VLS_ctx.state.error.value))
                    return;
                if (!!(__VLS_ctx.state.pods.value.length === 0))
                    return;
                __VLS_ctx.toggleSort('projects');
            } },
        ...{ class: "th-sort" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "th-sort__arrow" },
    });
    (__VLS_ctx.sortIndicator('projects'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
    for (const [pod] of __VLS_getVForSourceType((__VLS_ctx.sortedPods))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
            key: (pod.podId),
            ...{ class: ({ 'pod-row--self': pod.selfPod, 'pod-row--master': pod.master }) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono" },
        });
        (pod.nodeName);
        if (pod.master) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "ml-1 badge-role badge-role--master" },
                title: "Currently holds the cluster-master lease",
            });
        }
        if (pod.selfPod) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "ml-1 text-[10px] uppercase tracking-wider opacity-70" },
                title: "This is the brain currently serving the request",
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-[10px] opacity-50 font-mono truncate" },
            title: (pod.podId),
        });
        (pod.podId);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: (__VLS_ctx.statusClass(pod)) },
        });
        (__VLS_ctx.effectiveStatus(pod).toLowerCase());
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "font-mono text-xs" },
        });
        (pod.endpoint || '—');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs opacity-80" },
            title: (__VLS_ctx.fmtTime(pod.lastHeartbeatAt)),
        });
        (__VLS_ctx.fmtAge(pod.lastHeartbeatAt));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs opacity-70" },
        });
        (__VLS_ctx.fmtTime(pod.bootedAt));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs opacity-70" },
        });
        (pod.version ?? '—');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
            ...{ class: "text-xs" },
        });
        if (pod.tenantProjects.length === 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "opacity-50" },
            });
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex flex-wrap gap-1" },
            });
            for (const [proj] of __VLS_getVForSourceType((pod.tenantProjects))) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    key: (proj.name),
                    ...{ class: (__VLS_ctx.projectChipClass(proj)) },
                    title: (__VLS_ctx.projectChipTitle(proj)),
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "font-mono" },
                });
                (proj.name);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "project-chip__score" },
                });
                (proj.homeResourceScore);
            }
        }
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-[11px] opacity-60" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-mono" },
});
(__VLS_ctx.state.cluster.value?.clusterId ?? '—');
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['items-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['master-banner']} */ ;
/** @type {__VLS_StyleScopedClasses['master-banner__label']} */ ;
/** @type {__VLS_StyleScopedClasses['master-banner__node']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['master-banner__endpoint']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['master-banner__lease']} */ ;
/** @type {__VLS_StyleScopedClasses['master-banner__none']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['table']} */ ;
/** @type {__VLS_StyleScopedClasses['table-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-44']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort__arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['w-24']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort__arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort__arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort__arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort__arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['w-24']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort__arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort']} */ ;
/** @type {__VLS_StyleScopedClasses['th-sort__arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['pod-row--self']} */ ;
/** @type {__VLS_StyleScopedClasses['pod-row--master']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-1']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-role']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-role--master']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wider']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['project-chip__score']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[11px]']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            state: state,
            toggleSort: toggleSort,
            sortIndicator: sortIndicator,
            refresh: refresh,
            effectiveStatus: effectiveStatus,
            statusClass: statusClass,
            projectChipClass: projectChipClass,
            projectChipTitle: projectChipTitle,
            fmtTime: fmtTime,
            fmtAge: fmtAge,
            fmtRemaining: fmtRemaining,
            totalProjects: totalProjects,
            masterPresent: masterPresent,
            sortedPods: sortedPods,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ClusterTab.vue.js.map