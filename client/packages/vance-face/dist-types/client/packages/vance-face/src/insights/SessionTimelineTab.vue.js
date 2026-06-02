import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VEmptyState } from '@/components';
import ProcessTreeBlock from './ProcessTreeBlock.vue';
import { brainFetch } from '@vance/shared';
const { t } = useI18n();
const props = defineProps();
const emit = defineEmits();
const bundles = ref(new Map());
const loading = ref(false);
const error = ref(null);
watch(() => props.processes, async (list) => {
    await loadAll(list);
}, { immediate: true });
async function loadAll(list) {
    if (!list || list.length === 0) {
        bundles.value = new Map();
        return;
    }
    loading.value = true;
    error.value = null;
    try {
        // Fetch chat + memory for every process in parallel; only fetch the
        // marvin tree when the engine carries one — saves a 200-OK round-trip
        // per ford / arthur process.
        const fetched = await Promise.all(list.map(p => loadOne(p)));
        const map = new Map();
        for (const b of fetched)
            map.set(b.process.id, b);
        bundles.value = map;
    }
    catch (e) {
        error.value = e instanceof Error ? e.message : t('insights.timeline.failedToLoad');
    }
    finally {
        loading.value = false;
    }
}
async function loadOne(p) {
    const isMarvin = (p.thinkEngine ?? '').toLowerCase() === 'marvin';
    const [chat, memory, marvinNodes] = await Promise.all([
        brainFetch('GET', `admin/processes/${encodeURIComponent(p.id)}/chat`),
        brainFetch('GET', `admin/processes/${encodeURIComponent(p.id)}/memory`),
        isMarvin
            ? brainFetch('GET', `admin/processes/${encodeURIComponent(p.id)}/marvin-tree`)
            : Promise.resolve([]),
    ]);
    return { process: p, chat, memory, marvinNodes };
}
// ─── Process tree (parent → children by parentProcessId) ────────────────
const tree = computed(() => {
    const byParent = new Map();
    for (const p of props.processes) {
        const key = p.parentProcessId ?? null;
        if (!byParent.has(key))
            byParent.set(key, []);
        byParent.get(key).push(p);
    }
    for (const list of byParent.values()) {
        list.sort((a, b) => byTime(instantStr(a.createdAt), instantStr(b.createdAt)));
    }
    const idsInSession = new Set(props.processes.map(p => p.id));
    function build(parentId) {
        return (byParent.get(parentId) ?? []).map(p => ({
            process: p,
            children: build(p.id),
        }));
    }
    // Orphans: declared parent is not in this session — render at root.
    const orphans = props.processes.filter(p => p.parentProcessId != null && !idsInSession.has(p.parentProcessId));
    return [
        ...build(null),
        ...orphans.map(p => ({ process: p, children: build(p.id) })),
    ];
});
// ─── Per-process event stream ───────────────────────────────────────────
const eventsByProcess = computed(() => {
    const out = {};
    for (const [id, b] of bundles.value.entries()) {
        out[id] = eventsFor(b);
    }
    return out;
});
function eventsFor(bundle) {
    const out = [];
    out.push({
        kind: 'spawn',
        at: instantStr(bundle.process.createdAt),
        id: 'spawn',
        label: t('insights.timeline.eventSpawn', { engine: bundle.process.thinkEngine }),
        tag: bundle.process.recipeName
            ? t('insights.timeline.tagRecipe', { name: bundle.process.recipeName })
            : undefined,
        detail: JSON.stringify({
            name: bundle.process.name,
            engine: bundle.process.thinkEngine,
            recipe: bundle.process.recipeName,
            goal: bundle.process.goal,
            params: bundle.process.engineParams,
            parentProcessId: bundle.process.parentProcessId,
        }, null, 2),
        detailIsMarkdown: false,
    });
    for (const m of bundle.chat) {
        out.push({
            kind: 'chat',
            at: instantStr(m.createdAt),
            id: 'chat:' + m.id,
            label: t('insights.timeline.eventChat', {
                role: m.role,
                preview: truncate(m.content, 90),
            }),
            tag: m.archivedInMemoryId ? t('insights.timeline.tagArchived') : undefined,
            detail: m.content,
            detailIsMarkdown: true,
        });
    }
    for (const mem of bundle.memory) {
        out.push({
            kind: 'memory',
            at: instantStr(mem.createdAt),
            id: 'mem:' + mem.id,
            label: mem.title
                ? t('insights.timeline.eventMemoryWithTitle', { kind: mem.kind, title: mem.title })
                : t('insights.timeline.eventMemory', { kind: mem.kind }),
            tag: mem.supersededByMemoryId ? t('insights.timeline.tagSuperseded') : undefined,
            detail: mem.content,
            detailIsMarkdown: true,
        });
    }
    for (const n of bundle.marvinNodes) {
        out.push({
            kind: 'marvin',
            at: instantStr(n.createdAt),
            id: 'mn:' + n.id,
            label: t('insights.timeline.eventMarvinNode', {
                taskKind: n.taskKind,
                goal: truncate(n.goal || t('insights.timeline.noGoal'), 80),
            }),
            tag: n.status,
            detail: JSON.stringify({
                goal: n.goal,
                taskKind: n.taskKind,
                status: n.status,
                artifacts: n.artifacts,
                spawnedProcessId: n.spawnedProcessId,
                inboxItemId: n.inboxItemId,
                failureReason: n.failureReason,
            }, null, 2),
            detailIsMarkdown: false,
        });
    }
    bundle.process.pendingMessages.forEach((m, idx) => {
        out.push({
            kind: 'pending',
            at: instantStr(m.at),
            id: 'pm:' + idx,
            label: t('insights.timeline.eventPending', { type: m.type }),
            tag: t('insights.timeline.tagQueued'),
            detail: JSON.stringify(m.payload ?? {}, null, 2),
            detailIsMarkdown: false,
        });
    });
    out.sort((a, b) => byTime(a.at, b.at));
    return out;
}
// ─── Expansion state ────────────────────────────────────────────────────
const collapsedProcesses = ref(new Set());
const expandedEvents = ref(new Set());
function toggleProcess(id) {
    const next = new Set(collapsedProcesses.value);
    if (next.has(id))
        next.delete(id);
    else
        next.add(id);
    collapsedProcesses.value = next;
}
function toggleEvent(processId, eventId) {
    const key = processId + '|' + eventId;
    const next = new Set(expandedEvents.value);
    if (next.has(key))
        next.delete(key);
    else
        next.add(key);
    expandedEvents.value = next;
}
// ─── Helpers ────────────────────────────────────────────────────────────
function instantStr(d) {
    if (d == null)
        return null;
    if (d instanceof Date)
        return d.toISOString();
    return String(d);
}
function byTime(a, b) {
    if (a == null && b == null)
        return 0;
    if (a == null)
        return 1;
    if (b == null)
        return -1;
    return a < b ? -1 : a > b ? 1 : 0;
}
function truncate(s, max) {
    if (!s)
        return '';
    const trimmed = s.replace(/\s+/g, ' ').trim();
    return trimmed.length > max ? trimmed.slice(0, max - 1) + '…' : trimmed;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.error) {
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
    (__VLS_ctx.error);
    var __VLS_3;
}
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-70" },
    });
    (__VLS_ctx.$t('insights.timeline.loading'));
}
else if (__VLS_ctx.processes.length === 0) {
    const __VLS_4 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        headline: (__VLS_ctx.$t('insights.timeline.noProcessesHeadline')),
        body: (__VLS_ctx.$t('insights.timeline.noProcessesBody')),
    }));
    const __VLS_6 = __VLS_5({
        headline: (__VLS_ctx.$t('insights.timeline.noProcessesHeadline')),
        body: (__VLS_ctx.$t('insights.timeline.noProcessesBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "timeline-tree" },
    });
    for (const [root] of __VLS_getVForSourceType((__VLS_ctx.tree))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (root.process.id),
            ...{ class: "timeline-process" },
        });
        /** @type {[typeof ProcessTreeBlock, ]} */ ;
        // @ts-ignore
        const __VLS_8 = __VLS_asFunctionalComponent(ProcessTreeBlock, new ProcessTreeBlock({
            ...{ 'onSelectProcess': {} },
            ...{ 'onToggleProcess': {} },
            ...{ 'onToggleEvent': {} },
            node: (root),
            eventsByProcess: (__VLS_ctx.eventsByProcess),
            collapsedProcesses: (__VLS_ctx.collapsedProcesses),
            expandedEvents: (__VLS_ctx.expandedEvents),
        }));
        const __VLS_9 = __VLS_8({
            ...{ 'onSelectProcess': {} },
            ...{ 'onToggleProcess': {} },
            ...{ 'onToggleEvent': {} },
            node: (root),
            eventsByProcess: (__VLS_ctx.eventsByProcess),
            collapsedProcesses: (__VLS_ctx.collapsedProcesses),
            expandedEvents: (__VLS_ctx.expandedEvents),
        }, ...__VLS_functionalComponentArgsRest(__VLS_8));
        let __VLS_11;
        let __VLS_12;
        let __VLS_13;
        const __VLS_14 = {
            onSelectProcess: ((id) => __VLS_ctx.emit('select-process', id))
        };
        const __VLS_15 = {
            onToggleProcess: (__VLS_ctx.toggleProcess)
        };
        const __VLS_16 = {
            onToggleEvent: (__VLS_ctx.toggleEvent)
        };
        var __VLS_10;
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['timeline-tree']} */ ;
/** @type {__VLS_StyleScopedClasses['timeline-process']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VEmptyState: VEmptyState,
            ProcessTreeBlock: ProcessTreeBlock,
            emit: emit,
            loading: loading,
            error: error,
            tree: tree,
            eventsByProcess: eventsByProcess,
            collapsedProcesses: collapsedProcesses,
            expandedEvents: expandedEvents,
            toggleProcess: toggleProcess,
            toggleEvent: toggleEvent,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=SessionTimelineTab.vue.js.map