import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VCheckbox, VInput, VSelect, VTagEditor, VTextarea, } from '@/components';
const props = defineProps();
const emit = defineEmits();
const title = ref(props.event.title);
const start = ref(props.event.start);
const end = ref(props.event.end ?? '');
const allDay = ref(props.event.allDay);
const location = ref(props.event.location ?? '');
const attendees = ref([...props.event.attendees]);
const recurrence = ref(props.event.recurrence ?? '');
const tags = ref([...props.event.tags]);
const notes = ref(props.event.notes ?? '');
const targetLane = ref(props.event.lane);
watch(() => props.event.id, () => {
    title.value = props.event.title;
    start.value = props.event.start;
    end.value = props.event.end ?? '';
    allDay.value = props.event.allDay;
    location.value = props.event.location ?? '';
    attendees.value = [...props.event.attendees];
    recurrence.value = props.event.recurrence ?? '';
    tags.value = [...props.event.tags];
    notes.value = props.event.notes ?? '';
    targetLane.value = props.event.lane;
});
const dirty = computed(() => title.value !== props.event.title ||
    start.value !== props.event.start ||
    end.value !== (props.event.end ?? '') ||
    allDay.value !== props.event.allDay ||
    location.value !== (props.event.location ?? '') ||
    !arraysEqual(attendees.value, props.event.attendees) ||
    recurrence.value !== (props.event.recurrence ?? '') ||
    !arraysEqual(tags.value, props.event.tags) ||
    notes.value !== (props.event.notes ?? '') ||
    targetLane.value !== props.event.lane);
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
    if (title.value !== props.event.title)
        patch.title = title.value;
    if (start.value !== props.event.start)
        patch.start = start.value;
    if (end.value !== (props.event.end ?? ''))
        patch.end = end.value;
    if (allDay.value !== props.event.allDay)
        patch.allDay = allDay.value;
    if (location.value !== (props.event.location ?? ''))
        patch.location = location.value;
    if (!arraysEqual(attendees.value, props.event.attendees))
        patch.attendees = attendees.value;
    if (recurrence.value !== (props.event.recurrence ?? ''))
        patch.recurrence = recurrence.value;
    if (!arraysEqual(tags.value, props.event.tags))
        patch.tags = tags.value;
    if (notes.value !== (props.event.notes ?? ''))
        patch.notes = notes.value;
    if (targetLane.value !== props.event.lane)
        patch.targetLane = targetLane.value;
    emit('update', patch);
}
function confirmDelete() {
    if (window.confirm(`Delete event "${props.event.title}"?`))
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
const __VLS_4 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
    modelValue: (__VLS_ctx.start),
    label: "Start",
    placeholder: "YYYY-MM-DD[THH:mm]",
}));
const __VLS_6 = __VLS_5({
    modelValue: (__VLS_ctx.start),
    label: "Start",
    placeholder: "YYYY-MM-DD[THH:mm]",
}, ...__VLS_functionalComponentArgsRest(__VLS_5));
const __VLS_8 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    modelValue: (__VLS_ctx.end),
    label: "End",
    placeholder: "(optional)",
}));
const __VLS_10 = __VLS_9({
    modelValue: (__VLS_ctx.end),
    label: "End",
    placeholder: "(optional)",
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
const __VLS_12 = {}.VCheckbox;
/** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
// @ts-ignore
const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
    modelValue: (__VLS_ctx.allDay),
    label: "All-day event",
}));
const __VLS_14 = __VLS_13({
    modelValue: (__VLS_ctx.allDay),
    label: "All-day event",
}, ...__VLS_functionalComponentArgsRest(__VLS_13));
const __VLS_16 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.targetLane),
    label: "Lane",
    options: (__VLS_ctx.lanes.map((l) => ({ value: l.name, label: l.title ?? l.name }))),
}));
const __VLS_18 = __VLS_17({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.targetLane),
    label: "Lane",
    options: (__VLS_ctx.lanes.map((l) => ({ value: l.name, label: l.title ?? l.name }))),
}, ...__VLS_functionalComponentArgsRest(__VLS_17));
let __VLS_20;
let __VLS_21;
let __VLS_22;
const __VLS_23 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.targetLane = v ?? props.event.lane)
};
var __VLS_19;
const __VLS_24 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
    modelValue: (__VLS_ctx.location),
    label: "Location",
}));
const __VLS_26 = __VLS_25({
    modelValue: (__VLS_ctx.location),
    label: "Location",
}, ...__VLS_functionalComponentArgsRest(__VLS_25));
const __VLS_28 = {}.VTagEditor;
/** @type {[typeof __VLS_components.VTagEditor, ]} */ ;
// @ts-ignore
const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
    modelValue: (__VLS_ctx.attendees),
    label: "Attendees",
}));
const __VLS_30 = __VLS_29({
    modelValue: (__VLS_ctx.attendees),
    label: "Attendees",
}, ...__VLS_functionalComponentArgsRest(__VLS_29));
const __VLS_32 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
    modelValue: (__VLS_ctx.recurrence),
    label: "Recurrence (RRULE)",
    placeholder: "FREQ=WEEKLY;BYDAY=MO,…",
}));
const __VLS_34 = __VLS_33({
    modelValue: (__VLS_ctx.recurrence),
    label: "Recurrence (RRULE)",
    placeholder: "FREQ=WEEKLY;BYDAY=MO,…",
}, ...__VLS_functionalComponentArgsRest(__VLS_33));
const __VLS_36 = {}.VTagEditor;
/** @type {[typeof __VLS_components.VTagEditor, ]} */ ;
// @ts-ignore
const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
    modelValue: (__VLS_ctx.tags),
    label: "Tags",
}));
const __VLS_38 = __VLS_37({
    modelValue: (__VLS_ctx.tags),
    label: "Tags",
}, ...__VLS_functionalComponentArgsRest(__VLS_37));
const __VLS_40 = {}.VTextarea;
/** @type {[typeof __VLS_components.VTextarea, ]} */ ;
// @ts-ignore
const __VLS_41 = __VLS_asFunctionalComponent(__VLS_40, new __VLS_40({
    modelValue: (__VLS_ctx.notes),
    label: "Notes",
    rows: (4),
}));
const __VLS_42 = __VLS_41({
    modelValue: (__VLS_ctx.notes),
    label: "Notes",
    rows: (4),
}, ...__VLS_functionalComponentArgsRest(__VLS_41));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2 mt-2" },
});
if (__VLS_ctx.event.googleUrl) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        href: (__VLS_ctx.event.googleUrl),
        target: "_blank",
        rel: "noopener",
        ...{ class: "flex-1 text-center text-sm bg-base-200 hover:bg-base-300 rounded px-3 py-2" },
    });
}
if (__VLS_ctx.event.outlookUrl) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        href: (__VLS_ctx.event.outlookUrl),
        target: "_blank",
        rel: "noopener",
        ...{ class: "flex-1 text-center text-sm bg-base-200 hover:bg-base-300 rounded px-3 py-2" },
    });
}
const __VLS_44 = {}.VAlert;
/** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
// @ts-ignore
const __VLS_45 = __VLS_asFunctionalComponent(__VLS_44, new __VLS_44({
    variant: "info",
    ...{ class: "text-xs" },
}));
const __VLS_46 = __VLS_45({
    variant: "info",
    ...{ class: "text-xs" },
}, ...__VLS_functionalComponentArgsRest(__VLS_45));
__VLS_47.slots.default;
(__VLS_ctx.event.sourcePath);
var __VLS_47;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center justify-between p-4 border-t border-base-300" },
});
const __VLS_48 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_49 = __VLS_asFunctionalComponent(__VLS_48, new __VLS_48({
    ...{ 'onClick': {} },
    variant: "ghost",
    ...{ class: "text-error" },
}));
const __VLS_50 = __VLS_49({
    ...{ 'onClick': {} },
    variant: "ghost",
    ...{ class: "text-error" },
}, ...__VLS_functionalComponentArgsRest(__VLS_49));
let __VLS_52;
let __VLS_53;
let __VLS_54;
const __VLS_55 = {
    onClick: (__VLS_ctx.confirmDelete)
};
__VLS_51.slots.default;
var __VLS_51;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2" },
});
const __VLS_56 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_57 = __VLS_asFunctionalComponent(__VLS_56, new __VLS_56({
    ...{ 'onClick': {} },
    variant: "ghost",
    disabled: (!__VLS_ctx.dirty),
}));
const __VLS_58 = __VLS_57({
    ...{ 'onClick': {} },
    variant: "ghost",
    disabled: (!__VLS_ctx.dirty),
}, ...__VLS_functionalComponentArgsRest(__VLS_57));
let __VLS_60;
let __VLS_61;
let __VLS_62;
const __VLS_63 = {
    onClick: (...[$event]) => {
        __VLS_ctx.emit('close');
    }
};
__VLS_59.slots.default;
var __VLS_59;
const __VLS_64 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.dirty),
}));
const __VLS_66 = __VLS_65({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.dirty),
}, ...__VLS_functionalComponentArgsRest(__VLS_65));
let __VLS_68;
let __VLS_69;
let __VLS_70;
const __VLS_71 = {
    onClick: (__VLS_ctx.save)
};
__VLS_67.slots.default;
var __VLS_67;
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
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
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
            VAlert: VAlert,
            VButton: VButton,
            VCheckbox: VCheckbox,
            VInput: VInput,
            VSelect: VSelect,
            VTagEditor: VTagEditor,
            VTextarea: VTextarea,
            emit: emit,
            title: title,
            start: start,
            end: end,
            allDay: allDay,
            location: location,
            attendees: attendees,
            recurrence: recurrence,
            tags: tags,
            notes: notes,
            targetLane: targetLane,
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
//# sourceMappingURL=CalendarEventDetail.vue.js.map