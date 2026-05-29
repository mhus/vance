import { defineAsyncComponent, onMounted, ref } from 'vue';
import { EditorShell, VAlert, VEmptyState } from '@/components';
import { brainFetch } from '@vance/shared';
// Lazy-load app sub-editors so the bundle stays slim.
const KanbanBoard = defineAsyncComponent(() => import('./kanban/KanbanBoard.vue'));
const CalendarPlanner = defineAsyncComponent(() => import('./calendar/CalendarPlanner.vue'));
const projectId = ref('');
const folder = ref('');
const appType = ref(null);
const docTitle = ref('');
const loading = ref(true);
const error = ref(null);
onMounted(async () => {
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
    fullHeight: true,
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.docTitle || 'App'),
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
else {
    const __VLS_17 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        headline: "Unknown app type",
        body: (`No editor registered for app type '${__VLS_ctx.appType ?? '(missing)'}'. The folder's _app.yaml may be malformed.`),
    }));
    const __VLS_19 = __VLS_18({
        headline: "Unknown app type",
        body: (`No editor registered for app type '${__VLS_ctx.appType ?? '(missing)'}'. The folder's _app.yaml may be malformed.`),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
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
            projectId: projectId,
            folder: folder,
            appType: appType,
            docTitle: docTitle,
            loading: loading,
            error: error,
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