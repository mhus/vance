import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';
const props = defineProps();
const containerRef = ref(null);
const loading = ref(false);
const loadError = ref(null);
const officeNotConfigured = ref(false);
// Generated per-instance — ONLYOFFICE's SDK targets DocEditor by
// element id. Random suffix avoids clashes if two editors mount
// in the same session.
const editorElementId = `vance-office-${Math.random().toString(36).slice(2, 10)}`;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
let editorInstance = null;
let sdkLoadPromise = null;
const isLoaded = computed(() => !loading.value && !loadError.value && !officeNotConfigured.value);
async function loadEditor() {
    if (!props.documentId)
        return;
    loading.value = true;
    loadError.value = null;
    officeNotConfigured.value = false;
    destroyEditor();
    let session;
    try {
        session = await brainFetch('GET', `office/session/${encodeURIComponent(props.documentId)}`);
    }
    catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        // The brain returns 503 with {error:'office-not-configured'}
        // when the office.* settings aren't filled in — we want a
        // helpful empty-state, not a red error banner.
        if (/503/.test(msg) || /office-not-configured/.test(msg)) {
            officeNotConfigured.value = true;
        }
        else {
            loadError.value = msg;
        }
        loading.value = false;
        return;
    }
    try {
        await loadSdk(session.officeUrl);
    }
    catch (e) {
        loadError.value = `Konnte SDK von ${session.officeUrl} nicht laden: `
            + (e instanceof Error ? e.message : String(e));
        loading.value = false;
        return;
    }
    try {
        instantiateEditor(session);
    }
    catch (e) {
        loadError.value = `Editor-Mount fehlgeschlagen: `
            + (e instanceof Error ? e.message : String(e));
        loading.value = false;
        return;
    }
    loading.value = false;
}
/**
 * Inject the doc-server's API script once per page. Subsequent
 * mounts reuse the already-loaded {@code window.DocsAPI}.
 */
function loadSdk(officeUrl) {
    if (typeof window !== 'undefined'
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        && window.DocsAPI) {
        return Promise.resolve();
    }
    if (sdkLoadPromise)
        return sdkLoadPromise;
    sdkLoadPromise = new Promise((resolve, reject) => {
        const base = officeUrl.replace(/\/+$/, '');
        const src = `${base}/web-apps/apps/api/documents/api.js`;
        const script = document.createElement('script');
        script.src = src;
        script.async = true;
        script.onload = () => resolve();
        script.onerror = () => {
            sdkLoadPromise = null;
            reject(new Error(`failed to load ${src}`));
        };
        document.head.appendChild(script);
    });
    return sdkLoadPromise;
}
function instantiateEditor(session) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const DocsAPI = window.DocsAPI;
    if (!DocsAPI) {
        throw new Error('DocsAPI not present after SDK load');
    }
    // The container must exist in the DOM before DocEditor mounts
    // and must be empty — DocsAPI replaces its innerHTML.
    if (!containerRef.value) {
        throw new Error('container ref not ready');
    }
    containerRef.value.innerHTML = '';
    const target = document.createElement('div');
    target.id = editorElementId;
    target.style.width = '100%';
    target.style.height = '100%';
    containerRef.value.appendChild(target);
    const config = {
        document: session.document,
        documentType: session.documentType,
        editorConfig: {
            ...session.editorConfig,
            // Reasonable defaults; the operator can override per-tenant
            // via further setting fields later.
            lang: navigator.language?.split('-')[0] ?? 'de',
            customization: {
                compactHeader: false,
                autosave: true,
            },
        },
        token: session.token,
        width: '100%',
        height: '100%',
        type: 'desktop',
    };
    editorInstance = new DocsAPI.DocEditor(editorElementId, config);
}
function destroyEditor() {
    try {
        editorInstance?.destroyEditor?.();
    }
    catch {
        /* best-effort cleanup; the SDK occasionally throws on double-destroy */
    }
    editorInstance = null;
    if (containerRef.value)
        containerRef.value.innerHTML = '';
}
onMounted(() => { void loadEditor(); });
watch(() => props.documentId, () => { void loadEditor(); });
onBeforeUnmount(() => { destroyEditor(); });
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "office-editor" },
});
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "office-state" },
    });
}
else if (__VLS_ctx.officeNotConfigured) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "office-state office-state--info" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.em, __VLS_intrinsicElements.em)({});
}
else if (__VLS_ctx.loadError) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "office-state office-state--err" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
        ...{ class: "office-error-msg" },
    });
    (__VLS_ctx.loadError);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ref: "containerRef",
    ...{ class: "office-frame" },
});
__VLS_asFunctionalDirective(__VLS_directives.vShow)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.isLoaded) }, null, null);
/** @type {typeof __VLS_ctx.containerRef} */ ;
/** @type {__VLS_StyleScopedClasses['office-editor']} */ ;
/** @type {__VLS_StyleScopedClasses['office-state']} */ ;
/** @type {__VLS_StyleScopedClasses['office-state']} */ ;
/** @type {__VLS_StyleScopedClasses['office-state--info']} */ ;
/** @type {__VLS_StyleScopedClasses['office-state']} */ ;
/** @type {__VLS_StyleScopedClasses['office-state--err']} */ ;
/** @type {__VLS_StyleScopedClasses['office-error-msg']} */ ;
/** @type {__VLS_StyleScopedClasses['office-frame']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            containerRef: containerRef,
            loading: loading,
            loadError: loadError,
            officeNotConfigured: officeNotConfigured,
            isLoaded: isLoaded,
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
//# sourceMappingURL=OfficeEditor.vue.js.map