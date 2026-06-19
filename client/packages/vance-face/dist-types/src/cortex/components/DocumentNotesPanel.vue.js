import { computed, nextTick, onUpdated, ref, watch } from 'vue';
import { VueDraggable } from 'vue-draggable-plus';
/**
 * Max textarea height for sticky notes. Beyond this the textarea
 * scrolls internally instead of growing further — keeps a few-page-long
 * note from pushing every other card off-screen.
 */
const TEXTAREA_MAX_PX = 320;
const props = withDefaults(defineProps(), {
    highlightedNoteId: null,
});
const emit = defineEmits();
/**
 * Mutable working copy of {@link Props.notes} — VueDraggable mutates
 * the bound array in place during drag. We resync from the parent on
 * every prop change so live-updates from other clients overwrite our
 * local mid-drag state cleanly.
 */
const draggable = ref([...props.notes]);
watch(() => props.notes, (next) => {
    draggable.value = [...next];
}, { deep: true });
function onDragEnd() {
    // Diff the final draggable order against props.notes to identify the
    // single note that moved. With single-item drag we expect exactly one
    // id at a different index — pick it and emit.
    for (let i = 0; i < draggable.value.length; i++) {
        const id = draggable.value[i].id;
        const oldIndex = props.notes.findIndex((n) => n.id === id);
        if (oldIndex !== i) {
            emit('reorder', id, i);
            return;
        }
    }
}
/** Per-note dirty buffer for inline-edit. Server is patched on blur. */
const draftText = ref({});
function startEdit(note) {
    if (draftText.value[note.id] === undefined) {
        draftText.value[note.id] = note.text;
    }
}
function commit(note) {
    const buf = draftText.value[note.id];
    if (buf === undefined)
        return;
    if (buf !== note.text) {
        emit('update', note.id, { text: buf });
    }
    // Keep the buffer so a subsequent re-focus doesn't lose mid-edit state
    // until the parent re-renders with the persisted value.
}
function toggleDone(note) {
    emit('update', note.id, { done: !note.done });
}
function relTime(ms) {
    if (!ms)
        return '';
    const now = Date.now();
    const delta = Math.max(0, now - ms);
    if (delta < 60_000)
        return 'jetzt';
    if (delta < 3_600_000)
        return `vor ${Math.floor(delta / 60_000)} min`;
    if (delta < 86_400_000)
        return `vor ${Math.floor(delta / 3_600_000)} h`;
    return new Date(ms).toLocaleDateString();
}
/** Scroll the highlighted note into view + brief pulse. */
const cardRefs = ref({});
function setCardRef(noteId, el) {
    cardRefs.value[noteId] = el;
}
/**
 * Auto-grow the textareas so the visible card height tracks the note's
 * content length. The text-area's {@code rows} attribute would only
 * grow the min-size; we drive {@code style.height} dynamically from
 * {@code scrollHeight}, capped at {@link TEXTAREA_MAX_PX} so very long
 * notes scroll internally instead of dominating the panel.
 */
const textareaRefs = ref({});
function setTextareaRef(noteId, el) {
    textareaRefs.value[noteId] = el;
    if (el)
        autoResize(el);
}
function autoResize(el) {
    el.style.height = 'auto';
    const target = Math.min(el.scrollHeight, TEXTAREA_MAX_PX);
    el.style.height = `${target}px`;
}
function onTextInput(note, e) {
    const ta = e.target;
    draftText.value[note.id] = ta.value;
    autoResize(ta);
}
// Resize after every render — covers external mutations from live
// events that update note.text without going through onTextInput.
onUpdated(() => {
    for (const id of Object.keys(textareaRefs.value)) {
        const el = textareaRefs.value[id];
        if (el)
            autoResize(el);
    }
});
watch(() => props.highlightedNoteId, (id) => {
    if (!id)
        return;
    void nextTick(() => {
        const el = cardRefs.value[id];
        if (el)
            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
});
const isEmpty = computed(() => props.notes.length === 0);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    highlightedNoteId: null,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['note-card--done']} */ ;
/** @type {__VLS_StyleScopedClasses['note-card-header']} */ ;
/** @type {__VLS_StyleScopedClasses['note-drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['note-drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['note-drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['note-drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['note-card-text']} */ ;
/** @type {__VLS_StyleScopedClasses['note-card-text']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
    ...{ class: "notes-panel" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
    ...{ class: "notes-panel-header" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "text-sm font-semibold" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "text-xs opacity-60" },
});
(__VLS_ctx.notes.length);
__VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
    ...{ class: "flex-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.emit('add');
        } },
    type: "button",
    ...{ class: "\u0074\u0065\u0078\u0074\u002d\u0078\u0073\u0020\u0070\u0078\u002d\u0032\u0020\u0070\u0079\u002d\u0030\u002e\u0035\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0062\u0061\u0073\u0065\u002d\u0033\u0030\u0030\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0068\u006f\u0076\u0065\u0072\u003a\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0032\u0030\u0030" },
    title: "Neue Notiz",
});
if (__VLS_ctx.isEmpty) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "notes-panel-empty" },
    });
}
else {
    const __VLS_0 = {}.VueDraggable;
    /** @type {[typeof __VLS_components.VueDraggable, typeof __VLS_components.VueDraggable, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ 'onEnd': {} },
        modelValue: (__VLS_ctx.draggable),
        ...{ class: "notes-panel-list" },
        animation: (150),
        handle: ".note-drag-handle",
        ghostClass: "note-card--ghost",
        chosenClass: "note-card--chosen",
        dragClass: "note-card--drag",
    }));
    const __VLS_2 = __VLS_1({
        ...{ 'onEnd': {} },
        modelValue: (__VLS_ctx.draggable),
        ...{ class: "notes-panel-list" },
        animation: (150),
        handle: ".note-drag-handle",
        ghostClass: "note-card--ghost",
        chosenClass: "note-card--chosen",
        dragClass: "note-card--drag",
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        onEnd: (__VLS_ctx.onDragEnd)
    };
    __VLS_3.slots.default;
    for (const [note] of __VLS_getVForSourceType((__VLS_ctx.draggable))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.article, __VLS_intrinsicElements.article)({
            key: (note.id),
            ref: ((el) => __VLS_ctx.setCardRef(note.id, el)),
            ...{ class: "note-card" },
            ...{ class: ({
                    'note-card--done': note.done,
                    'note-card--pulse': __VLS_ctx.highlightedNoteId === note.id,
                }) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
            ...{ class: "note-card-header note-drag-handle" },
            title: "Zum Verschieben ziehen",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
            ...{ onChange: (...[$event]) => {
                    if (!!(__VLS_ctx.isEmpty))
                        return;
                    __VLS_ctx.toggleDone(note);
                } },
            ...{ onMousedown: () => { } },
            type: "checkbox",
            checked: (note.done),
            title: (note.done ? 'Erledigt' : 'Als erledigt markieren'),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs font-semibold truncate" },
            title: (note.userId),
        });
        (note.userId);
        if (note.line != null) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.isEmpty))
                            return;
                        if (!(note.line != null))
                            return;
                        __VLS_ctx.emit('jump-to-line', note.line);
                    } },
                ...{ onMousedown: () => { } },
                type: "button",
                ...{ class: "\u0074\u0065\u0078\u0074\u002d\u0078\u0073\u0020\u0070\u0078\u002d\u0031\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0031\u0030\u0030\u002f\u0036\u0030\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0062\u0061\u0073\u0065\u002d\u0033\u0030\u0030\u002f\u0036\u0030\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0068\u006f\u0076\u0065\u0072\u003a\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0031\u0030\u0030" },
                title: (`Sprung zu Zeile ${note.line}`),
            });
            (note.line);
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
            ...{ class: "flex-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-[10px] opacity-60" },
            title: (new Date(note.updatedAtMs).toLocaleString()),
        });
        (__VLS_ctx.relTime(note.updatedAtMs));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.isEmpty))
                        return;
                    __VLS_ctx.emit('delete', note.id);
                } },
            ...{ onMousedown: () => { } },
            type: "button",
            ...{ class: "opacity-50 hover:opacity-100 hover:text-error" },
            title: "Löschen",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.textarea)({
            ...{ onFocus: (...[$event]) => {
                    if (!!(__VLS_ctx.isEmpty))
                        return;
                    __VLS_ctx.startEdit(note);
                } },
            ...{ onInput: ((e) => __VLS_ctx.onTextInput(note, e)) },
            ...{ onBlur: (...[$event]) => {
                    if (!!(__VLS_ctx.isEmpty))
                        return;
                    __VLS_ctx.commit(note);
                } },
            ...{ onMousedown: () => { } },
            value: (__VLS_ctx.draftText[note.id] ?? note.text),
            ref: ((el) => __VLS_ctx.setTextareaRef(note.id, el)),
            ...{ class: "note-card-text" },
            rows: "1",
        });
    }
    var __VLS_3;
}
/** @type {__VLS_StyleScopedClasses['notes-panel']} */ ;
/** @type {__VLS_StyleScopedClasses['notes-panel-header']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['notes-panel-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['notes-panel-list']} */ ;
/** @type {__VLS_StyleScopedClasses['note-card']} */ ;
/** @type {__VLS_StyleScopedClasses['note-card--done']} */ ;
/** @type {__VLS_StyleScopedClasses['note-card--pulse']} */ ;
/** @type {__VLS_StyleScopedClasses['note-card-header']} */ ;
/** @type {__VLS_StyleScopedClasses['note-drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100/60']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300/60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['note-card-text']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VueDraggable: VueDraggable,
            emit: emit,
            draggable: draggable,
            onDragEnd: onDragEnd,
            draftText: draftText,
            startEdit: startEdit,
            commit: commit,
            toggleDone: toggleDone,
            relTime: relTime,
            setCardRef: setCardRef,
            setTextareaRef: setTextareaRef,
            onTextInput: onTextInput,
            isEmpty: isEmpty,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=DocumentNotesPanel.vue.js.map