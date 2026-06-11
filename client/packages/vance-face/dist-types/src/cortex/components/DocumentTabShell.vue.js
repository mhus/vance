import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { CodeEditor } from '@/components';
import ImageView from '@/document/ImageView.vue';
import { useCortexStore } from '../stores/cortexStore';
import { resolveBinding } from '../docTypeRegistry';
import { resolveRunAdapter } from '../runners/runnerRegistry';
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
const propertiesUrl = computed(() => {
    const pid = store.projectId;
    if (!pid)
        return null;
    const params = new URLSearchParams({
        projectId: pid,
        documentId: props.document.id,
    });
    return `/documents.html?${params.toString()}`;
});
const effectiveMimeType = computed(() => {
    const explicit = props.document.mimeType;
    if (explicit)
        return explicit;
    const lower = props.document.path.toLowerCase();
    if (lower.endsWith('.md'))
        return 'text/markdown';
    if (lower.endsWith('.json'))
        return 'application/json';
    if (lower.endsWith('.yaml') || lower.endsWith('.yml'))
        return 'application/yaml';
    if (lower.endsWith('.js') || lower.endsWith('.mjs'))
        return 'text/javascript';
    if (lower.endsWith('.ts'))
        return 'text/typescript';
    if (lower.endsWith('.py'))
        return 'text/x-python';
    if (lower.endsWith('.sh') || lower.endsWith('.bash'))
        return 'text/x-shellscript';
    return 'text/plain';
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
if (__VLS_ctx.isViewMode) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex border border-base-300 rounded overflow-hidden text-xs" },
        role: "group",
        'aria-label': "View / edit toggle",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.isViewMode))
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
                if (!(__VLS_ctx.isViewMode))
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
if (__VLS_ctx.propertiesUrl) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        href: (__VLS_ctx.propertiesUrl),
        target: "_blank",
        rel: "noopener",
        ...{ class: "opacity-60 hover:opacity-100 hover:bg-base-200 rounded px-1 leading-none" },
        title: "Open document properties in a new tab",
    });
}
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
if (__VLS_ctx.binding.mode === 'code') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-hidden" },
    });
    const __VLS_0 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ 'onUpdate:modelValue': {} },
        ...{ 'onSelectionChanged': {} },
        modelValue: (__VLS_ctx.document.inlineText),
        mimeType: (__VLS_ctx.effectiveMimeType),
    }));
    const __VLS_2 = __VLS_1({
        ...{ 'onUpdate:modelValue': {} },
        ...{ 'onSelectionChanged': {} },
        modelValue: (__VLS_ctx.document.inlineText),
        mimeType: (__VLS_ctx.effectiveMimeType),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.emit('update', v))
    };
    const __VLS_8 = {
        onSelectionChanged: (__VLS_ctx.onSelectionChanged)
    };
    var __VLS_3;
}
else if (__VLS_ctx.binding.mode === 'image') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-auto bg-base-200/40 flex items-start justify-center p-4" },
    });
    /** @type {[typeof ImageView, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(ImageView, new ImageView({
        mode: "editor",
        document: (__VLS_ctx.docDtoForView),
    }));
    const __VLS_10 = __VLS_9({
        mode: "editor",
        document: (__VLS_ctx.docDtoForView),
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
}
else if (__VLS_ctx.isViewMode) {
    if (__VLS_ctx.showRawEditor) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-h-0 overflow-hidden" },
        });
        const __VLS_12 = {}.CodeEditor;
        /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
        // @ts-ignore
        const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
            ...{ 'onUpdate:modelValue': {} },
            ...{ 'onSelectionChanged': {} },
            modelValue: (__VLS_ctx.document.inlineText),
            mimeType: (__VLS_ctx.effectiveMimeType),
        }));
        const __VLS_14 = __VLS_13({
            ...{ 'onUpdate:modelValue': {} },
            ...{ 'onSelectionChanged': {} },
            modelValue: (__VLS_ctx.document.inlineText),
            mimeType: (__VLS_ctx.effectiveMimeType),
        }, ...__VLS_functionalComponentArgsRest(__VLS_13));
        let __VLS_16;
        let __VLS_17;
        let __VLS_18;
        const __VLS_19 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.emit('update', v))
        };
        const __VLS_20 = {
            onSelectionChanged: (__VLS_ctx.onSelectionChanged)
        };
        var __VLS_15;
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
            ...{ class: "flex-1 min-h-0 overflow-hidden" },
        });
        const __VLS_21 = {}.CodeEditor;
        /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
        // @ts-ignore
        const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
            ...{ 'onUpdate:modelValue': {} },
            ...{ 'onSelectionChanged': {} },
            modelValue: (__VLS_ctx.document.inlineText),
            mimeType: (__VLS_ctx.effectiveMimeType),
        }));
        const __VLS_23 = __VLS_22({
            ...{ 'onUpdate:modelValue': {} },
            ...{ 'onSelectionChanged': {} },
            modelValue: (__VLS_ctx.document.inlineText),
            mimeType: (__VLS_ctx.effectiveMimeType),
        }, ...__VLS_functionalComponentArgsRest(__VLS_22));
        let __VLS_25;
        let __VLS_26;
        let __VLS_27;
        const __VLS_28 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.emit('update', v))
        };
        const __VLS_29 = {
            onSelectionChanged: (__VLS_ctx.onSelectionChanged)
        };
        var __VLS_24;
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-h-0 overflow-auto" },
        });
        const __VLS_30 = ((__VLS_ctx.activeView));
        // @ts-ignore
        const __VLS_31 = __VLS_asFunctionalComponent(__VLS_30, new __VLS_30({
            ...{ 'onUpdate:doc': {} },
            mode: "editor",
            ...(__VLS_ctx.viewBindings),
        }));
        const __VLS_32 = __VLS_31({
            ...{ 'onUpdate:doc': {} },
            mode: "editor",
            ...(__VLS_ctx.viewBindings),
        }, ...__VLS_functionalComponentArgsRest(__VLS_31));
        let __VLS_34;
        let __VLS_35;
        let __VLS_36;
        const __VLS_37 = {
            'onUpdate:doc': (__VLS_ctx.onModelUpdate)
        };
        var __VLS_33;
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
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-none']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
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
            ImageView: ImageView,
            emit: emit,
            binding: binding,
            reloading: reloading,
            onReload: onReload,
            propertiesUrl: propertiesUrl,
            effectiveMimeType: effectiveMimeType,
            onSelectionChanged: onSelectionChanged,
            docDtoForView: docDtoForView,
            parseResult: parseResult,
            onModelUpdate: onModelUpdate,
            activeView: activeView,
            viewBindings: viewBindings,
            isViewMode: isViewMode,
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