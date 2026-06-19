import { computed, onBeforeUnmount, ref, shallowRef, toRef, watch } from 'vue';
import { CodeEditor, MarkdownView } from '@/components';
import ImageView from '@/document/ImageView.vue';
import DocumentPreview from '@/document/DocumentPreview.vue';
import { useCortexStore } from '../stores/cortexStore';
import { resolveBinding } from '../docTypeRegistry';
import { resolveRunAdapter } from '../runners/runnerRegistry';
import { useDocumentNotes } from '../composables/useDocumentNotes';
import CortexValidateDialog from './CortexValidateDialog.vue';
import CortexHactarDialog from './CortexHactarDialog.vue';
import DocumentPropertiesPanel from './DocumentPropertiesPanel.vue';
import DocumentNotesPanel from './DocumentNotesPanel.vue';
const props = defineProps();
const emit = defineEmits();
const store = useCortexStore();
const binding = computed(() => resolveBinding(props.document));
const reloading = ref(false);
async function onReload() {
    if (reloading.value)
        return;
    if (props.document.dirty) {
        const ok = window.confirm(`Discard unsaved changes in ${props.document.path}?`);
        if (!ok)
            return;
    }
    reloading.value = true;
    try {
        await store.reloadTab(props.document.id);
    }
    catch (e) {
        console.warn('Failed to reload document', e);
    }
    finally {
        reloading.value = false;
    }
}
// Properties panel state — persisted per browser tab in sessionStorage
// so the user's preference survives doc switches inside the same
// session, but doesn't bleed across browser tabs / restarts.
const PROPS_OPEN_KEY = 'editor:propertiesOpen';
function loadPropsOpen() {
    try {
        return sessionStorage.getItem(PROPS_OPEN_KEY) === '1';
    }
    catch {
        return false;
    }
}
const propertiesOpen = ref(loadPropsOpen());
watch(propertiesOpen, (v) => {
    try {
        sessionStorage.setItem(PROPS_OPEN_KEY, v ? '1' : '0');
    }
    catch { /* sessionStorage unavailable */ }
});
// Notes panel state — same sessionStorage pattern as propertiesOpen.
// Open per-browser-tab default; survives doc-switches within the same
// editor session but doesn't bleed across browser tabs.
const NOTES_OPEN_KEY = 'editor:notesOpen';
function loadNotesOpen() {
    try {
        return sessionStorage.getItem(NOTES_OPEN_KEY) === '1';
    }
    catch {
        return false;
    }
}
const notesOpen = ref(loadNotesOpen());
watch(notesOpen, (v) => {
    try {
        sessionStorage.setItem(NOTES_OPEN_KEY, v ? '1' : '0');
    }
    catch { /* sessionStorage unavailable */ }
});
// Sticky-notes composable — wired to this tab's document. Mutates the
// document's embedded `notes` map directly (it's the same reactive
// object the store owns), so the editor's gutter dots and the panel
// list update without a parent refetch.
const documentRef = toRef(props, 'document');
const docNotes = useDocumentNotes(documentRef);
/**
 * Highlighted note in the panel — set by a click on a gutter anchor in
 * the editor. The panel watches this and scroll-into-view + pulses the
 * matching card briefly.
 */
const highlightedNoteId = ref(null);
async function onAddUnanchoredNote() {
    if (!notesOpen.value)
        notesOpen.value = true;
    const created = await docNotes.addNote('', null);
    if (created)
        highlightedNoteId.value = created.id;
}
async function onUpdateNote(noteId, patch) {
    await docNotes.updateNote(noteId, patch);
}
async function onDeleteNote(noteId) {
    await docNotes.deleteNote(noteId);
    if (highlightedNoteId.value === noteId)
        highlightedNoteId.value = null;
}
function onJumpToLine(_line) {
    // Editor scroll-to-line is a v2 — for now the gutter dot is visible
    // anyway when the user opens the panel. Hook left intentionally
    // empty until CodeEditor gains a programmatic-scroll API.
}
/** Editor → panel: clicked the dot at this line. */
function onNoteAnchorClick(line) {
    const note = docNotes.noteAtLine(line);
    if (!note)
        return;
    if (!notesOpen.value)
        notesOpen.value = true;
    highlightedNoteId.value = note.id;
}
/** Editor → panel: clicked the empty gutter at this line. Create new note. */
async function onNoteGutterClick(line) {
    if (!notesOpen.value)
        notesOpen.value = true;
    const created = await docNotes.addNote('', line);
    if (created)
        highlightedNoteId.value = created.id;
}
// Derive a language hint for CodeEditor. Path extension wins over the
// server-supplied mime: a file named {@code foo.js} should highlight
// as JS whether the server returns {@code text/javascript},
// {@code application/octet-stream} or nothing at all. Server mime is
// only consulted when the extension doesn't pin a language.
const effectiveMimeType = computed(() => {
    const lower = props.document.path.toLowerCase();
    if (lower.endsWith('.md') || lower.endsWith('.markdown'))
        return 'text/markdown';
    if (lower.endsWith('.json'))
        return 'application/json';
    if (lower.endsWith('.yaml') || lower.endsWith('.yml'))
        return 'application/yaml';
    if (lower.endsWith('.js')
        || lower.endsWith('.mjs')
        || lower.endsWith('.mjsh')
        || lower.endsWith('.cjs'))
        return 'text/javascript';
    if (lower.endsWith('.ts') || lower.endsWith('.tsx'))
        return 'text/typescript';
    if (lower.endsWith('.py'))
        return 'text/x-python';
    if (lower.endsWith('.sh') || lower.endsWith('.bash'))
        return 'text/x-shellscript';
    if (lower.endsWith('.r'))
        return 'text/x-r';
    if (lower.endsWith('.java'))
        return 'text/x-java';
    if (lower.endsWith('.html') || lower.endsWith('.htm'))
        return 'text/html';
    if (lower.endsWith('.css'))
        return 'text/css';
    if (lower.endsWith('.xml'))
        return 'application/xml';
    if (lower.endsWith('.sql'))
        return 'application/sql';
    const explicit = props.document.mimeType;
    return explicit && explicit.trim() ? explicit : 'text/plain';
});
function onSelectionChanged(sel) {
    if (sel.from === sel.to || !sel.text) {
        store.clearSelection();
        return;
    }
    store.setSelection({
        docId: props.document.id,
        docPath: props.document.path,
        from: sel.from,
        to: sel.to,
        text: sel.text,
    });
}
onBeforeUnmount(() => {
    store.clearSelection();
});
// ImageView and the addon kind views consume a DocumentDto. Build a
// partial DTO from the trimmed CortexDocument shape — required-but-
// unused fields get inert defaults that don't affect rendering.
const docDtoForView = computed(() => ({
    id: props.document.id,
    projectId: store.projectId ?? '',
    path: props.document.path,
    name: props.document.name,
    title: props.document.title ?? undefined,
    mimeType: props.document.mimeType ?? undefined,
    size: 0,
    tags: [],
    inline: !!props.document.inlineText,
    inlineText: props.document.inlineText || undefined,
    kind: props.document.kind ?? undefined,
    headers: {},
    autoSummary: false,
    summaryDirty: false,
    notes: props.document.notes ?? {},
}));
const codecPair = computed(() => {
    const b = binding.value;
    if (b.mode === 'typed-model' && b.codec) {
        return {
            parse: b.codec.parse,
            serialize: b.codec.serialize,
        };
    }
    if (b.mode === 'kind-registry' && b.kindEntry) {
        return {
            parse: b.kindEntry.parse,
            serialize: b.kindEntry.serialize,
            isParseError: b.kindEntry.isParseError,
        };
    }
    return null;
});
const parseResult = computed(() => {
    const pair = codecPair.value;
    if (!pair?.parse) {
        return { model: null, error: null };
    }
    const mime = props.document.mimeType ?? '';
    try {
        return { model: pair.parse(props.document.inlineText, mime), error: null };
    }
    catch (e) {
        // KindEntry.isParseError lets the view distinguish its own codec
        // errors (show the banner) from unrelated bugs (rethrow). Hand-
        // rolled bindings don't provide one — treat every throw as parse.
        const isParseErr = pair.isParseError ? pair.isParseError(e) : true;
        if (!isParseErr)
            throw e;
        const msg = e instanceof Error ? e.message : String(e);
        return { model: null, error: msg };
    }
});
function onModelUpdate(model) {
    const pair = codecPair.value;
    if (!pair?.serialize)
        return; // read-only view (e.g. mindmap render)
    const mime = props.document.mimeType ?? '';
    let text;
    try {
        text = pair.serialize(model, mime);
    }
    catch (e) {
        console.warn('Codec serialize failed; dropping update', e);
        return;
    }
    if (text !== props.document.inlineText) {
        emit('update', text);
    }
}
const activeView = computed(() => {
    const b = binding.value;
    if (b.mode === 'typed-model')
        return b.view;
    if (b.mode === 'kind-registry')
        return b.kindEntry?.editor ?? b.kindEntry?.view;
    return undefined;
});
// What gets passed to the view: a parsed model when there's a parser,
// otherwise the DocumentDto so display-only views (PDF-like addons)
// can fetch their own bytes.
const viewBindings = computed(() => {
    if (codecPair.value?.parse) {
        return { doc: parseResult.value.model };
    }
    return { document: docDtoForView.value };
});
const isViewMode = computed(() => binding.value.mode === 'typed-model' || binding.value.mode === 'kind-registry');
// Markdown gets the same view/edit toggle as the kind-registry modes:
// rendered MarkdownView in 'view', raw CodeEditor in 'edit'. The
// catch-all 'code' binding resolves these documents (no dedicated
// Markdown binding in the registry) so the toggle gates inside the
// 'code' branch below rather than the typed-model / kind-registry
// template.
const isMarkdownDocument = computed(() => {
    if (binding.value.mode !== 'code')
        return false;
    const lower = props.document.path.toLowerCase();
    if (lower.endsWith('.md') || lower.endsWith('.markdown'))
        return true;
    return (props.document.mimeType ?? '').toLowerCase().startsWith('text/markdown');
});
const showToggle = computed(() => isViewMode.value || isMarkdownDocument.value);
const viewEditMode = ref('view');
watch(() => props.document.id, () => {
    viewEditMode.value = 'view';
});
// In a view-capable mode, 'edit' falls back to the same CodeEditor
// the catch-all 'code' mode uses — same selection-tracking, same
// keyboard model.
const showRawEditor = computed(() => isViewMode.value && viewEditMode.value === 'edit');
// ─── Run adapter (orthogonal capability) ─────────────────────────
//
// resolveRunAdapter is independent of the doc-type binding — a JS
// file might be 'code' mode (catch-all), a Python file might one day
// be a typed-model view, both can be runnable. The shell composes
// view + run UI.
const runAdapter = computed(() => resolveRunAdapter(props.document));
// shallowRef preserves the RunHandle's internal Ref<T> shape — the
// template + script access {@code .state.value} / {@code .logLines.value}
// directly, no deep-unwrap surprises.
const runHandle = shallowRef(null);
const argsJson = ref('{}');
const argsError = ref(null);
const runStarting = ref(false);
const runState = computed(() => runHandle.value?.state.value ?? 'idle');
const isRunning = computed(() => runStarting.value
    || runState.value === 'starting'
    || runState.value === 'running');
async function onRun() {
    if (!runAdapter.value || !store.projectId)
        return;
    if (isRunning.value)
        return;
    // Parse args before starting so a typo doesn't kick off a no-op
    // execution. {} is the implicit default for an empty input.
    let parsedArgs = {};
    argsError.value = null;
    const raw = argsJson.value.trim();
    if (raw && raw !== '{}') {
        try {
            const v = JSON.parse(raw);
            if (v === null || typeof v !== 'object' || Array.isArray(v)) {
                throw new Error('args must be a JSON object');
            }
            parsedArgs = v;
        }
        catch (e) {
            argsError.value = e instanceof Error ? e.message : 'Invalid JSON';
            return;
        }
    }
    // Detach the previous handle to free its WS / poll listeners
    // before allocating a new one for the same tab.
    if (runHandle.value) {
        runHandle.value.detach();
        runHandle.value = null;
    }
    runStarting.value = true;
    try {
        // Backend's scripts/execute loads the document body server-side
        // via scriptId — if our local buffer has uncommitted edits the
        // 2s auto-save debounce wouldn't have flushed yet, so the run
        // would silently execute the previous version. Flush now to
        // guarantee server sees what the user just typed.
        if (props.document.dirty) {
            await store.saveTab(props.document.id);
        }
        const handle = await runAdapter.value.execute({
            doc: props.document,
            projectId: store.projectId,
            args: parsedArgs,
        });
        runHandle.value = handle;
    }
    catch (e) {
        argsError.value = e instanceof Error ? e.message : 'Run failed';
    }
    finally {
        runStarting.value = false;
    }
}
async function onCancel() {
    if (!runHandle.value)
        return;
    await runHandle.value.cancel();
}
function onCloseLogPanel() {
    if (runHandle.value) {
        // Terminal-state handle: detach is fine. Mid-run: detach also
        // OK — we keep the backend execution running, just stop
        // listening. The user can navigate back via the next Run.
        runHandle.value.detach();
        runHandle.value = null;
    }
}
// Tab switch: drop the previous tab's handle so its WS listeners
// don't leak. The new tab starts with no handle until the user hits
// Run.
watch(() => props.document.id, () => {
    if (runHandle.value) {
        runHandle.value.detach();
        runHandle.value = null;
    }
    argsJson.value = '{}';
    argsError.value = null;
});
// Final cleanup when the shell unmounts (Cortex closed).
onBeforeUnmount(() => {
    if (runHandle.value) {
        runHandle.value.detach();
        runHandle.value = null;
    }
});
// ─── Validate + Hactar dialogs (JS-only for V1) ─────────────────
//
// Both gated by {@code runAdapter.id === 'js'} — Python / Shell
// runners later get their own (or none); the per-language gating
// keeps the toolbar from showing buttons whose endpoint would 404.
const showValidate = ref(false);
const showSlart = ref(false);
/** Mode the script-generate dialog opens in. {@code 'CREATE'} blanks
 *  the editor context; {@code 'UPDATE'} includes the current body
 *  and (optionally) the prior run-failure reason from the run panel. */
const slartMode = ref('CREATE');
const isJsLanguage = computed(() => runAdapter.value?.id === 'js');
/** Heuristic: editor is "empty" when there is no content yet (new
 *  doc) or only whitespace. Drives which of the two architect
 *  buttons (Generate vs. Update) is shown. */
const editorHasContent = computed(() => (props.document.inlineText ?? '').trim().length > 0);
function onSlartApply(code) {
    emit('update', code);
}
function openSlart(mode) {
    slartMode.value = mode;
    showSlart.value = true;
}
function fmtResult(v) {
    if (v === null || v === undefined)
        return '(no return value)';
    if (typeof v === 'string')
        return v;
    try {
        return JSON.stringify(v, null, 2);
    }
    catch {
        return String(v);
    }
}
function fmtDuration(ms) {
    if (ms == null)
        return '';
    if (ms < 1000)
        return `${ms} ms`;
    return `${(ms / 1000).toFixed(2)} s`;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['cortex-code-host']} */ ;
/** @type {__VLS_StyleScopedClasses['cortex-code-host']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full flex flex-col min-h-0" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center gap-2 px-3 py-2 border-b border-base-300 bg-base-100 text-sm" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (__VLS_ctx.onReload) },
    type: "button",
    ...{ class: "\u006f\u0070\u0061\u0063\u0069\u0074\u0079\u002d\u0036\u0030\u0020\u0065\u006e\u0061\u0062\u006c\u0065\u0064\u003a\u0068\u006f\u0076\u0065\u0072\u003a\u006f\u0070\u0061\u0063\u0069\u0074\u0079\u002d\u0031\u0030\u0030\u0020\u0065\u006e\u0061\u0062\u006c\u0065\u0064\u003a\u0068\u006f\u0076\u0065\u0072\u003a\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0032\u0030\u0030\u0020\u0064\u0069\u0073\u0061\u0062\u006c\u0065\u0064\u003a\u0063\u0075\u0072\u0073\u006f\u0072\u002d\u0064\u0065\u0066\u0061\u0075\u006c\u0074\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0070\u0078\u002d\u0031\u0020\u006c\u0065\u0061\u0064\u0069\u006e\u0067\u002d\u006e\u006f\u006e\u0065" },
    disabled: (__VLS_ctx.reloading),
    title: (__VLS_ctx.document.dirty ? 'Reload (discards unsaved changes)' : 'Reload from server'),
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: (__VLS_ctx.reloading ? 'animate-spin inline-block' : '') },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-mono opacity-80 truncate" },
});
(__VLS_ctx.document.path);
if (__VLS_ctx.showToggle) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex border border-base-300 rounded overflow-hidden text-xs" },
        role: "group",
        'aria-label': "View / edit toggle",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.showToggle))
                    return;
                __VLS_ctx.viewEditMode = 'view';
            } },
        type: "button",
        ...{ class: "px-2 py-0.5" },
        ...{ class: (__VLS_ctx.viewEditMode === 'view' ? 'bg-base-300' : 'opacity-60 hover:bg-base-200') },
        title: "Rendered view",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.showToggle))
                    return;
                __VLS_ctx.viewEditMode = 'edit';
            } },
        type: "button",
        ...{ class: "px-2 py-0.5 border-l border-base-300" },
        ...{ class: (__VLS_ctx.viewEditMode === 'edit' ? 'bg-base-300' : 'opacity-60 hover:bg-base-200') },
        title: "Raw source editor",
    });
}
if (__VLS_ctx.runAdapter) {
    if (!__VLS_ctx.isRunning) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.onRun) },
            type: "button",
            ...{ class: "text-xs px-2 py-0.5 rounded border border-base-300 hover:bg-base-200" },
            title: (`${__VLS_ctx.runAdapter.label} — execute the document`),
        });
        (__VLS_ctx.runAdapter.label);
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.onCancel) },
            type: "button",
            ...{ class: "text-xs px-2 py-0.5 rounded border border-warning/40 bg-warning/10 text-warning hover:bg-warning/20" },
            title: "Cancel the running execution",
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        value: (__VLS_ctx.argsJson),
        type: "text",
        spellcheck: "false",
        ...{ class: "text-xs font-mono px-2 py-0.5 rounded border w-32" },
        ...{ class: (__VLS_ctx.argsError ? 'border-error' : 'border-base-300') },
        title: (__VLS_ctx.argsError ?? 'JSON args object, default `{}`'),
        placeholder: "{}",
    });
}
if (__VLS_ctx.isJsLanguage) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.isJsLanguage))
                    return;
                __VLS_ctx.showValidate = true;
            } },
        type: "button",
        ...{ class: "text-xs px-2 py-0.5 rounded border border-base-300 hover:bg-base-200" },
        title: "Validate (quick + deep)",
    });
    if (!__VLS_ctx.editorHasContent) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.isJsLanguage))
                        return;
                    if (!(!__VLS_ctx.editorHasContent))
                        return;
                    __VLS_ctx.openSlart('CREATE');
                } },
            type: "button",
            ...{ class: "text-xs px-2 py-0.5 rounded border border-base-300 hover:bg-base-200" },
            title: "Generate a new script from a free-text description (Slart SCRIPT_JS)",
        });
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.isJsLanguage))
                        return;
                    if (!!(!__VLS_ctx.editorHasContent))
                        return;
                    __VLS_ctx.openSlart('UPDATE');
                } },
            type: "button",
            ...{ class: "text-xs px-2 py-0.5 rounded border border-base-300 hover:bg-base-200" },
            title: "Update this script — describe the change and Slart rewrites it preserving structure",
        });
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.propertiesOpen = !__VLS_ctx.propertiesOpen;
        } },
    type: "button",
    ...{ class: "opacity-60 hover:opacity-100 hover:bg-base-200 rounded px-1.5 py-0.5 text-xs" },
    ...{ class: ({ 'bg-base-300 opacity-100': __VLS_ctx.propertiesOpen }) },
    title: (__VLS_ctx.propertiesOpen ? 'Hide properties' : 'Show properties'),
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.notesOpen = !__VLS_ctx.notesOpen;
        } },
    type: "button",
    ...{ class: "opacity-60 hover:opacity-100 hover:bg-base-200 rounded px-1.5 py-0.5 text-xs" },
    ...{ class: ({ 'bg-base-300 opacity-100': __VLS_ctx.notesOpen }) },
    title: (__VLS_ctx.notesOpen ? 'Notizen ausblenden' : 'Notizen einblenden'),
});
(__VLS_ctx.docNotes.notes.value.length);
if (__VLS_ctx.document.dirty && __VLS_ctx.binding.editLocation === 'client-memory') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-60" },
    });
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
    ...{ class: "flex-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "opacity-50 text-xs font-mono" },
    title: (`binding=${__VLS_ctx.binding.id} mode=${__VLS_ctx.binding.mode} kind=${__VLS_ctx.document.kind ?? 'null'} mime=${__VLS_ctx.document.mimeType ?? 'null'}`),
});
(__VLS_ctx.binding.id);
(__VLS_ctx.binding.mode === 'code' ? __VLS_ctx.effectiveMimeType : (__VLS_ctx.document.mimeType ?? __VLS_ctx.binding.mode));
if (__VLS_ctx.propertiesOpen) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "shrink-0 max-h-[50%] overflow-y-auto" },
    });
    /** @type {[typeof DocumentPropertiesPanel, ]} */ ;
    // @ts-ignore
    const __VLS_0 = __VLS_asFunctionalComponent(DocumentPropertiesPanel, new DocumentPropertiesPanel({
        document: (__VLS_ctx.document),
    }));
    const __VLS_1 = __VLS_0({
        document: (__VLS_ctx.document),
    }, ...__VLS_functionalComponentArgsRest(__VLS_0));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 flex flex-row min-h-0" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 flex flex-col min-h-0" },
});
if (__VLS_ctx.binding.mode === 'code' && __VLS_ctx.isMarkdownDocument && __VLS_ctx.viewEditMode === 'view') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-auto px-4 py-2" },
    });
    const __VLS_3 = {}.MarkdownView;
    /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
    // @ts-ignore
    const __VLS_4 = __VLS_asFunctionalComponent(__VLS_3, new __VLS_3({
        source: (__VLS_ctx.document.inlineText),
    }));
    const __VLS_5 = __VLS_4({
        source: (__VLS_ctx.document.inlineText),
    }, ...__VLS_functionalComponentArgsRest(__VLS_4));
}
else if (__VLS_ctx.binding.mode === 'code') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-hidden cortex-code-host" },
    });
    const __VLS_7 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_8 = __VLS_asFunctionalComponent(__VLS_7, new __VLS_7({
        ...{ 'onUpdate:modelValue': {} },
        ...{ 'onSelectionChanged': {} },
        ...{ 'onNoteAnchorClick': {} },
        ...{ 'onNoteGutterClick': {} },
        modelValue: (__VLS_ctx.document.inlineText),
        mimeType: (__VLS_ctx.effectiveMimeType),
        noteLines: (__VLS_ctx.docNotes.linesWithNotes.value),
    }));
    const __VLS_9 = __VLS_8({
        ...{ 'onUpdate:modelValue': {} },
        ...{ 'onSelectionChanged': {} },
        ...{ 'onNoteAnchorClick': {} },
        ...{ 'onNoteGutterClick': {} },
        modelValue: (__VLS_ctx.document.inlineText),
        mimeType: (__VLS_ctx.effectiveMimeType),
        noteLines: (__VLS_ctx.docNotes.linesWithNotes.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_8));
    let __VLS_11;
    let __VLS_12;
    let __VLS_13;
    const __VLS_14 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.emit('update', v))
    };
    const __VLS_15 = {
        onSelectionChanged: (__VLS_ctx.onSelectionChanged)
    };
    const __VLS_16 = {
        onNoteAnchorClick: (__VLS_ctx.onNoteAnchorClick)
    };
    const __VLS_17 = {
        onNoteGutterClick: (__VLS_ctx.onNoteGutterClick)
    };
    var __VLS_10;
}
else if (__VLS_ctx.binding.mode === 'image') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-auto bg-base-200/40 flex items-start justify-center p-4" },
    });
    /** @type {[typeof ImageView, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(ImageView, new ImageView({
        mode: "editor",
        document: (__VLS_ctx.docDtoForView),
    }));
    const __VLS_19 = __VLS_18({
        mode: "editor",
        document: (__VLS_ctx.docDtoForView),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
}
else if (__VLS_ctx.binding.mode === 'preview') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-4 py-2 bg-base-200/40 border-b border-base-300 text-xs flex flex-wrap gap-x-4 gap-y-1 shrink-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-60" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono" },
    });
    (__VLS_ctx.document.path);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-60" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono" },
    });
    (__VLS_ctx.document.mimeType ?? '—');
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-60 italic" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-auto p-4 bg-base-200/40" },
    });
    /** @type {[typeof DocumentPreview, ]} */ ;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent(DocumentPreview, new DocumentPreview({
        documentId: (__VLS_ctx.document.id),
        mimeType: (__VLS_ctx.document.mimeType ?? null),
    }));
    const __VLS_22 = __VLS_21({
        documentId: (__VLS_ctx.document.id),
        mimeType: (__VLS_ctx.document.mimeType ?? null),
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
}
else if (__VLS_ctx.isViewMode) {
    if (__VLS_ctx.showRawEditor) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-h-0 overflow-hidden cortex-code-host" },
        });
        const __VLS_24 = {}.CodeEditor;
        /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
        // @ts-ignore
        const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
            ...{ 'onUpdate:modelValue': {} },
            ...{ 'onSelectionChanged': {} },
            ...{ 'onNoteAnchorClick': {} },
            ...{ 'onNoteGutterClick': {} },
            modelValue: (__VLS_ctx.document.inlineText),
            mimeType: (__VLS_ctx.effectiveMimeType),
            noteLines: (__VLS_ctx.docNotes.linesWithNotes.value),
        }));
        const __VLS_26 = __VLS_25({
            ...{ 'onUpdate:modelValue': {} },
            ...{ 'onSelectionChanged': {} },
            ...{ 'onNoteAnchorClick': {} },
            ...{ 'onNoteGutterClick': {} },
            modelValue: (__VLS_ctx.document.inlineText),
            mimeType: (__VLS_ctx.effectiveMimeType),
            noteLines: (__VLS_ctx.docNotes.linesWithNotes.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_25));
        let __VLS_28;
        let __VLS_29;
        let __VLS_30;
        const __VLS_31 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.emit('update', v))
        };
        const __VLS_32 = {
            onSelectionChanged: (__VLS_ctx.onSelectionChanged)
        };
        const __VLS_33 = {
            onNoteAnchorClick: (__VLS_ctx.onNoteAnchorClick)
        };
        const __VLS_34 = {
            onNoteGutterClick: (__VLS_ctx.onNoteGutterClick)
        };
        var __VLS_27;
    }
    else if (__VLS_ctx.parseResult.error) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-h-0 flex flex-col overflow-hidden" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-3 py-2 bg-error/10 text-error text-xs border-b border-error/30" },
        });
        (__VLS_ctx.document.kind);
        (__VLS_ctx.parseResult.error);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-h-0 overflow-hidden cortex-code-host" },
        });
        const __VLS_35 = {}.CodeEditor;
        /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
        // @ts-ignore
        const __VLS_36 = __VLS_asFunctionalComponent(__VLS_35, new __VLS_35({
            ...{ 'onUpdate:modelValue': {} },
            ...{ 'onSelectionChanged': {} },
            ...{ 'onNoteAnchorClick': {} },
            ...{ 'onNoteGutterClick': {} },
            modelValue: (__VLS_ctx.document.inlineText),
            mimeType: (__VLS_ctx.effectiveMimeType),
            noteLines: (__VLS_ctx.docNotes.linesWithNotes.value),
        }));
        const __VLS_37 = __VLS_36({
            ...{ 'onUpdate:modelValue': {} },
            ...{ 'onSelectionChanged': {} },
            ...{ 'onNoteAnchorClick': {} },
            ...{ 'onNoteGutterClick': {} },
            modelValue: (__VLS_ctx.document.inlineText),
            mimeType: (__VLS_ctx.effectiveMimeType),
            noteLines: (__VLS_ctx.docNotes.linesWithNotes.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_36));
        let __VLS_39;
        let __VLS_40;
        let __VLS_41;
        const __VLS_42 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.emit('update', v))
        };
        const __VLS_43 = {
            onSelectionChanged: (__VLS_ctx.onSelectionChanged)
        };
        const __VLS_44 = {
            onNoteAnchorClick: (__VLS_ctx.onNoteAnchorClick)
        };
        const __VLS_45 = {
            onNoteGutterClick: (__VLS_ctx.onNoteGutterClick)
        };
        var __VLS_38;
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-h-0 overflow-auto" },
        });
        const __VLS_46 = ((__VLS_ctx.activeView));
        // @ts-ignore
        const __VLS_47 = __VLS_asFunctionalComponent(__VLS_46, new __VLS_46({
            ...{ 'onUpdate:doc': {} },
            mode: "editor",
            ...(__VLS_ctx.viewBindings),
        }));
        const __VLS_48 = __VLS_47({
            ...{ 'onUpdate:doc': {} },
            mode: "editor",
            ...(__VLS_ctx.viewBindings),
        }, ...__VLS_functionalComponentArgsRest(__VLS_47));
        let __VLS_50;
        let __VLS_51;
        let __VLS_52;
        const __VLS_53 = {
            'onUpdate:doc': (__VLS_ctx.onModelUpdate)
        };
        var __VLS_49;
    }
}
if (__VLS_ctx.runHandle) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-none border-t border-base-300 flex flex-col overflow-hidden" },
        ...{ style: {} },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-2 px-3 py-1 bg-base-200 text-xs font-mono border-b border-base-300" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "px-1.5 py-0.5 rounded uppercase tracking-wide" },
        ...{ class: ({
                'bg-info/15 text-info': __VLS_ctx.runState === 'running' || __VLS_ctx.runState === 'starting',
                'bg-success/15 text-success': __VLS_ctx.runState === 'finished',
                'bg-error/15 text-error': __VLS_ctx.runState === 'failed',
                'bg-base-300': __VLS_ctx.runState === 'cancelled' || __VLS_ctx.runState === 'idle',
            }) },
    });
    (__VLS_ctx.runState);
    if (__VLS_ctx.runHandle.durationMs.value != null) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.fmtDuration(__VLS_ctx.runHandle.durationMs.value));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.onCloseLogPanel) },
        type: "button",
        ...{ class: "opacity-60 hover:opacity-100 hover:bg-base-300 rounded px-1" },
        title: "Close log panel",
    });
    if (__VLS_ctx.runHandle.error.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-3 py-1.5 bg-error/10 text-error text-xs font-mono whitespace-pre-wrap border-b border-error/30" },
        });
        (__VLS_ctx.runHandle.error.value);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-y-auto font-mono text-xs p-2 leading-tight" },
    });
    for (const [line, i] of __VLS_getVForSourceType((__VLS_ctx.runHandle.logLines.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (i),
            ...{ class: "whitespace-pre-wrap" },
        });
        (line);
    }
    if (__VLS_ctx.runHandle.logLines.value.length === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-50" },
        });
    }
    if (__VLS_ctx.runState === 'finished' && __VLS_ctx.runHandle.result.value !== null) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "border-t border-base-300 px-3 py-1.5 bg-base-200/40 font-mono text-xs whitespace-pre-wrap max-h-32 overflow-y-auto" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-60 mb-1" },
        });
        (__VLS_ctx.fmtResult(__VLS_ctx.runHandle.result.value));
    }
}
if (__VLS_ctx.notesOpen) {
    /** @type {[typeof DocumentNotesPanel, ]} */ ;
    // @ts-ignore
    const __VLS_54 = __VLS_asFunctionalComponent(DocumentNotesPanel, new DocumentNotesPanel({
        ...{ 'onAdd': {} },
        ...{ 'onUpdate': {} },
        ...{ 'onDelete': {} },
        ...{ 'onJumpToLine': {} },
        notes: (__VLS_ctx.docNotes.notes.value),
        highlightedNoteId: (__VLS_ctx.highlightedNoteId),
    }));
    const __VLS_55 = __VLS_54({
        ...{ 'onAdd': {} },
        ...{ 'onUpdate': {} },
        ...{ 'onDelete': {} },
        ...{ 'onJumpToLine': {} },
        notes: (__VLS_ctx.docNotes.notes.value),
        highlightedNoteId: (__VLS_ctx.highlightedNoteId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_54));
    let __VLS_57;
    let __VLS_58;
    let __VLS_59;
    const __VLS_60 = {
        onAdd: (__VLS_ctx.onAddUnanchoredNote)
    };
    const __VLS_61 = {
        onUpdate: (__VLS_ctx.onUpdateNote)
    };
    const __VLS_62 = {
        onDelete: (__VLS_ctx.onDeleteNote)
    };
    const __VLS_63 = {
        onJumpToLine: (__VLS_ctx.onJumpToLine)
    };
    var __VLS_56;
}
if (__VLS_ctx.showValidate) {
    /** @type {[typeof CortexValidateDialog, ]} */ ;
    // @ts-ignore
    const __VLS_64 = __VLS_asFunctionalComponent(CortexValidateDialog, new CortexValidateDialog({
        ...{ 'onClose': {} },
        document: (__VLS_ctx.document),
    }));
    const __VLS_65 = __VLS_64({
        ...{ 'onClose': {} },
        document: (__VLS_ctx.document),
    }, ...__VLS_functionalComponentArgsRest(__VLS_64));
    let __VLS_67;
    let __VLS_68;
    let __VLS_69;
    const __VLS_70 = {
        onClose: (...[$event]) => {
            if (!(__VLS_ctx.showValidate))
                return;
            __VLS_ctx.showValidate = false;
        }
    };
    var __VLS_66;
}
if (__VLS_ctx.showSlart && __VLS_ctx.store.projectId) {
    /** @type {[typeof CortexHactarDialog, ]} */ ;
    // @ts-ignore
    const __VLS_71 = __VLS_asFunctionalComponent(CortexHactarDialog, new CortexHactarDialog({
        ...{ 'onClose': {} },
        ...{ 'onApply': {} },
        document: (__VLS_ctx.document),
        projectId: (__VLS_ctx.store.projectId),
        sessionId: (__VLS_ctx.sessionId ?? null),
        mode: (__VLS_ctx.slartMode),
    }));
    const __VLS_72 = __VLS_71({
        ...{ 'onClose': {} },
        ...{ 'onApply': {} },
        document: (__VLS_ctx.document),
        projectId: (__VLS_ctx.store.projectId),
        sessionId: (__VLS_ctx.sessionId ?? null),
        mode: (__VLS_ctx.slartMode),
    }, ...__VLS_functionalComponentArgsRest(__VLS_71));
    let __VLS_74;
    let __VLS_75;
    let __VLS_76;
    const __VLS_77 = {
        onClose: (...[$event]) => {
            if (!(__VLS_ctx.showSlart && __VLS_ctx.store.projectId))
                return;
            __VLS_ctx.showSlart = false;
        }
    };
    const __VLS_78 = {
        onApply: (__VLS_ctx.onSlartApply)
    };
    var __VLS_73;
}
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['enabled:hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['enabled:hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled:cursor-default']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-none']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['border-l']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-warning/40']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-warning/10']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-warning/20']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['w-32']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-[50%]']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-row']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['cortex-code-host']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['cortex-code-host']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-error/10']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-error/30']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['cortex-code-host']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-none']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-info/15']} */ ;
/** @type {__VLS_StyleScopedClasses['text-info']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-success/15']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-error/15']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-error/10']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-error/30']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-tight']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-32']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            CodeEditor: CodeEditor,
            MarkdownView: MarkdownView,
            ImageView: ImageView,
            DocumentPreview: DocumentPreview,
            CortexValidateDialog: CortexValidateDialog,
            CortexHactarDialog: CortexHactarDialog,
            DocumentPropertiesPanel: DocumentPropertiesPanel,
            DocumentNotesPanel: DocumentNotesPanel,
            emit: emit,
            store: store,
            binding: binding,
            reloading: reloading,
            onReload: onReload,
            propertiesOpen: propertiesOpen,
            notesOpen: notesOpen,
            docNotes: docNotes,
            highlightedNoteId: highlightedNoteId,
            onAddUnanchoredNote: onAddUnanchoredNote,
            onUpdateNote: onUpdateNote,
            onDeleteNote: onDeleteNote,
            onJumpToLine: onJumpToLine,
            onNoteAnchorClick: onNoteAnchorClick,
            onNoteGutterClick: onNoteGutterClick,
            effectiveMimeType: effectiveMimeType,
            onSelectionChanged: onSelectionChanged,
            docDtoForView: docDtoForView,
            parseResult: parseResult,
            onModelUpdate: onModelUpdate,
            activeView: activeView,
            viewBindings: viewBindings,
            isViewMode: isViewMode,
            isMarkdownDocument: isMarkdownDocument,
            showToggle: showToggle,
            viewEditMode: viewEditMode,
            showRawEditor: showRawEditor,
            runAdapter: runAdapter,
            runHandle: runHandle,
            argsJson: argsJson,
            argsError: argsError,
            runState: runState,
            isRunning: isRunning,
            onRun: onRun,
            onCancel: onCancel,
            onCloseLogPanel: onCloseLogPanel,
            showValidate: showValidate,
            showSlart: showSlart,
            slartMode: slartMode,
            isJsLanguage: isJsLanguage,
            editorHasContent: editorHasContent,
            onSlartApply: onSlartApply,
            openSlart: openSlart,
            fmtResult: fmtResult,
            fmtDuration: fmtDuration,
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
//# sourceMappingURL=DocumentTabShell.vue.js.map