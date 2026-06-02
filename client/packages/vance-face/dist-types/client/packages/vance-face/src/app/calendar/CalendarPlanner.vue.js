import { computed, onMounted, ref, watch } from 'vue';
import mermaid from 'mermaid';
import { VAlert, VButton, VEmptyState, VInput, VModal, VSelect } from '@/components';
import CalendarEventDetail from './CalendarEventDetail.vue';
import { createCalendarEvent, deleteCalendarEvent, getCalendarPlanner, rebuildCalendarPlanner, updateCalendarEvent, } from '@vance/shared';
const props = defineProps();
const OVERVIEW = '__overview__';
const planner = ref(null);
const loading = ref(true);
const error = ref(null);
const activeTab = ref(OVERVIEW);
const selectedEventId = ref(null);
const showCreateModal = ref(false);
const newEventForm = ref({
    title: '',
    start: '',
    lane: '',
    allDay: false,
    attendees: [],
    tags: [],
});
const ganttSvg = ref('');
const ganttError = ref(null);
let mermaidInitialized = false;
const eventsForTab = computed(() => {
    if (!planner.value || activeTab.value === OVERVIEW)
        return [];
    return planner.value.events
        .filter((e) => e.lane === activeTab.value)
        .slice()
        .sort((a, b) => a.start.localeCompare(b.start));
});
const selectedEvent = computed(() => {
    if (!planner.value || !selectedEventId.value)
        return null;
    return planner.value.events.find((e) => e.id === selectedEventId.value) ?? null;
});
async function load() {
    loading.value = true;
    error.value = null;
    try {
        planner.value = await getCalendarPlanner(props.projectId, props.folder);
        if (activeTab.value !== OVERVIEW &&
            !planner.value.lanes.some((l) => l.name === activeTab.value)) {
            activeTab.value = OVERVIEW;
        }
        await renderGantt();
    }
    catch (e) {
        error.value = `Could not load planner: ${e.message}`;
    }
    finally {
        loading.value = false;
    }
}
function initMermaid() {
    if (mermaidInitialized)
        return;
    mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        theme: 'default',
    });
    mermaidInitialized = true;
}
async function renderGantt() {
    if (!planner.value)
        return;
    const gantt = planner.value.artefacts.find((a) => a.name === 'gantt');
    if (!gantt?.body) {
        ganttSvg.value = '';
        ganttError.value = null;
        return;
    }
    const source = extractMermaidFence(gantt.body);
    if (!source) {
        ganttSvg.value = '';
        ganttError.value = 'Could not extract Mermaid source from _gantt.md.';
        return;
    }
    try {
        initMermaid();
        const id = `gantt-${Date.now()}`;
        const out = await mermaid.render(id, source);
        ganttSvg.value = out.svg;
        ganttError.value = null;
    }
    catch (e) {
        ganttSvg.value = '';
        ganttError.value = e.message;
    }
}
function extractMermaidFence(body) {
    const match = body.match(/```mermaid\s*\n([\s\S]*?)\n```/);
    return match ? match[1] : null;
}
async function rebuild() {
    try {
        await rebuildCalendarPlanner(props.projectId, props.folder);
        await load();
    }
    catch (e) {
        error.value = `Rebuild failed: ${e.message}`;
    }
}
function openCreateModal(lane) {
    newEventForm.value = {
        title: '',
        start: new Date().toISOString().slice(0, 10),
        lane,
        allDay: false,
        attendees: [],
        tags: [],
    };
    showCreateModal.value = true;
}
async function submitCreate() {
    if (!newEventForm.value.title.trim() || !newEventForm.value.start.trim())
        return;
    try {
        const created = await createCalendarEvent(props.projectId, props.folder, newEventForm.value);
        if (planner.value)
            planner.value.events.push(created);
        showCreateModal.value = false;
        selectedEventId.value = created.id;
        activeTab.value = created.lane;
    }
    catch (e) {
        error.value = `Create failed: ${e.message}`;
    }
}
async function onEventUpdate(id, patch) {
    try {
        const updated = await updateCalendarEvent(props.projectId, props.folder, id, patch);
        if (!planner.value)
            return;
        const idx = planner.value.events.findIndex((e) => e.id === id);
        if (idx >= 0)
            planner.value.events[idx] = updated;
        selectedEventId.value = updated.id;
        if (patch.targetLane && updated.lane !== activeTab.value) {
            activeTab.value = updated.lane;
        }
    }
    catch (e) {
        error.value = `Update failed: ${e.message}`;
    }
}
async function onEventDelete(id) {
    try {
        await deleteCalendarEvent(props.projectId, props.folder, id);
        if (planner.value) {
            planner.value.events = planner.value.events.filter((e) => e.id !== id);
        }
        selectedEventId.value = null;
    }
    catch (e) {
        error.value = `Delete failed: ${e.message}`;
    }
}
watch(activeTab, () => {
    if (activeTab.value === OVERVIEW) {
        void renderGantt();
    }
});
function formatDateRange(ev) {
    if (!ev.end)
        return ev.start;
    if (ev.allDay && ev.start === ev.end)
        return ev.start;
    return `${ev.start} → ${ev.end}`;
}
function rangeLabel(c) {
    return `${c.overlapStart.replace('T', ' ')} – ${c.overlapEnd.replace('T', ' ')}`;
}
onMounted(load);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col h-full" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center justify-between p-4 border-b border-base-300" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({
    ...{ class: "text-xl font-semibold" },
});
(__VLS_ctx.title ?? __VLS_ctx.folder);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-sm text-base-content/60 mt-0.5" },
});
(__VLS_ctx.folder);
if (__VLS_ctx.planner?.windowFrom || __VLS_ctx.planner?.windowUntil) {
    (__VLS_ctx.planner?.windowFrom ?? '?');
    (__VLS_ctx.planner?.windowUntil ?? '?');
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2 items-center" },
});
if (__VLS_ctx.planner) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-sm text-base-content/60" },
    });
    (__VLS_ctx.planner.events.length);
    (__VLS_ctx.planner.lanes.length);
    if (__VLS_ctx.planner.conflicts.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-error" },
        });
        (__VLS_ctx.planner.conflicts.length);
    }
}
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
    onClick: (__VLS_ctx.load)
};
__VLS_3.slots.default;
var __VLS_3;
const __VLS_8 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}));
const __VLS_10 = __VLS_9({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
let __VLS_12;
let __VLS_13;
let __VLS_14;
const __VLS_15 = {
    onClick: (__VLS_ctx.rebuild)
};
__VLS_11.slots.default;
var __VLS_11;
if (__VLS_ctx.error) {
    const __VLS_16 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        variant: "error",
        ...{ class: "m-4" },
    }));
    const __VLS_18 = __VLS_17({
        variant: "error",
        ...{ class: "m-4" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    __VLS_19.slots.default;
    (__VLS_ctx.error);
    var __VLS_19;
}
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-8 text-base-content/70" },
    });
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex overflow-hidden" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "w-56 flex-shrink-0 border-r border-base-300 bg-base-200/40 overflow-y-auto" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!!(__VLS_ctx.loading))
                    return;
                __VLS_ctx.activeTab = __VLS_ctx.OVERVIEW;
                __VLS_ctx.selectedEventId = null;
            } },
        ...{ class: "w-full text-left px-4 py-3 hover:bg-base-200 transition-colors" },
        ...{ class: (__VLS_ctx.activeTab === __VLS_ctx.OVERVIEW ? 'bg-base-100 border-l-4 border-primary' : '') },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-medium" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs text-base-content/60 mt-0.5" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "border-t border-base-300 mt-1 pt-1" },
    });
    for (const [lane] of __VLS_getVForSourceType((__VLS_ctx.planner?.lanes ?? []))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.loading))
                        return;
                    __VLS_ctx.activeTab = lane.name;
                    __VLS_ctx.selectedEventId = null;
                } },
            key: (lane.name),
            ...{ class: "w-full text-left px-4 py-2 hover:bg-base-200 transition-colors flex items-center justify-between" },
            ...{ class: (__VLS_ctx.activeTab === lane.name ? 'bg-base-100 border-l-4 border-primary' : '') },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "font-medium text-sm" },
        });
        (lane.title ?? lane.name);
        if (!lane.declared) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs text-warning" },
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs text-base-content/60" },
        });
        (lane.eventCount);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 overflow-y-auto" },
    });
    if (__VLS_ctx.activeTab === __VLS_ctx.OVERVIEW) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-4 flex flex-col gap-4" },
        });
        if (__VLS_ctx.planner?.artefacts.find((a) => a.name === 'gantt')) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
            __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
                ...{ class: "text-lg font-semibold mb-2" },
            });
            if (__VLS_ctx.ganttError) {
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
                (__VLS_ctx.ganttError);
                var __VLS_23;
            }
            else if (__VLS_ctx.ganttSvg) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
                    ...{ class: "bg-base-100 border border-base-300 rounded p-4 overflow-x-auto" },
                });
                __VLS_asFunctionalDirective(__VLS_directives.vHtml)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.ganttSvg) }, null, null);
            }
            else {
                const __VLS_24 = {}.VEmptyState;
                /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                // @ts-ignore
                const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
                    headline: "No Gantt yet",
                    body: "Click 'Rebuild artefacts' to generate the Mermaid Gantt diagram.",
                }));
                const __VLS_26 = __VLS_25({
                    headline: "No Gantt yet",
                    body: "Click 'Rebuild artefacts' to generate the Mermaid Gantt diagram.",
                }, ...__VLS_functionalComponentArgsRest(__VLS_25));
            }
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
            ...{ class: "text-lg font-semibold mb-2" },
        });
        if (__VLS_ctx.planner) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-base-content/60 text-sm" },
            });
            (__VLS_ctx.planner.conflicts.length);
        }
        if (!__VLS_ctx.planner || __VLS_ctx.planner.conflicts.length === 0) {
            const __VLS_28 = {}.VEmptyState;
            /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
            // @ts-ignore
            const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
                headline: "No conflicts",
                body: "No two events overlap in the current window.",
            }));
            const __VLS_30 = __VLS_29({
                headline: "No conflicts",
                body: "No two events overlap in the current window.",
            }, ...__VLS_functionalComponentArgsRest(__VLS_29));
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "border border-base-300 rounded overflow-hidden" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
                ...{ class: "w-full text-sm" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.thead, __VLS_intrinsicElements.thead)({
                ...{ class: "bg-base-200" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
            __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
                ...{ class: "text-left px-3 py-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
                ...{ class: "text-left px-3 py-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
                ...{ class: "text-left px-3 py-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
            for (const [c, idx] of __VLS_getVForSourceType((__VLS_ctx.planner.conflicts))) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
                    key: (idx),
                    ...{ class: "border-t border-base-300" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                    ...{ class: "px-3 py-2" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "font-medium" },
                });
                (c.titleA);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-xs text-base-content/60" },
                });
                (c.laneA);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                    ...{ class: "px-3 py-2" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "font-medium" },
                });
                (c.titleB);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-xs text-base-content/60" },
                });
                (c.laneB);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                    ...{ class: "px-3 py-2 text-xs text-base-content/70" },
                });
                (__VLS_ctx.rangeLabel(c));
            }
        }
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col h-full" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-4 flex items-center justify-between border-b border-base-300" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
            ...{ class: "text-lg font-semibold" },
        });
        (__VLS_ctx.planner?.lanes.find((l) => l.name === __VLS_ctx.activeTab)?.title ?? __VLS_ctx.activeTab);
        const __VLS_32 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "primary",
        }));
        const __VLS_34 = __VLS_33({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "primary",
        }, ...__VLS_functionalComponentArgsRest(__VLS_33));
        let __VLS_36;
        let __VLS_37;
        let __VLS_38;
        const __VLS_39 = {
            onClick: (...[$event]) => {
                if (!!(__VLS_ctx.loading))
                    return;
                if (!!(__VLS_ctx.activeTab === __VLS_ctx.OVERVIEW))
                    return;
                __VLS_ctx.openCreateModal(__VLS_ctx.activeTab);
            }
        };
        __VLS_35.slots.default;
        var __VLS_35;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 overflow-y-auto p-4" },
        });
        if (__VLS_ctx.eventsForTab.length === 0) {
            const __VLS_40 = {}.VEmptyState;
            /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
            // @ts-ignore
            const __VLS_41 = __VLS_asFunctionalComponent(__VLS_40, new __VLS_40({
                headline: "No events in this lane",
                body: "Add the first event to get started.",
            }));
            const __VLS_42 = __VLS_41({
                headline: "No events in this lane",
                body: "Add the first event to get started.",
            }, ...__VLS_functionalComponentArgsRest(__VLS_41));
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex flex-col gap-2" },
            });
            for (const [ev] of __VLS_getVForSourceType((__VLS_ctx.eventsForTab))) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ onClick: (...[$event]) => {
                            if (!!(__VLS_ctx.loading))
                                return;
                            if (!!(__VLS_ctx.activeTab === __VLS_ctx.OVERVIEW))
                                return;
                            if (!!(__VLS_ctx.eventsForTab.length === 0))
                                return;
                            __VLS_ctx.selectedEventId = ev.id;
                        } },
                    key: (ev.id),
                    ...{ class: "bg-base-100 border border-base-300 rounded p-3 cursor-pointer hover:border-primary transition-colors" },
                    ...{ class: (__VLS_ctx.selectedEventId === ev.id ? 'border-primary ring-1 ring-primary/30' : '') },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex items-start justify-between gap-3" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex-1 min-w-0" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "font-medium" },
                });
                (ev.title);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-xs text-base-content/70 mt-0.5" },
                });
                (__VLS_ctx.formatDateRange(ev));
                if (ev.allDay) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "ml-1 text-base-content/50" },
                    });
                }
                if (ev.recurrence) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "ml-1 text-info" },
                    });
                }
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex gap-1" },
                });
                if (ev.googleUrl) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
                        ...{ onClick: () => { } },
                        href: (ev.googleUrl),
                        target: "_blank",
                        rel: "noopener",
                        ...{ class: "text-xs bg-base-200 hover:bg-base-300 rounded px-2 py-1" },
                        title: "Add to Google Calendar",
                    });
                }
                if (ev.outlookUrl) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
                        ...{ onClick: () => { } },
                        href: (ev.outlookUrl),
                        target: "_blank",
                        rel: "noopener",
                        ...{ class: "text-xs bg-base-200 hover:bg-base-300 rounded px-2 py-1" },
                        title: "Add to Outlook",
                    });
                }
                if (ev.location) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "text-xs text-base-content/60 mt-1" },
                    });
                    (ev.location);
                }
                if (ev.tags.length > 0) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "flex flex-wrap gap-1 mt-1" },
                    });
                    for (const [tag] of __VLS_getVForSourceType((ev.tags))) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            key: (tag),
                            ...{ class: "text-xs bg-info/15 text-info rounded px-1.5 py-0.5" },
                        });
                        (tag);
                    }
                }
            }
        }
    }
    if (__VLS_ctx.selectedEvent && __VLS_ctx.planner) {
        /** @type {[typeof CalendarEventDetail, ]} */ ;
        // @ts-ignore
        const __VLS_44 = __VLS_asFunctionalComponent(CalendarEventDetail, new CalendarEventDetail({
            ...{ 'onClose': {} },
            ...{ 'onUpdate': {} },
            ...{ 'onDelete': {} },
            event: (__VLS_ctx.selectedEvent),
            lanes: (__VLS_ctx.planner.lanes),
            ...{ class: "w-96 flex-shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto" },
        }));
        const __VLS_45 = __VLS_44({
            ...{ 'onClose': {} },
            ...{ 'onUpdate': {} },
            ...{ 'onDelete': {} },
            event: (__VLS_ctx.selectedEvent),
            lanes: (__VLS_ctx.planner.lanes),
            ...{ class: "w-96 flex-shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_44));
        let __VLS_47;
        let __VLS_48;
        let __VLS_49;
        const __VLS_50 = {
            onClose: (...[$event]) => {
                if (!!(__VLS_ctx.loading))
                    return;
                if (!(__VLS_ctx.selectedEvent && __VLS_ctx.planner))
                    return;
                __VLS_ctx.selectedEventId = null;
            }
        };
        const __VLS_51 = {
            onUpdate: ((patch) => __VLS_ctx.onEventUpdate(__VLS_ctx.selectedEvent.id, patch))
        };
        const __VLS_52 = {
            onDelete: (...[$event]) => {
                if (!!(__VLS_ctx.loading))
                    return;
                if (!(__VLS_ctx.selectedEvent && __VLS_ctx.planner))
                    return;
                __VLS_ctx.onEventDelete(__VLS_ctx.selectedEvent.id);
            }
        };
        var __VLS_46;
    }
}
const __VLS_53 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
    modelValue: (__VLS_ctx.showCreateModal),
    title: "New event",
}));
const __VLS_55 = __VLS_54({
    modelValue: (__VLS_ctx.showCreateModal),
    title: "New event",
}, ...__VLS_functionalComponentArgsRest(__VLS_54));
__VLS_56.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
const __VLS_57 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
    modelValue: (__VLS_ctx.newEventForm.title),
    placeholder: "Event title",
}));
const __VLS_59 = __VLS_58({
    modelValue: (__VLS_ctx.newEventForm.title),
    placeholder: "Event title",
}, ...__VLS_functionalComponentArgsRest(__VLS_58));
const __VLS_61 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
    modelValue: (__VLS_ctx.newEventForm.start),
    placeholder: "Start (YYYY-MM-DD or YYYY-MM-DDTHH:mm)",
}));
const __VLS_63 = __VLS_62({
    modelValue: (__VLS_ctx.newEventForm.start),
    placeholder: "Start (YYYY-MM-DD or YYYY-MM-DDTHH:mm)",
}, ...__VLS_functionalComponentArgsRest(__VLS_62));
const __VLS_65 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newEventForm.end ?? ''),
    placeholder: "End (optional)",
}));
const __VLS_67 = __VLS_66({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newEventForm.end ?? ''),
    placeholder: "End (optional)",
}, ...__VLS_functionalComponentArgsRest(__VLS_66));
let __VLS_69;
let __VLS_70;
let __VLS_71;
const __VLS_72 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.newEventForm.end = v)
};
var __VLS_68;
const __VLS_73 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newEventForm.lane ?? ''),
    options: (__VLS_ctx.planner?.lanes.map((l) => ({ value: l.name, label: l.title ?? l.name })) ?? []),
}));
const __VLS_75 = __VLS_74({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newEventForm.lane ?? ''),
    options: (__VLS_ctx.planner?.lanes.map((l) => ({ value: l.name, label: l.title ?? l.name })) ?? []),
}, ...__VLS_functionalComponentArgsRest(__VLS_74));
let __VLS_77;
let __VLS_78;
let __VLS_79;
const __VLS_80 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.newEventForm.lane = v ?? '')
};
var __VLS_76;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2 pt-2" },
});
const __VLS_81 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_83 = __VLS_82({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_82));
let __VLS_85;
let __VLS_86;
let __VLS_87;
const __VLS_88 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreateModal = false;
    }
};
__VLS_84.slots.default;
var __VLS_84;
const __VLS_89 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newEventForm.title.trim() || !__VLS_ctx.newEventForm.start.trim()),
}));
const __VLS_91 = __VLS_90({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newEventForm.title.trim() || !__VLS_ctx.newEventForm.start.trim()),
}, ...__VLS_functionalComponentArgsRest(__VLS_90));
let __VLS_93;
let __VLS_94;
let __VLS_95;
const __VLS_96 = {
    onClick: (__VLS_ctx.submitCreate)
};
__VLS_92.slots.default;
var __VLS_92;
var __VLS_56;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['m-4']} */ ;
/** @type {__VLS_StyleScopedClasses['p-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['w-56']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-r']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-3']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-x-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:border-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/50']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-info']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-info/15']} */ ;
/** @type {__VLS_StyleScopedClasses['text-info']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['w-96']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-l']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            CalendarEventDetail: CalendarEventDetail,
            OVERVIEW: OVERVIEW,
            planner: planner,
            loading: loading,
            error: error,
            activeTab: activeTab,
            selectedEventId: selectedEventId,
            showCreateModal: showCreateModal,
            newEventForm: newEventForm,
            ganttSvg: ganttSvg,
            ganttError: ganttError,
            eventsForTab: eventsForTab,
            selectedEvent: selectedEvent,
            load: load,
            rebuild: rebuild,
            openCreateModal: openCreateModal,
            submitCreate: submitCreate,
            onEventUpdate: onEventUpdate,
            onEventDelete: onEventDelete,
            formatDateRange: formatDateRange,
            rangeLabel: rangeLabel,
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
//# sourceMappingURL=CalendarPlanner.vue.js.map