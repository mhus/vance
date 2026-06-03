import { computed, defineAsyncComponent, onMounted, ref } from 'vue';
import { EditorShell, VAlert, VEmptyState } from '@/components';
import { brainFetch } from '@vance/shared';
import { useTenantProjects } from '@/composables/useTenantProjects';
// Lazy-load app sub-editors so the bundle stays slim.
const KanbanBoard = defineAsyncComponent(() => import('./kanban/KanbanBoard.vue'));
const CalendarPlanner = defineAsyncComponent(() => import('./calendar/CalendarPlanner.vue'));
// Loaded via Module Federation at runtime — see vance-face's vite.config.ts
// federation host config + env.d.ts module declaration. Vite-ignore is
// needed because the resolver doesn't know about federation remotes at
// build time; the federation plugin rewrites this import to a runtime
// fetch of /addons/vance-addon-brain-slideshow/remoteEntry.js.
const SlideshowApp = defineAsyncComponent(() => import(/* @vite-ignore */ 'vance_addon_slideshow/SlideshowApp'));
const projectId = ref('');
const folder = ref('');
const appType = ref(null);
const docTitle = ref('');
const loading = ref(true);
const error = ref(null);
const projectsState = useTenantProjects();
const projectTitle = computed(() => {
    if (!projectId.value)
        return '';
    const match = projectsState.projects.value.find((p) => p.name === projectId.value);
    return match?.title ?? projectId.value;
});
/**
 * Build a URL that opens the Documents picker at a specific folder
 * inside the current project. The picker honors {@code ?path=…} on
 * mount, so this lands the user exactly where the app folder sits
 * (mirrors {@link DocumentApp.applyPathFilter}).
 */
function documentsUrl(pathPrefix) {
    const params = new URLSearchParams({ projectId: projectId.value });
    if (pathPrefix)
        params.set('path', pathPrefix);
    return `/documents.html?${params.toString()}`;
}
/**
 * Top-bar breadcrumbs. Mirrors the {@link DocumentApp}'s pattern:
 * project title → each folder segment (clickable, drilling the
 * Documents picker into that level) → the app title as a final,
 * non-clickable leaf. The app editor itself has no internal
 * navigation, so the breadcrumb is the only way back into the
 * Documents view.
 */
const breadcrumbs = computed(() => {
    if (!projectId.value)
        return [];
    const crumbs = [];
    // Project root → Documents picker at the project root.
    crumbs.push({
        text: projectTitle.value,
        onClick: () => window.location.assign(documentsUrl('')),
    });
    // Folder segments. The app folder itself (the last segment) is
    // also clickable — clicking it lands the picker inside the
    // folder that contains the {@code _app.yaml}, so the user can
    // see the file alongside the rest of the folder's content.
    if (folder.value) {
        const segments = folder.value.split('/').filter((s) => s.length > 0);
        let acc = '';
        for (const seg of segments) {
            acc += seg + '/';
            const target = acc;
            crumbs.push({
                text: seg,
                onClick: () => window.location.assign(documentsUrl(target)),
            });
        }
    }
    // App title — final crumb, non-clickable (the user is here).
    if (docTitle.value && docTitle.value !== folder.value) {
        crumbs.push(docTitle.value);
    }
    return crumbs;
});
onMounted(async () => {
    // Fire-and-forget — breadcrumbs upgrade to titled crumbs once
    // the project list lands. The app sub-editor render path doesn't
    // depend on it, so we don't block on the request.
    void projectsState.reload();
    try {
        const params = new URLSearchParams(window.location.search);
        const queryProject = params.get('projectId') ?? '';
        const queryFolder = params.get('folder') ?? '';
        const queryDoc = params.get('documentId');
        projectId.value = queryProject;
        if (queryDoc) {
            // Resolve folder + app type from the manifest document.
            const doc = await brainFetch('GET', `documents/${queryDoc}`);
            projectId.value = doc.projectId;
            folder.value = doc.path.replace(/\/_app\.yaml$/, '');
            appType.value = doc.headers?.app ?? null;
            docTitle.value = doc.title ?? folder.value;
        }
        else if (queryFolder) {
            folder.value = queryFolder;
            // Look up the manifest to learn the app type.
            const url = `documents/by-path?projectId=${encodeURIComponent(projectId.value)}&path=${encodeURIComponent(folder.value + '/_app.yaml')}`;
            const doc = await brainFetch('GET', url);
            appType.value = doc.headers?.app ?? null;
            docTitle.value = doc.title ?? folder.value;
        }
        else {
            error.value = 'Missing folder or documentId in the URL.';
        }
    }
    catch (e) {
        error.value = `Could not load app manifest: ${e.message}`;
    }
    finally {
        loading.value = false;
    }
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.docTitle || 'App'),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: true,
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.docTitle || 'App'),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-8 text-base-content/70" },
    });
}
else if (__VLS_ctx.error) {
    const __VLS_5 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        variant: "error",
        ...{ class: "m-4" },
    }));
    const __VLS_7 = __VLS_6({
        variant: "error",
        ...{ class: "m-4" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    __VLS_8.slots.default;
    (__VLS_ctx.error);
    var __VLS_8;
}
else if (__VLS_ctx.appType === 'kanban') {
    const __VLS_9 = {}.KanbanBoard;
    /** @type {[typeof __VLS_components.KanbanBoard, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        projectId: (__VLS_ctx.projectId),
        folder: (__VLS_ctx.folder),
        title: (__VLS_ctx.docTitle),
    }));
    const __VLS_11 = __VLS_10({
        projectId: (__VLS_ctx.projectId),
        folder: (__VLS_ctx.folder),
        title: (__VLS_ctx.docTitle),
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
}
else if (__VLS_ctx.appType === 'calendar') {
    const __VLS_13 = {}.CalendarPlanner;
    /** @type {[typeof __VLS_components.CalendarPlanner, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        projectId: (__VLS_ctx.projectId),
        folder: (__VLS_ctx.folder),
        title: (__VLS_ctx.docTitle),
    }));
    const __VLS_15 = __VLS_14({
        projectId: (__VLS_ctx.projectId),
        folder: (__VLS_ctx.folder),
        title: (__VLS_ctx.docTitle),
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
}
else if (__VLS_ctx.appType === 'slideshow') {
    const __VLS_17 = {}.SlideshowApp;
    /** @type {[typeof __VLS_components.SlideshowApp, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        projectId: (__VLS_ctx.projectId),
        folder: (__VLS_ctx.folder),
        title: (__VLS_ctx.docTitle),
    }));
    const __VLS_19 = __VLS_18({
        projectId: (__VLS_ctx.projectId),
        folder: (__VLS_ctx.folder),
        title: (__VLS_ctx.docTitle),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
}
else {
    const __VLS_21 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        headline: "Unknown app type",
        body: (`No editor registered for app type '${__VLS_ctx.appType ?? '(missing)'}'. The folder's _app.yaml may be malformed.`),
    }));
    const __VLS_23 = __VLS_22({
        headline: "Unknown app type",
        body: (`No editor registered for app type '${__VLS_ctx.appType ?? '(missing)'}'. The folder's _app.yaml may be malformed.`),
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['p-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['m-4']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VEmptyState: VEmptyState,
            KanbanBoard: KanbanBoard,
            CalendarPlanner: CalendarPlanner,
            SlideshowApp: SlideshowApp,
            projectId: projectId,
            folder: folder,
            appType: appType,
            docTitle: docTitle,
            loading: loading,
            error: error,
            breadcrumbs: breadcrumbs,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=AppEditor.vue.js.map