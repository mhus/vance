import { computed, ref, watch } from 'vue';
import { CodeEditor, VAlert, VButton, VCheckbox, VInput, VSelect, VTagEditor, } from '@/components';
const props = defineProps();
const emit = defineEmits();
// Local edit state — staged until the user clicks Save. Replaying the
// card's fields into refs makes the form straightforward to wire and
// keeps unsaved edits visible across re-renders of the same card.
const title = ref(props.card.title);
const priority = ref(props.card.priority ?? '');
const assignee = ref(props.card.assignee ?? '');
const labels = ref([...props.card.labels]);
const dueDate = ref(props.card.dueDate ?? '');
const estimate = ref(props.card.estimate ?? null);
const blocked = ref(props.card.blocked);
const body = ref(props.card.body ?? '');
watch(() => props.card.path, () => {
    title.value = props.card.title;
    priority.value = props.card.priority ?? '';
    assignee.value = props.card.assignee ?? '';
    labels.value = [...props.card.labels];
    dueDate.value = props.card.dueDate ?? '';
    estimate.value = props.card.estimate ?? null;
    blocked.value = props.card.blocked;
    body.value = props.card.body ?? '';
});
const dirty = computed(() => title.value !== props.card.title ||
    (priority.value || null) !== (props.card.priority ?? null) ||
    (assignee.value || null) !== (props.card.assignee ?? null) ||
    !arraysEqual(labels.value, props.card.labels) ||
    (dueDate.value || null) !== (props.card.dueDate ?? null) ||
    estimate.value !== (props.card.estimate ?? null) ||
    blocked.value !== props.card.blocked ||
    body.value !== (props.card.body ?? ''));
function arraysEqual(a, b) {
    if (a.length !== b.length)
        return false;
    for (let i = 0; i < a.length; i++)
        if (a[i] !== b[i])
            return false;
    return true;
}
function save() {
    const patch = {};
    if (title.value !== props.card.title)
        patch.title = title.value;
    if (priority.value !== (props.card.priority ?? ''))
        patch.priority = priority.value;
    if (assignee.value !== (props.card.assignee ?? ''))
        patch.assignee = assignee.value;
    if (!arraysEqual(labels.value, props.card.labels))
        patch.labels = labels.value;
    if (dueDate.value !== (props.card.dueDate ?? ''))
        patch.dueDate = dueDate.value;
    if (estimate.value !== (props.card.estimate ?? null)) {
        if (estimate.value !== null)
            patch.estimate = estimate.value;
    }
    if (blocked.value !== props.card.blocked)
        patch.blocked = blocked.value;
    if (body.value !== (props.card.body ?? ''))
        patch.body = body.value;
    emit('update', patch);
}
function confirmDelete() {
    if (window.confirm(`Delete card "${props.card.title}"?`))
        emit('delete');
}
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
__VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
    ...{ class: "text-lg font-semibold" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.emit('close');
        } },
    ...{ class: "text-base-content/60 hover:text-base-content text-xl leading-none" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 overflow-y-auto p-4 flex flex-col gap-3" },
});
const __VLS_0 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    modelValue: (__VLS_ctx.title),
    label: "Title",
}));
const __VLS_2 = __VLS_1({
    modelValue: (__VLS_ctx.title),
    label: "Title",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "grid grid-cols-2 gap-2" },
});
const __VLS_4 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.priority),
    label: "Priority",
    options: ([
        { value: '', label: 'No priority' },
        { value: 'low', label: 'Low' },
        { value: 'med', label: 'Medium' },
        { value: 'high', label: 'High' },
        { value: 'critical', label: 'Critical' },
    ]),
}));
const __VLS_6 = __VLS_5({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.priority),
    label: "Priority",
    options: ([
        { value: '', label: 'No priority' },
        { value: 'low', label: 'Low' },
        { value: 'med', label: 'Medium' },
        { value: 'high', label: 'High' },
        { value: 'critical', label: 'Critical' },
    ]),
}, ...__VLS_functionalComponentArgsRest(__VLS_5));
let __VLS_8;
let __VLS_9;
let __VLS_10;
const __VLS_11 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.priority = v ?? '')
};
var __VLS_7;
const __VLS_12 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
    modelValue: (__VLS_ctx.assignee),
    label: "Assignee",
}));
const __VLS_14 = __VLS_13({
    modelValue: (__VLS_ctx.assignee),
    label: "Assignee",
}, ...__VLS_functionalComponentArgsRest(__VLS_13));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "grid grid-cols-2 gap-2" },
});
const __VLS_16 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
    modelValue: (__VLS_ctx.dueDate),
    label: "Due date",
    placeholder: "YYYY-MM-DD",
}));
const __VLS_18 = __VLS_17({
    modelValue: (__VLS_ctx.dueDate),
    label: "Due date",
    placeholder: "YYYY-MM-DD",
}, ...__VLS_functionalComponentArgsRest(__VLS_17));
const __VLS_20 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.estimate === null ? '' : String(__VLS_ctx.estimate)),
    label: "Estimate",
}));
const __VLS_22 = __VLS_21({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.estimate === null ? '' : String(__VLS_ctx.estimate)),
    label: "Estimate",
}, ...__VLS_functionalComponentArgsRest(__VLS_21));
let __VLS_24;
let __VLS_25;
let __VLS_26;
const __VLS_27 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.estimate = v === '' ? null : Number(v))
};
var __VLS_23;
const __VLS_28 = {}.VTagEditor;
/** @type {[typeof __VLS_components.VTagEditor, ]} */ ;
// @ts-ignore
const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
    modelValue: (__VLS_ctx.labels),
    label: "Labels",
}));
const __VLS_30 = __VLS_29({
    modelValue: (__VLS_ctx.labels),
    label: "Labels",
}, ...__VLS_functionalComponentArgsRest(__VLS_29));
const __VLS_32 = {}.VCheckbox;
/** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
// @ts-ignore
const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
    modelValue: (__VLS_ctx.blocked),
    label: "Blocked",
}));
const __VLS_34 = __VLS_33({
    modelValue: (__VLS_ctx.blocked),
    label: "Blocked",
}, ...__VLS_functionalComponentArgsRest(__VLS_33));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
    ...{ class: "text-sm font-medium" },
});
const __VLS_36 = {}.CodeEditor;
/** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
// @ts-ignore
const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
    modelValue: (__VLS_ctx.body),
    mimeType: "text/markdown",
    rows: (14),
}));
const __VLS_38 = __VLS_37({
    modelValue: (__VLS_ctx.body),
    mimeType: "text/markdown",
    rows: (14),
}, ...__VLS_functionalComponentArgsRest(__VLS_37));
if (__VLS_ctx.body) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs text-base-content/50" },
    });
}
const __VLS_40 = {}.VAlert;
/** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
// @ts-ignore
const __VLS_41 = __VLS_asFunctionalComponent(__VLS_40, new __VLS_40({
    variant: "info",
    ...{ class: "text-xs" },
}));
const __VLS_42 = __VLS_41({
    variant: "info",
    ...{ class: "text-xs" },
}, ...__VLS_functionalComponentArgsRest(__VLS_41));
__VLS_43.slots.default;
(__VLS_ctx.card.path);
var __VLS_43;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center justify-between p-4 border-t border-base-300" },
});
const __VLS_44 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_45 = __VLS_asFunctionalComponent(__VLS_44, new __VLS_44({
    ...{ 'onClick': {} },
    variant: "ghost",
    ...{ class: "text-error" },
}));
const __VLS_46 = __VLS_45({
    ...{ 'onClick': {} },
    variant: "ghost",
    ...{ class: "text-error" },
}, ...__VLS_functionalComponentArgsRest(__VLS_45));
let __VLS_48;
let __VLS_49;
let __VLS_50;
const __VLS_51 = {
    onClick: (__VLS_ctx.confirmDelete)
};
__VLS_47.slots.default;
var __VLS_47;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2" },
});
const __VLS_52 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
    ...{ 'onClick': {} },
    variant: "ghost",
    disabled: (!__VLS_ctx.dirty),
}));
const __VLS_54 = __VLS_53({
    ...{ 'onClick': {} },
    variant: "ghost",
    disabled: (!__VLS_ctx.dirty),
}, ...__VLS_functionalComponentArgsRest(__VLS_53));
let __VLS_56;
let __VLS_57;
let __VLS_58;
const __VLS_59 = {
    onClick: (...[$event]) => {
        __VLS_ctx.emit('close');
    }
};
__VLS_55.slots.default;
var __VLS_55;
const __VLS_60 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.dirty),
}));
const __VLS_62 = __VLS_61({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.dirty),
}, ...__VLS_functionalComponentArgsRest(__VLS_61));
let __VLS_64;
let __VLS_65;
let __VLS_66;
const __VLS_67 = {
    onClick: (__VLS_ctx.save)
};
__VLS_63.slots.default;
var __VLS_63;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:text-base-content']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-none']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            CodeEditor: CodeEditor,
            VAlert: VAlert,
            VButton: VButton,
            VCheckbox: VCheckbox,
            VInput: VInput,
            VSelect: VSelect,
            VTagEditor: VTagEditor,
            emit: emit,
            title: title,
            priority: priority,
            assignee: assignee,
            labels: labels,
            dueDate: dueDate,
            estimate: estimate,
            blocked: blocked,
            body: body,
            dirty: dirty,
            save: save,
            confirmDelete: confirmDelete,
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
//# sourceMappingURL=KanbanCardDetail.vue.js.map