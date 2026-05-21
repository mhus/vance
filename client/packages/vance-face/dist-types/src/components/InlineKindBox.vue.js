import { computed, ref } from 'vue';
import { brainFetch } from '@vance/shared';
import KindBox from './KindBox.vue';
import { kindIcon, kindLabel, resolveRenderer } from '@/kindRenderers/registry';
import { useDocumentRefStore } from '@/document/documentRefStore';
const props = withDefaults(defineProps(), { meta: () => ({}) });
const renderer = computed(() => resolveRenderer(props.kind, 'inline'));
const showRaw = ref(false);
const status = ref(null);
const saving = ref(false);
const label = computed(() => kindLabel(props.kind));
const icon = computed(() => kindIcon(props.kind));
const documentRefStore = useDocumentRefStore();
function onCopy() {
    if (typeof navigator === 'undefined' || !navigator.clipboard)
        return;
    void navigator.clipboard.writeText(props.content);
    flashStatus('ok', 'Inhalt kopiert');
}
function onDownload() {
    const ext = extForKind(props.kind);
    const ts = timestampSlug();
    const filename = `${props.kind}-${ts}.${ext}`;
    const blob = new Blob([props.content], { type: mimeForKind(props.kind) });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}
function toggleRaw() {
    showRaw.value = !showRaw.value;
}
/**
 * Save-as-Document — promote-pfad. Prompts the user for a path
 * (with a sensible default), POSTs to the documents endpoint, builds
 * the canonical {@code vance:} link client-side, copies the link to
 * the clipboard and surfaces a one-line success badge inside the
 * KindBox. On error: an inline error message; the inline block keeps
 * working unchanged.
 */
async function onSaveAsDocument() {
    if (saving.value)
        return;
    const projectId = documentRefStore.currentProject;
    if (!projectId) {
        flashStatus('err', 'Kein aktuelles Project — bind erst eine Chat-Session.');
        return;
    }
    const defaultPath = `documents/${props.kind}-${timestampSlug()}.${extForKind(props.kind)}`;
    // window.prompt is intentionally minimal v1 — a proper Modal can
    // come later. The user can rename the document afterwards in the
    // document editor anyway.
    const userPath = window.prompt('Pfad für das neue Document:', defaultPath);
    if (userPath === null)
        return; // cancelled
    const path = userPath.trim() || defaultPath;
    saving.value = true;
    status.value = null;
    try {
        const body = {
            path,
            inlineText: props.content,
            mimeType: mimeForKind(props.kind),
        };
        const params = new URLSearchParams({ projectId });
        const created = await brainFetch('POST', `documents?${params}`, { body });
        const link = buildVanceLink(created.path ?? path, props.kind);
        if (typeof navigator !== 'undefined' && navigator.clipboard) {
            void navigator.clipboard.writeText(link);
        }
        flashStatus('ok', `Gespeichert: ${created.path ?? path} — Link kopiert`);
    }
    catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        flashStatus('err', `Speichern fehlgeschlagen: ${msg}`);
    }
    finally {
        saving.value = false;
    }
}
function buildVanceLink(path, kind) {
    const encodedPath = path
        .split('/')
        .map((seg) => encodeURIComponent(seg))
        .join('/');
    const k = encodeURIComponent(kind.toLowerCase());
    const text = path.split('/').pop() || path;
    // Image-style for image/svg kinds, link-style otherwise — same
    // heuristic as DocumentLinkBuilder server-side.
    const imageStyle = kind.toLowerCase() === 'image' || kind.toLowerCase() === 'svg';
    return `${imageStyle ? '!' : ''}[${text}](vance:/${encodedPath}?kind=${k})`;
}
function flashStatus(kind, text) {
    status.value = { kind, text };
    // Auto-clear OK messages so the box doesn't permanently carry a
    // stale toast; errors stick until the user toggles another action.
    if (kind === 'ok') {
        setTimeout(() => {
            if (status.value?.text === text)
                status.value = null;
        }, 4000);
    }
}
function timestampSlug() {
    return new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
}
function extForKind(kind) {
    switch (kind.toLowerCase()) {
        case 'json': return 'json';
        case 'yaml': return 'yaml';
        case 'xml': return 'xml';
        case 'bash': return 'sh';
        case 'java': return 'java';
        case 'python': return 'py';
        case 'sql': return 'sql';
        case 'markdown': return 'md';
        case 'mindmap':
        case 'tree':
        case 'list':
        case 'items':
        case 'table':
            return 'md';
        case 'graph':
        case 'records':
        case 'sheet':
            return 'json';
        case 'svg': return 'svg';
        default: return 'txt';
    }
}
function mimeForKind(kind) {
    switch (kind.toLowerCase()) {
        case 'json': return 'application/json';
        case 'yaml': return 'application/yaml';
        case 'xml': return 'application/xml';
        case 'svg': return 'image/svg+xml';
        case 'markdown':
        case 'mindmap':
        case 'tree':
        case 'list':
        case 'items':
        case 'table':
            return 'text/markdown';
        case 'graph':
        case 'records':
        case 'sheet':
            return 'application/json';
        default:
            return 'text/plain';
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ meta: () => ({}) });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['kbx-act']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-act']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-raw']} */ ;
// CSS variable injection 
// CSS variable injection end 
/** @type {[typeof KindBox, typeof KindBox, ]} */ ;
// @ts-ignore
const __VLS_0 = __VLS_asFunctionalComponent(KindBox, new KindBox({
    kind: (__VLS_ctx.kind),
    label: (__VLS_ctx.label),
    icon: (__VLS_ctx.icon),
}));
const __VLS_1 = __VLS_0({
    kind: (__VLS_ctx.kind),
    label: (__VLS_ctx.label),
    icon: (__VLS_ctx.icon),
}, ...__VLS_functionalComponentArgsRest(__VLS_0));
var __VLS_3 = {};
__VLS_2.slots.default;
{
    const { actions: __VLS_thisSlot } = __VLS_2.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.onDownload) },
        ...{ class: "kbx-act" },
        title: (__VLS_ctx.$t?.('chat.kindBox.download') ?? 'Download'),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.toggleRaw) },
        ...{ class: "kbx-act" },
        title: (__VLS_ctx.$t?.('chat.kindBox.raw') ?? 'Raw'),
    });
    (__VLS_ctx.showRaw ? '⟲' : '&lt;/&gt;');
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.onCopy) },
        ...{ class: "kbx-act" },
        title: (__VLS_ctx.$t?.('chat.kindBox.copy') ?? 'Copy'),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.onSaveAsDocument) },
        ...{ class: "kbx-act" },
        disabled: (__VLS_ctx.saving),
        title: (__VLS_ctx.$t?.('chat.kindBox.saveAsDocument') ?? 'Save as Document'),
    });
    (__VLS_ctx.saving ? '⌛' : '💾');
}
if (__VLS_ctx.status) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: (['kbx-status', __VLS_ctx.status.kind === 'ok' ? 'kbx-status--ok' : 'kbx-status--err']) },
    });
    (__VLS_ctx.status.text);
}
if (__VLS_ctx.showRaw || !__VLS_ctx.renderer) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
        ...{ class: "kbx-raw" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
    (__VLS_ctx.content);
}
else {
    const __VLS_4 = ((__VLS_ctx.renderer.inline));
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        mode: "inline",
        content: (__VLS_ctx.content),
        meta: (__VLS_ctx.meta),
    }));
    const __VLS_6 = __VLS_5({
        mode: "inline",
        content: (__VLS_ctx.content),
        meta: (__VLS_ctx.meta),
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
}
var __VLS_2;
/** @type {__VLS_StyleScopedClasses['kbx-act']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-act']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-act']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-act']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-status']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-raw']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            KindBox: KindBox,
            renderer: renderer,
            showRaw: showRaw,
            status: status,
            saving: saving,
            label: label,
            icon: icon,
            onCopy: onCopy,
            onDownload: onDownload,
            toggleRaw: toggleRaw,
            onSaveAsDocument: onSaveAsDocument,
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
//# sourceMappingURL=InlineKindBox.vue.js.map