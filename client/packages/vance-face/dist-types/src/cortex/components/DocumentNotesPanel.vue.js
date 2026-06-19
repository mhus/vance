import { computed, nextTick, ref, watch } from 'vue';
const props = withDefaults(defineProps(), {
    highlightedNoteId: null,
});
const emit = defineEmits();
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
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "notes-panel-list" },
    });
    for (const [note] of __VLS_getVForSourceType((__VLS_ctx.notes))) {
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
            ...{ class: "note-card-header" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
            ...{ onChange: (...[$event]) => {
                    if (!!(__VLS_ctx.isEmpty))
                        return;
                    __VLS_ctx.toggleDone(note);
                } },
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
            ...{ onInput: ((e) => (__VLS_ctx.draftText[note.id] = e.target.value)) },
            ...{ onBlur: (...[$event]) => {
                    if (!!(__VLS_ctx.isEmpty))
                        return;
                    __VLS_ctx.commit(note);
                } },
            value: (__VLS_ctx.draftText[note.id] ?? note.text),
            ...{ class: "note-card-text" },
            rows: "2",
        });
    }
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
            emit: emit,
            draftText: draftText,
            startEdit: startEdit,
            commit: commit,
            toggleDone: toggleDone,
            relTime: relTime,
            setCardRef: setCardRef,
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