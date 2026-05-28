import { computed, onMounted, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
import DOMPurify from 'dompurify';
import * as XLSX from 'xlsx';
const props = withDefaults(defineProps(), { mode: 'embedded' });
const loading = ref(false);
const loadError = ref(null);
const sheetNames = ref([]);
const activeSheet = ref('');
const sheetHtml = ref({});
const url = computed(() => {
    const id = props.document?.id ?? props.documentId;
    if (!id)
        return '';
    return documentContentUrl(id);
});
const currentHtml = computed(() => sheetHtml.value[activeSheet.value] ?? '');
async function loadXlsx() {
    if (!url.value)
        return;
    loading.value = true;
    loadError.value = null;
    sheetNames.value = [];
    activeSheet.value = '';
    sheetHtml.value = {};
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
        const wb = XLSX.read(arrayBuffer, { type: 'array' });
        sheetNames.value = [...wb.SheetNames];
        activeSheet.value = sheetNames.value[0] ?? '';
        const rendered = {};
        for (const name of wb.SheetNames) {
            const ws = wb.Sheets[name];
            if (!ws)
                continue;
            const html = XLSX.utils.sheet_to_html(ws, { editable: false });
            rendered[name] = DOMPurify.sanitize(html, {
                USE_PROFILES: { html: true },
            });
        }
        sheetHtml.value = rendered;
    }
    catch (e) {
        loadError.value = e.message || 'Failed to load XLSX';
    }
    finally {
        loading.value = false;
    }
}
function selectSheet(name) {
    activeSheet.value = name;
}
onMounted(() => { void loadXlsx(); });
watch(() => url.value, () => { void loadXlsx(); });
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ mode: 'embedded' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['xlsx-reload']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-stage']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: (['xlsx-view', `xlsx-view--${__VLS_ctx.mode}`]) },
});
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "xlsx-state" },
    });
}
else if (__VLS_ctx.loadError) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "xlsx-state xlsx-state--err" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    (__VLS_ctx.loadError);
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "xlsx-toolbar" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.loadXlsx) },
        type: "button",
        ...{ class: "xlsx-reload" },
        title: "Vorschau aktualisieren — zeigt Änderungen nach einem Office-Edit-Save.",
    });
    if (__VLS_ctx.sheetNames.length > 1) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "xlsx-tabs" },
        });
        for (const [name] of __VLS_getVForSourceType((__VLS_ctx.sheetNames))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.loadError))
                            return;
                        if (!(__VLS_ctx.sheetNames.length > 1))
                            return;
                        __VLS_ctx.selectSheet(name);
                    } },
                key: (name),
                type: "button",
                ...{ class: (['xlsx-tab', { 'xlsx-tab--active': name === __VLS_ctx.activeSheet }]) },
            });
            (name);
        }
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "xlsx-stage" },
    });
    __VLS_asFunctionalDirective(__VLS_directives.vHtml)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.currentHtml) }, null, null);
}
/** @type {__VLS_StyleScopedClasses['xlsx-view']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-state']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-state']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-state--err']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-toolbar']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-reload']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['xlsx-stage']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            loading: loading,
            loadError: loadError,
            sheetNames: sheetNames,
            activeSheet: activeSheet,
            currentHtml: currentHtml,
            loadXlsx: loadXlsx,
            selectSheet: selectSheet,
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
//# sourceMappingURL=XlsxView.vue.js.map