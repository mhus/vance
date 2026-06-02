import { computed, onMounted, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
import DOMPurify from 'dompurify';
import mammoth from 'mammoth';
const props = withDefaults(defineProps(), { mode: 'embedded' });
const loading = ref(false);
const loadError = ref(null);
const html = ref('');
const warnings = ref([]);
const url = computed(() => {
    const id = props.document?.id ?? props.documentId;
    if (!id)
        return '';
    return documentContentUrl(id);
});
async function loadDocx() {
    if (!url.value)
        return;
    loading.value = true;
    loadError.value = null;
    html.value = '';
    warnings.value = [];
    try {
        // cache: 'no-store' is critical — after an office-edit save
        // the bytes change but the URL stays identical, and the
        // browser would otherwise hand us the stale prior body.
        const res = await fetch(url.value, {
            credentials: 'include',
            cache: 'no-store',
        });
        if (!res.ok) {
            throw new Error(`HTTP ${res.status} ${res.statusText}`);
        }
        const arrayBuffer = await res.arrayBuffer();
        const result = await mammoth.convertToHtml({ arrayBuffer });
        // mammoth's HTML is structured but unstyled; DOMPurify strips
        // anything it doesn't recognise (event handlers, scripts) —
        // mammoth output is already safe but we sanitise defensively.
        html.value = DOMPurify.sanitize(result.value, {
            USE_PROFILES: { html: true },
        });
        if (result.messages && result.messages.length > 0) {
            warnings.value = result.messages
                .map((m) => m.message)
                .slice(0, 5);
        }
    }
    catch (e) {
        loadError.value = e.message || 'Failed to load DOCX';
    }
    finally {
        loading.value = false;
    }
}
onMounted(() => { void loadDocx(); });
watch(() => url.value, () => { void loadDocx(); });
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ mode: 'embedded' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['docx-reload']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: (['docx-view', `docx-view--${__VLS_ctx.mode}`]) },
});
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "docx-state" },
    });
}
else if (__VLS_ctx.loadError) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "docx-state docx-state--err" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    (__VLS_ctx.loadError);
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "docx-toolbar" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.loadDocx) },
        type: "button",
        ...{ class: "docx-reload" },
        title: ('Vorschau aktualisieren — zeigt Änderungen nach einem Office-Edit-Save.'),
    });
    if (__VLS_ctx.warnings.length) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "docx-warnings" },
            title: (__VLS_ctx.warnings.join('\n')),
        });
        (__VLS_ctx.warnings.length);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "docx-stage" },
    });
    __VLS_asFunctionalDirective(__VLS_directives.vHtml)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.html) }, null, null);
}
/** @type {__VLS_StyleScopedClasses['docx-view']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-state']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-state']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-state--err']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-toolbar']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-reload']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-warnings']} */ ;
/** @type {__VLS_StyleScopedClasses['docx-stage']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            loading: loading,
            loadError: loadError,
            html: html,
            warnings: warnings,
            loadDocx: loadDocx,
        };
    },
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=DocxView.vue.js.map