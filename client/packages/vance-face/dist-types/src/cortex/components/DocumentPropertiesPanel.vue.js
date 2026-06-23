import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VColorPicker, VInput } from '@/components';
import DocumentArchives from '@/document/DocumentArchives.vue';
import { useCortexStore } from '../stores/cortexStore';
const props = defineProps();
const store = useCortexStore();
const editTitle = ref('');
const editTags = ref('');
const editColor = ref(null);
const saving = ref(false);
const error = ref(null);
// Seed editable fields whenever the document changes (tab switch or
// after a refresh). User edits in progress get overwritten — caller
// is expected to either save or close the panel before switching.
watch(() => props.document.id, () => {
    editTitle.value = props.document.title ?? '';
    editTags.value = (props.document.tags ?? []).join(', ');
    editColor.value = props.document.color ?? null;
    error.value = null;
}, { immediate: true });
const isDirty = computed(() => {
    const titleNow = props.document.title ?? '';
    const tagsNow = (props.document.tags ?? []).join(', ');
    const colorNow = props.document.color ?? null;
    return (editTitle.value !== titleNow
        || editTags.value !== tagsNow
        || editColor.value !== colorNow);
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
        await store.updateMeta(props.document.id, {
            title: editTitle.value.trim() || null,
            color: editColor.value,
            tags,
        });
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
    headers: {},
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
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "border-b border-base-300 bg-base-100 px-4 py-3 text-sm" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "grid grid-cols-1 md:grid-cols-2 gap-3" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-2" },
});
const __VLS_0 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    modelValue: (__VLS_ctx.editTitle),
    label: "Title",
    placeholder: "(no title)",
    disabled: (__VLS_ctx.saving),
}));
const __VLS_2 = __VLS_1({
    modelValue: (__VLS_ctx.editTitle),
    label: "Title",
    placeholder: "(no title)",
    disabled: (__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
const __VLS_4 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
    modelValue: (__VLS_ctx.editTags),
    label: "Tags",
    placeholder: "comma, separated",
    help: "Comma-separated",
    disabled: (__VLS_ctx.saving),
}));
const __VLS_6 = __VLS_5({
    modelValue: (__VLS_ctx.editTags),
    label: "Tags",
    placeholder: "comma, separated",
    help: "Comma-separated",
    disabled: (__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_5));
const __VLS_8 = {}.VColorPicker;
/** @type {[typeof __VLS_components.VColorPicker, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    modelValue: (__VLS_ctx.editColor),
    label: "Color",
    allowClear: true,
    disabled: (__VLS_ctx.saving),
}));
const __VLS_10 = __VLS_9({
    modelValue: (__VLS_ctx.editColor),
    label: "Color",
    allowClear: true,
    disabled: (__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
__VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
    ...{ class: "grid grid-cols-[max-content_1fr] gap-x-3 gap-y-1 text-xs" },
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
    ...{ class: "font-mono break-all" },
});
(__VLS_ctx.document.name);
__VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
    ...{ class: "opacity-60" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
    ...{ class: "font-mono" },
});
(__VLS_ctx.document.mimeType ?? '—');
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
if (__VLS_ctx.document.summary) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-3 text-xs" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-60 mb-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "bg-base-200 rounded px-2 py-1 whitespace-pre-wrap" },
    });
    (__VLS_ctx.document.summary);
}
if (__VLS_ctx.error) {
    const __VLS_12 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        variant: "error",
        ...{ class: "mt-3" },
    }));
    const __VLS_14 = __VLS_13({
        variant: "error",
        ...{ class: "mt-3" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    __VLS_15.slots.default;
    (__VLS_ctx.error);
    var __VLS_15;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mt-3 flex justify-end" },
});
const __VLS_16 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "primary",
    disabled: (!__VLS_ctx.isDirty),
    loading: (__VLS_ctx.saving),
}));
const __VLS_18 = __VLS_17({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "primary",
    disabled: (!__VLS_ctx.isDirty),
    loading: (__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_17));
let __VLS_20;
let __VLS_21;
let __VLS_22;
const __VLS_23 = {
    onClick: (__VLS_ctx.onSave)
};
__VLS_19.slots.default;
var __VLS_19;
/** @type {[typeof DocumentArchives, ]} */ ;
// @ts-ignore
const __VLS_24 = __VLS_asFunctionalComponent(DocumentArchives, new DocumentArchives({
    ...{ 'onRestored': {} },
    document: (__VLS_ctx.dtoForArchives),
}));
const __VLS_25 = __VLS_24({
    ...{ 'onRestored': {} },
    document: (__VLS_ctx.dtoForArchives),
}, ...__VLS_functionalComponentArgsRest(__VLS_24));
let __VLS_27;
let __VLS_28;
let __VLS_29;
const __VLS_30 = {
    onRestored: (__VLS_ctx.onRestored)
};
var __VLS_26;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-1']} */ ;
/** @type {__VLS_StyleScopedClasses['md:grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-[max-content_1fr]']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['break-all']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['break-all']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
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
            DocumentArchives: DocumentArchives,
            editTitle: editTitle,
            editTags: editTags,
            editColor: editColor,
            saving: saving,
            error: error,
            isDirty: isDirty,
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