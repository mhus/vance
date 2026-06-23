import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { EditorShell, ProjectListSidebar, VAlert, VButton, VEmptyState, VInput, accentColorDotClass, } from '@/components';
import { useI18n } from 'vue-i18n';
import { useTenantProjects } from '@composables/useTenantProjects';
import { useDocuments } from '@composables/useDocuments';
import DocumentIcon from './DocumentIcon.vue';
const { t } = useI18n();
const projectsState = useTenantProjects();
const PAGE_SIZE = 50;
const docsState = useDocuments(PAGE_SIZE);
const selectedProjectId = ref(null);
const focusZone = ref('main');
const search = ref('');
const DEFAULT_PATH_PREFIX = 'documents/';
const pendingDraft = ref(false);
onMounted(async () => {
    await projectsState.reload();
    const params = new URLSearchParams(window.location.search);
    const queryProject = params.get('projectId');
    const queryPath = params.get('path');
    pendingDraft.value = params.get('createDraft') === '1';
    if (queryProject && projectsState.projects.value.some((p) => p.name === queryProject)) {
        selectedProjectId.value = queryProject;
    }
    else if (projectsState.projects.value.length > 0) {
        selectedProjectId.value = projectsState.projects.value[0].name;
    }
    // When the Inbox handed off a draft and the URL pre-selected a
    // project, forward the user straight to Notepad. Otherwise we
    // wait for the user to pick a project from the sidebar.
    if (pendingDraft.value && selectedProjectId.value && queryProject) {
        forwardDraftToNotepad(selectedProjectId.value);
        return;
    }
    if (selectedProjectId.value) {
        docsState.pathPrefix.value = queryPath ?? DEFAULT_PATH_PREFIX;
        await docsState.loadPage(selectedProjectId.value, 0, docsState.pathPrefix.value);
    }
    window.addEventListener('popstate', onPopstate);
});
function forwardDraftToNotepad(projectId) {
    const params = new URLSearchParams();
    params.set('project', projectId);
    params.set('create', '1');
    window.location.href = `/notepad.html?${params.toString()}`;
}
onBeforeUnmount(() => {
    window.removeEventListener('popstate', onPopstate);
});
// URL sync — project + path live in the address bar so browser
// back/forward step through the directory walk and refresh keeps
// position.
function syncUrl() {
    const params = new URLSearchParams();
    if (selectedProjectId.value)
        params.set('projectId', selectedProjectId.value);
    if (docsState.pathPrefix.value)
        params.set('path', docsState.pathPrefix.value);
    const next = `${window.location.pathname}?${params.toString()}`;
    if (next !== `${window.location.pathname}${window.location.search}`) {
        window.history.pushState({}, '', next);
    }
}
function onPopstate() {
    const params = new URLSearchParams(window.location.search);
    const queryProject = params.get('projectId');
    const queryPath = params.get('path') ?? '';
    if (queryProject && queryProject !== selectedProjectId.value) {
        selectedProjectId.value = queryProject;
    }
    if (queryPath !== docsState.pathPrefix.value && selectedProjectId.value) {
        void docsState.loadPage(selectedProjectId.value, 0, queryPath);
    }
}
watch(selectedProjectId, async (next, prev) => {
    if (!next)
        return;
    // Inbox draft handoff: as soon as the user picks a project, jump
    // to Notepad with create=1 — the modal there consumes the draft.
    if (pendingDraft.value) {
        forwardDraftToNotepad(next);
        return;
    }
    if (prev == null)
        return; // initial bind handled by onMounted
    docsState.pathPrefix.value = DEFAULT_PATH_PREFIX;
    search.value = '';
    await docsState.loadPage(next, 0, DEFAULT_PATH_PREFIX);
    syncUrl();
});
const breadcrumbSegments = computed(() => {
    const prefix = docsState.pathPrefix.value.replace(/\/+$/, '');
    if (!prefix)
        return [];
    return prefix.split('/');
});
function navigateToSegment(idx) {
    if (!selectedProjectId.value)
        return;
    const segs = breadcrumbSegments.value.slice(0, idx + 1);
    const newPath = segs.length > 0 ? `${segs.join('/')}/` : '';
    void docsState.loadPage(selectedProjectId.value, 0, newPath);
    syncUrl();
}
function navigateToRoot() {
    if (!selectedProjectId.value)
        return;
    void docsState.loadPage(selectedProjectId.value, 0, '');
    syncUrl();
}
function pathSegmentBack() {
    if (!selectedProjectId.value)
        return;
    const current = docsState.pathPrefix.value;
    if (!current)
        return;
    const trimmed = current.replace(/\/+$/, '');
    const idx = trimmed.lastIndexOf('/');
    const next = idx >= 0 ? `${trimmed.slice(0, idx)}/` : '';
    void docsState.loadPage(selectedProjectId.value, 0, next);
    syncUrl();
}
function navigateIntoFolder(folder) {
    if (!selectedProjectId.value)
        return;
    const base = docsState.pathPrefix.value.replace(/\/+$/, '');
    const next = base ? `${base}/${folder}/` : `${folder}/`;
    void docsState.loadPage(selectedProjectId.value, 0, next);
    syncUrl();
}
function openInNotepad(docId) {
    if (!selectedProjectId.value)
        return;
    const params = new URLSearchParams();
    params.set('project', selectedProjectId.value);
    params.set('doc', docId);
    window.location.href = `/notepad.html?${params.toString()}`;
}
function openCreateInNotepad() {
    if (!selectedProjectId.value)
        return;
    const params = new URLSearchParams();
    params.set('project', selectedProjectId.value);
    params.set('path', docsState.pathPrefix.value.replace(/\/+$/, ''));
    params.set('create', '1');
    window.location.href = `/notepad.html?${params.toString()}`;
}
// Server-side filter through the existing endpoint: re-load on every
// non-trivial change with a small debounce so typing doesn't flood
// the brain. Empty string clears the filter.
let searchTimer = null;
watch(search, (v) => {
    if (searchTimer)
        clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
        if (!selectedProjectId.value)
            return;
        void docsState.loadPage(selectedProjectId.value, 0, docsState.pathPrefix.value, undefined, v.trim());
    }, 250);
});
async function onProjectListDataChanged() {
    await projectsState.reload();
}
function formatSize(bytes) {
    if (bytes == null)
        return '—';
    if (bytes < 1024)
        return `${bytes} B`;
    if (bytes < 1024 * 1024)
        return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}
function formatDate(ms) {
    if (!ms)
        return '—';
    return new Date(ms).toLocaleDateString();
}
function fileBasename(path) {
    const idx = path.lastIndexOf('/');
    return idx >= 0 ? path.slice(idx + 1) : path;
}
const isEmpty = computed(() => docsState.subFolders.value.length === 0 && docsState.items.value.length === 0);
const totalPages = computed(() => Math.max(1, Math.ceil(docsState.totalCount.value / docsState.pageSize.value)));
function gotoPage(p) {
    if (!selectedProjectId.value)
        return;
    if (p < 0 || p >= totalPages.value)
        return;
    void docsState.loadPage(selectedProjectId.value, p, docsState.pathPrefix.value);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('documents.title')),
    fullHeight: (true),
    focusModel: "auto",
    showSidebar: (true),
}));
const __VLS_2 = __VLS_1({
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('documents.title')),
    fullHeight: (true),
    focusModel: "auto",
    showSidebar: (true),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-2" },
    });
    const __VLS_5 = {}.ProjectListSidebar;
    /** @type {[typeof __VLS_components.ProjectListSidebar, typeof __VLS_components.ProjectListSidebar, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        ...{ 'onFocusMain': {} },
        ...{ 'onDataChanged': {} },
        selectedProject: (__VLS_ctx.selectedProjectId),
        groups: (__VLS_ctx.projectsState.groups.value),
        projects: (__VLS_ctx.projectsState.projects.value),
        loading: (__VLS_ctx.projectsState.loading.value),
        error: (__VLS_ctx.projectsState.error.value),
        heading: (__VLS_ctx.$t('documents.projectsTitle')),
        filterPlaceholder: (__VLS_ctx.$t('documents.projectFilterPlaceholder')),
        ungroupedLabel: (__VLS_ctx.$t('documents.ungrouped')),
        editEnabled: true,
    }));
    const __VLS_7 = __VLS_6({
        ...{ 'onFocusMain': {} },
        ...{ 'onDataChanged': {} },
        selectedProject: (__VLS_ctx.selectedProjectId),
        groups: (__VLS_ctx.projectsState.groups.value),
        projects: (__VLS_ctx.projectsState.projects.value),
        loading: (__VLS_ctx.projectsState.loading.value),
        error: (__VLS_ctx.projectsState.error.value),
        heading: (__VLS_ctx.$t('documents.projectsTitle')),
        filterPlaceholder: (__VLS_ctx.$t('documents.projectFilterPlaceholder')),
        ungroupedLabel: (__VLS_ctx.$t('documents.ungrouped')),
        editEnabled: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    let __VLS_9;
    let __VLS_10;
    let __VLS_11;
    const __VLS_12 = {
        onFocusMain: (...[$event]) => {
            __VLS_ctx.focusZone = 'main';
        }
    };
    const __VLS_13 = {
        onDataChanged: (__VLS_ctx.onProjectListDataChanged)
    };
    __VLS_8.slots.default;
    {
        const { loading: __VLS_thisSlot } = __VLS_8.slots;
        (__VLS_ctx.$t('chat.picker.loading'));
    }
    {
        const { 'filter-no-match': __VLS_thisSlot } = __VLS_8.slots;
        const [{ filter }] = __VLS_getSlotParams(__VLS_thisSlot);
        (__VLS_ctx.$t('documents.projectFilterNoMatch', { filter }));
    }
    var __VLS_8;
}
if (!__VLS_ctx.selectedProjectId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "h-full flex items-center justify-center" },
    });
    const __VLS_14 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_15 = __VLS_asFunctionalComponent(__VLS_14, new __VLS_14({
        headline: (__VLS_ctx.$t('documents.empty.noProjectHeadline')),
        body: (__VLS_ctx.$t('documents.empty.noProjectBody')),
    }));
    const __VLS_16 = __VLS_15({
        headline: (__VLS_ctx.$t('documents.empty.noProjectHeadline')),
        body: (__VLS_ctx.$t('documents.empty.noProjectBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_15));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "h-full min-h-0 flex flex-col" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-3 flex-wrap" },
    });
    const __VLS_18 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_19 = __VLS_asFunctionalComponent(__VLS_18, new __VLS_18({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        disabled: (!__VLS_ctx.docsState.pathPrefix.value),
        title: (__VLS_ctx.$t('documents.pathBack')),
    }));
    const __VLS_20 = __VLS_19({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        disabled: (!__VLS_ctx.docsState.pathPrefix.value),
        title: (__VLS_ctx.$t('documents.pathBack')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_19));
    let __VLS_22;
    let __VLS_23;
    let __VLS_24;
    const __VLS_25 = {
        onClick: (__VLS_ctx.pathSegmentBack)
    };
    __VLS_21.slots.default;
    var __VLS_21;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
        ...{ class: "flex items-center gap-1 text-sm font-mono opacity-80 flex-1 min-w-0 truncate" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.navigateToRoot) },
        type: "button",
        ...{ class: "opacity-70 hover:opacity-100 hover:underline" },
    });
    for (const [seg, idx] of __VLS_getVForSourceType((__VLS_ctx.breadcrumbSegments))) {
        (idx);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-40" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.selectedProjectId))
                        return;
                    __VLS_ctx.navigateToSegment(idx);
                } },
            type: "button",
            ...{ class: "opacity-70 hover:opacity-100 hover:underline" },
        });
        (seg);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "w-[180px] shrink-0" },
    });
    const __VLS_26 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_27 = __VLS_asFunctionalComponent(__VLS_26, new __VLS_26({
        modelValue: (__VLS_ctx.search),
        placeholder: (__VLS_ctx.$t('documents.searchPlaceholder')),
    }));
    const __VLS_28 = __VLS_27({
        modelValue: (__VLS_ctx.search),
        placeholder: (__VLS_ctx.$t('documents.searchPlaceholder')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_27));
    const __VLS_30 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_31 = __VLS_asFunctionalComponent(__VLS_30, new __VLS_30({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
        title: (__VLS_ctx.$t('documents.newDocument')),
    }));
    const __VLS_32 = __VLS_31({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
        title: (__VLS_ctx.$t('documents.newDocument')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_31));
    let __VLS_34;
    let __VLS_35;
    let __VLS_36;
    const __VLS_37 = {
        onClick: (__VLS_ctx.openCreateInNotepad)
    };
    __VLS_33.slots.default;
    var __VLS_33;
    if (__VLS_ctx.docsState.error.value) {
        const __VLS_38 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_39 = __VLS_asFunctionalComponent(__VLS_38, new __VLS_38({
            variant: "error",
            ...{ class: "m-4" },
        }));
        const __VLS_40 = __VLS_39({
            variant: "error",
            ...{ class: "m-4" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_39));
        __VLS_41.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.docsState.error.value);
        var __VLS_41;
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-y-auto" },
    });
    if (__VLS_ctx.docsState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-6 text-sm opacity-60" },
        });
        (__VLS_ctx.$t('documents.loading'));
    }
    else if (__VLS_ctx.isEmpty) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-6" },
        });
        const __VLS_42 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_43 = __VLS_asFunctionalComponent(__VLS_42, new __VLS_42({
            headline: (__VLS_ctx.$t('documents.empty.folderHeadline')),
            body: (__VLS_ctx.$t('documents.empty.folderBody')),
        }));
        const __VLS_44 = __VLS_43({
            headline: (__VLS_ctx.$t('documents.empty.folderHeadline')),
            body: (__VLS_ctx.$t('documents.empty.folderBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_43));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
            ...{ class: "w-full text-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.thead, __VLS_intrinsicElements.thead)({
            ...{ class: "text-xs uppercase opacity-60 sticky top-0 bg-base-100 z-[1]" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "text-left px-4 py-2 w-8" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "text-left px-2 py-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "text-left px-2 py-2 w-24" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "text-left px-2 py-2 w-32" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "text-right px-2 py-2 w-20" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "text-left px-2 py-2 w-28" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "text-left px-4 py-2 w-32" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
        for (const [folder] of __VLS_getVForSourceType((__VLS_ctx.docsState.subFolders.value))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!!(__VLS_ctx.docsState.loading.value))
                            return;
                        if (!!(__VLS_ctx.isEmpty))
                            return;
                        __VLS_ctx.navigateIntoFolder(folder);
                    } },
                key: (`f:${folder}`),
                ...{ class: "border-b border-base-200 hover:bg-base-200/60 cursor-pointer" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-4 py-1.5" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-2 py-1.5 font-medium" },
            });
            (folder);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-2 py-1.5 opacity-50" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-2 py-1.5" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-2 py-1.5" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-2 py-1.5" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-4 py-1.5" },
            });
        }
        for (const [doc] of __VLS_getVForSourceType((__VLS_ctx.docsState.items.value))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!!(__VLS_ctx.docsState.loading.value))
                            return;
                        if (!!(__VLS_ctx.isEmpty))
                            return;
                        __VLS_ctx.openInNotepad(doc.id);
                    } },
                key: (doc.id),
                ...{ class: "border-b border-base-200 hover:bg-base-200/60 cursor-pointer" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-4 py-1.5" },
            });
            /** @type {[typeof DocumentIcon, ]} */ ;
            // @ts-ignore
            const __VLS_46 = __VLS_asFunctionalComponent(DocumentIcon, new DocumentIcon({
                kind: (doc.kind ?? null),
                mimeType: (doc.mimeType ?? null),
            }));
            const __VLS_47 = __VLS_46({
                kind: (doc.kind ?? null),
                mimeType: (doc.mimeType ?? null),
            }, ...__VLS_functionalComponentArgsRest(__VLS_46));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-2 py-1.5" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center gap-2 min-w-0" },
            });
            if (doc.color) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
                    ...{ class: "size-2 rounded-full flex-shrink-0" },
                    ...{ class: (__VLS_ctx.accentColorDotClass(doc.color)) },
                    'aria-label': (`color ${doc.color}`),
                });
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "font-medium truncate" },
            });
            (doc.title || __VLS_ctx.fileBasename(doc.path));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60 font-mono truncate" },
            });
            (doc.path);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-2 py-1.5 text-xs opacity-70" },
            });
            (doc.kind ?? '—');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-2 py-1.5 text-xs opacity-70 truncate" },
            });
            ((doc.tags ?? []).join(', '));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-2 py-1.5 text-right text-xs" },
            });
            (__VLS_ctx.formatSize(doc.size));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-2 py-1.5 text-xs" },
            });
            (__VLS_ctx.formatDate(doc.createdAtMs));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "px-4 py-1.5 text-xs opacity-70 truncate" },
            });
            (doc.createdBy ?? '—');
        }
    }
    if (__VLS_ctx.totalPages > 1) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "border-t border-base-300 bg-base-100 px-4 py-2 flex items-center gap-2 text-sm" },
        });
        const __VLS_49 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.docsState.page.value === 0),
        }));
        const __VLS_51 = __VLS_50({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.docsState.page.value === 0),
        }, ...__VLS_functionalComponentArgsRest(__VLS_50));
        let __VLS_53;
        let __VLS_54;
        let __VLS_55;
        const __VLS_56 = {
            onClick: (...[$event]) => {
                if (!!(!__VLS_ctx.selectedProjectId))
                    return;
                if (!(__VLS_ctx.totalPages > 1))
                    return;
                __VLS_ctx.gotoPage(__VLS_ctx.docsState.page.value - 1);
            }
        };
        __VLS_52.slots.default;
        var __VLS_52;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.docsState.page.value + 1);
        (__VLS_ctx.totalPages);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-50 ml-2" },
        });
        (__VLS_ctx.docsState.totalCount.value);
        (__VLS_ctx.t('documents.totalItems'));
        const __VLS_57 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.docsState.page.value >= __VLS_ctx.totalPages - 1),
        }));
        const __VLS_59 = __VLS_58({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.docsState.page.value >= __VLS_ctx.totalPages - 1),
        }, ...__VLS_functionalComponentArgsRest(__VLS_58));
        let __VLS_61;
        let __VLS_62;
        let __VLS_63;
        const __VLS_64 = {
            onClick: (...[$event]) => {
                if (!!(!__VLS_ctx.selectedProjectId))
                    return;
                if (!(__VLS_ctx.totalPages > 1))
                    return;
                __VLS_ctx.gotoPage(__VLS_ctx.docsState.page.value + 1);
            }
        };
        __VLS_60.slots.default;
        var __VLS_60;
    }
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['pb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:underline']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-40']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:underline']} */ ;
/** @type {__VLS_StyleScopedClasses['w-[180px]']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['m-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['sticky']} */ ;
/** @type {__VLS_StyleScopedClasses['top-0']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['z-[1]']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-24']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['text-right']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-20']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-28']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200/60']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200/60']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['size-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-right']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            ProjectListSidebar: ProjectListSidebar,
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            VInput: VInput,
            accentColorDotClass: accentColorDotClass,
            DocumentIcon: DocumentIcon,
            t: t,
            projectsState: projectsState,
            docsState: docsState,
            selectedProjectId: selectedProjectId,
            focusZone: focusZone,
            search: search,
            breadcrumbSegments: breadcrumbSegments,
            navigateToSegment: navigateToSegment,
            navigateToRoot: navigateToRoot,
            pathSegmentBack: pathSegmentBack,
            navigateIntoFolder: navigateIntoFolder,
            openInNotepad: openInNotepad,
            openCreateInNotepad: openCreateInNotepad,
            onProjectListDataChanged: onProjectListDataChanged,
            formatSize: formatSize,
            formatDate: formatDate,
            fileBasename: fileBasename,
            isEmpty: isEmpty,
            totalPages: totalPages,
            gotoPage: gotoPage,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=DocumentExplorerApp.vue.js.map