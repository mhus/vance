import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VColorPicker, VInput, VSelect } from '@/components';
import { useI18n } from 'vue-i18n';
import DocumentArchives from '@/document/DocumentArchives.vue';
import { useCortexStore } from '../stores/cortexStore';
const props = defineProps();
const store = useCortexStore();
const { t } = useI18n();
const editName = ref('');
const editTitle = ref('');
const editTags = ref('');
const editColor = ref(null);
const editMime = ref('');
const saving = ref(false);
const error = ref(null);
const mimeOptions = computed(() => {
    const docGroup = t('documents.mime.groupDoc');
    const codeGroup = t('documents.mime.groupCode');
    const webGroup = t('documents.mime.groupWeb');
    const base = [
        { value: 'text/markdown', label: 'Markdown (.md)', group: docGroup },
        { value: 'text/plain', label: 'Plain text (.txt)', group: docGroup },
        { value: 'application/json', label: 'JSON', group: docGroup },
        { value: 'application/yaml', label: 'YAML', group: docGroup },
        { value: 'application/xml', label: 'XML', group: docGroup },
        { value: 'application/javascript', label: 'JavaScript (.js)', group: codeGroup },
        { value: 'application/typescript', label: 'TypeScript (.ts)', group: codeGroup },
        { value: 'text/x-python', label: 'Python (.py)', group: codeGroup },
        { value: 'application/x-sh', label: 'Bash / Shell (.sh)', group: codeGroup },
        { value: 'text/x-r', label: 'R (.r)', group: codeGroup },
        { value: 'text/x-java-source', label: 'Java (.java)', group: codeGroup },
        { value: 'application/sql', label: 'SQL', group: codeGroup },
        { value: 'text/x-tex', label: 'LaTeX (.tex, .sty, .cls, .ltx, .dtx)', group: codeGroup },
        { value: 'text/x-bibtex', label: 'BibTeX (.bib, .bst)', group: codeGroup },
        { value: 'text/html', label: 'HTML', group: webGroup },
        { value: 'text/css', label: 'CSS', group: webGroup },
    ];
    // Preserve the current mimeType if it's not in the curated list — the
    // user otherwise loses information just by opening the panel.
    const current = editMime.value;
    if (current && !base.some((o) => o.value === current)) {
        base.unshift({ value: current, label: current, group: docGroup });
    }
    return base;
});
// Seed editable fields whenever the document changes (tab switch or
// after a refresh). User edits in progress get overwritten — caller
// is expected to either save or close the panel before switching.
watch(() => props.document.id, () => {
    editName.value = props.document.name ?? '';
    editTitle.value = props.document.title ?? '';
    editTags.value = (props.document.tags ?? []).join(', ');
    editColor.value = props.document.color ?? null;
    editMime.value = props.document.mimeType ?? '';
    error.value = null;
}, { immediate: true });
const isDirty = computed(() => {
    const nameNow = props.document.name ?? '';
    const titleNow = props.document.title ?? '';
    const tagsNow = (props.document.tags ?? []).join(', ');
    const colorNow = props.document.color ?? null;
    const mimeNow = props.document.mimeType ?? '';
    return (editName.value.trim() !== nameNow
        || editTitle.value !== titleNow
        || editTags.value !== tagsNow
        || editColor.value !== colorNow
        || editMime.value !== mimeNow);
});
// Replace the trailing segment of the current document path with the
// edited name, keeping the folder unchanged. Returns null when the
// name didn't change, is empty, or contains a slash (the rename input
// is filename-only — moves go through a separate path edit, not this
// panel).
function buildRenamedPath() {
    const trimmed = editName.value.trim();
    if (!trimmed || trimmed === props.document.name)
        return null;
    if (trimmed.includes('/')) {
        throw new Error('Name must not contain "/" — use the path field to move the document.');
    }
    const path = props.document.path;
    const slash = path.lastIndexOf('/');
    return slash < 0 ? trimmed : `${path.substring(0, slash + 1)}${trimmed}`;
}
// Read-only front-matter / upload-inferred headers from
// {@code DocumentDocument.headers}. Sorted by key for stable display.
const headerEntries = computed(() => {
    const map = props.document.headers ?? {};
    return Object.entries(map).sort(([a], [b]) => a.localeCompare(b));
});
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
    return new Date(ms).toLocaleString();
}
async function onSave() {
    saving.value = true;
    error.value = null;
    try {
        const tags = editTags.value
            .split(',')
            .map((s) => s.trim())
            .filter((s) => s.length > 0);
        const wasColor = props.document.color ?? null;
        const wasMime = props.document.mimeType ?? '';
        const body = {
            title: editTitle.value.trim() || null,
            tags,
        };
        if (editColor.value !== wasColor) {
            if (editColor.value === null) {
                body.clearColor = true;
            }
            else {
                body.color = editColor.value;
            }
        }
        if (editMime.value !== wasMime && editMime.value) {
            body.mimeType = editMime.value;
        }
        const renamedPath = buildRenamedPath();
        if (renamedPath !== null) {
            body.newPath = renamedPath;
        }
        await store.updateMeta(props.document.id, body);
    }
    catch (e) {
        error.value = e instanceof Error ? e.message : 'Failed to save properties';
    }
    finally {
        saving.value = false;
    }
}
// DocumentArchives expects a DocumentDto — build a partial one from
// the CortexDocument. Only the {@code id} field is functionally
// consulted by the archives panel (load + restore go by id).
const dtoForArchives = computed(() => ({
    id: props.document.id,
    projectId: store.projectId ?? '',
    path: props.document.path,
    name: props.document.name,
    title: props.document.title ?? undefined,
    color: props.document.color ?? undefined,
    mimeType: props.document.mimeType ?? undefined,
    size: props.document.size ?? 0,
    tags: props.document.tags ?? [],
    createdAtMs: props.document.createdAtMs ?? undefined,
    createdBy: props.document.createdBy ?? undefined,
    inline: !!props.document.inlineText,
    inlineText: props.document.inlineText || undefined,
    kind: props.document.kind ?? undefined,
    headers: props.document.headers ?? {},
    autoSummary: props.document.autoSummary ?? false,
    summaryDirty: props.document.summaryDirty ?? false,
    summary: props.document.summary ?? undefined,
    notes: props.document.notes ?? {},
}));
async function onRestored() {
    // Re-pull the document so the body + meta in the tab match the
    // restored version.
    await store.reloadTab(props.document.id);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['properties-panel']} */ ;
/** @type {__VLS_StyleScopedClasses['properties-panel']} */ ;
/** @type {__VLS_StyleScopedClasses['properties-panel']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "properties-panel border-b border-base-300 bg-base-100 px-3 py-2 text-xs" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "grid grid-cols-1 md:grid-cols-2 gap-x-4 gap-y-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "grid grid-cols-2 gap-x-2 gap-y-1" },
});
const __VLS_0 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    modelValue: (__VLS_ctx.editName),
    label: "Name",
    placeholder: "filename",
    disabled: (__VLS_ctx.saving),
}));
const __VLS_2 = __VLS_1({
    modelValue: (__VLS_ctx.editName),
    label: "Name",
    placeholder: "filename",
    disabled: (__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
const __VLS_4 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
    modelValue: (__VLS_ctx.editTitle),
    label: "Title",
    placeholder: "(no title)",
    disabled: (__VLS_ctx.saving),
}));
const __VLS_6 = __VLS_5({
    modelValue: (__VLS_ctx.editTitle),
    label: "Title",
    placeholder: "(no title)",
    disabled: (__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_5));
const __VLS_8 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    modelValue: (__VLS_ctx.editTags),
    label: "Tags",
    placeholder: "comma, separated",
    disabled: (__VLS_ctx.saving),
}));
const __VLS_10 = __VLS_9({
    modelValue: (__VLS_ctx.editTags),
    label: "Tags",
    placeholder: "comma, separated",
    disabled: (__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
const __VLS_12 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
    modelValue: (__VLS_ctx.editMime),
    options: (__VLS_ctx.mimeOptions),
    label: "MIME",
    disabled: (__VLS_ctx.saving),
}));
const __VLS_14 = __VLS_13({
    modelValue: (__VLS_ctx.editMime),
    options: (__VLS_ctx.mimeOptions),
    label: "MIME",
    disabled: (__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_13));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "col-span-2" },
});
const __VLS_16 = {}.VColorPicker;
/** @type {[typeof __VLS_components.VColorPicker, ]} */ ;
// @ts-ignore
const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
    modelValue: (__VLS_ctx.editColor),
    label: "Color",
    allowClear: true,
    disabled: (__VLS_ctx.saving),
}));
const __VLS_18 = __VLS_17({
    modelValue: (__VLS_ctx.editColor),
    label: "Color",
    allowClear: true,
    disabled: (__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_17));
__VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
    ...{ class: "grid grid-cols-[max-content_1fr] gap-x-3 gap-y-0.5 self-start" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
    ...{ class: "opacity-60" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
    ...{ class: "font-mono break-all" },
});
(__VLS_ctx.document.path);
__VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
    ...{ class: "opacity-60" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
    ...{ class: "font-mono" },
});
(__VLS_ctx.document.kind ?? '—');
__VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
    ...{ class: "opacity-60" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
(__VLS_ctx.formatSize(__VLS_ctx.document.size));
__VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
    ...{ class: "opacity-60" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
(__VLS_ctx.formatDate(__VLS_ctx.document.createdAtMs));
__VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
    ...{ class: "opacity-60" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
(__VLS_ctx.document.createdBy ?? '—');
if (__VLS_ctx.headerEntries.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-60 mb-0.5" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
        ...{ class: "grid grid-cols-[max-content_1fr] gap-x-3 gap-y-0.5 bg-base-200 rounded px-2 py-1" },
    });
    for (const [[k, v]] of __VLS_getVForSourceType((__VLS_ctx.headerEntries))) {
        (k);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "font-mono opacity-70" },
        });
        (k);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
            ...{ class: "font-mono break-all whitespace-pre-wrap" },
        });
        (v);
    }
}
if (__VLS_ctx.document.summary) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-60 mb-0.5" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "bg-base-200 rounded px-2 py-1 whitespace-pre-wrap" },
    });
    (__VLS_ctx.document.summary);
}
if (__VLS_ctx.error) {
    const __VLS_20 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
        variant: "error",
        ...{ class: "mt-2" },
    }));
    const __VLS_22 = __VLS_21({
        variant: "error",
        ...{ class: "mt-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
    __VLS_23.slots.default;
    (__VLS_ctx.error);
    var __VLS_23;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mt-2 flex justify-end" },
});
const __VLS_24 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "primary",
    disabled: (!__VLS_ctx.isDirty),
    loading: (__VLS_ctx.saving),
}));
const __VLS_26 = __VLS_25({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "primary",
    disabled: (!__VLS_ctx.isDirty),
    loading: (__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_25));
let __VLS_28;
let __VLS_29;
let __VLS_30;
const __VLS_31 = {
    onClick: (__VLS_ctx.onSave)
};
__VLS_27.slots.default;
var __VLS_27;
/** @type {[typeof DocumentArchives, ]} */ ;
// @ts-ignore
const __VLS_32 = __VLS_asFunctionalComponent(DocumentArchives, new DocumentArchives({
    ...{ 'onRestored': {} },
    document: (__VLS_ctx.dtoForArchives),
}));
const __VLS_33 = __VLS_32({
    ...{ 'onRestored': {} },
    document: (__VLS_ctx.dtoForArchives),
}, ...__VLS_functionalComponentArgsRest(__VLS_32));
let __VLS_35;
let __VLS_36;
let __VLS_37;
const __VLS_38 = {
    onRestored: (__VLS_ctx.onRestored)
};
var __VLS_34;
/** @type {__VLS_StyleScopedClasses['properties-panel']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-1']} */ ;
/** @type {__VLS_StyleScopedClasses['md:grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-[max-content_1fr]']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['self-start']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['break-all']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-[max-content_1fr]']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['break-all']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VColorPicker: VColorPicker,
            VInput: VInput,
            VSelect: VSelect,
            DocumentArchives: DocumentArchives,
            editName: editName,
            editTitle: editTitle,
            editTags: editTags,
            editColor: editColor,
            editMime: editMime,
            saving: saving,
            error: error,
            mimeOptions: mimeOptions,
            isDirty: isDirty,
            headerEntries: headerEntries,
            formatSize: formatSize,
            formatDate: formatDate,
            onSave: onSave,
            dtoForArchives: dtoForArchives,
            onRestored: onRestored,
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
//# sourceMappingURL=DocumentPropertiesPanel.vue.js.map