import { defineAsyncComponent } from 'vue';
import { resolveKindFor } from '@vance/kind-registry';
import ListView from '@/document/ListView.vue';
import TreeView from '@/document/TreeView.vue';
import RecordsView from '@/document/RecordsView.vue';
import SheetView from '@/document/SheetView.vue';
import MindmapView from '@/document/MindmapView.vue';
// Heavy views — lazy-load to keep the cortex bundle lean, mirroring
// the pattern in documents/DocumentApp.vue.
const ChecklistView = defineAsyncComponent(() => import('@/document/ChecklistView.vue'));
const ChartView = defineAsyncComponent(() => import('@/document/ChartView.vue'));
const GraphView = defineAsyncComponent(() => import('@/document/GraphView.vue'));
const SlidesView = defineAsyncComponent(() => import('@/document/SlidesView.vue'));
const DiagramView = defineAsyncComponent(() => import('@/document/DiagramView.vue'));
import { parseList, serializeList, isListMime, } from '@/document/listItemsCodec';
import { parseChecklist, serializeChecklist, isChecklistMime, } from '@/document/checklistCodec';
import { parseTree, serializeTree, isTreeMime, } from '@/document/treeItemsCodec';
import { parseRecords, serializeRecords, isRecordsMime, } from '@/document/recordsCodec';
import { parseChart, serializeChart, isChartMime, } from '@/document/chartCodec';
import { parseSheet, serializeSheet, isSheetMime, } from '@/document/sheetCodec';
import { parseGraph, serializeGraph, isGraphMime, } from '@/document/graphCodec';
import { parseSlides, serializeSlides, isSlidesMime, } from '@/document/slidesCodec';
import { parseDiagram, serializeDiagram, isDiagramMime, } from '@/document/diagramCodec';
const IMAGE_EXTS = [
    '.png',
    '.jpg',
    '.jpeg',
    '.gif',
    '.webp',
    '.svg',
    '.bmp',
    '.ico',
];
/**
 * Matches a document whose declared {@code kind} equals one of the
 * given values AND whose mime-type the codec accepts. The kind check is
 * what distinguishes a {@code kind: list} document from a generic
 * markdown file with the same mime-type.
 */
function kindAndMime(kinds, mimeCheck) {
    const lc = kinds.map((k) => k.toLowerCase());
    return (doc) => {
        const k = (doc.kind ?? '').toLowerCase();
        if (!lc.includes(k))
            return false;
        return mimeCheck(doc.mimeType);
    };
}
const listCodec = {
    parse: (body, mime) => parseList(body, mime),
    serialize: (doc, mime) => serializeList(doc, mime),
};
const checklistCodec = {
    parse: (body, mime) => parseChecklist(body, mime),
    serialize: (doc, mime) => serializeChecklist(doc, mime),
};
const treeCodec = {
    parse: (body, mime) => parseTree(body, mime),
    serialize: (doc, mime) => serializeTree(doc, mime),
};
const recordsCodec = {
    parse: (body, mime) => parseRecords(body, mime),
    serialize: (doc, mime) => serializeRecords(doc, mime),
};
const chartCodec = {
    parse: (body, mime) => parseChart(body, mime),
    serialize: (doc, mime) => serializeChart(doc, mime),
};
const sheetCodec = {
    parse: (body, mime) => parseSheet(body, mime),
    serialize: (doc, mime) => serializeSheet(doc, mime),
};
const graphCodec = {
    parse: (body, mime) => parseGraph(body, mime),
    serialize: (doc, mime) => serializeGraph(doc, mime),
};
const slidesCodec = {
    parse: (body, mime) => parseSlides(body, mime),
    serialize: (doc, mime) => serializeSlides(doc, mime),
};
const diagramCodec = {
    parse: (body, mime) => parseDiagram(body, mime),
    serialize: (doc, mime) => serializeDiagram(doc, mime),
};
const handRolled = [
    // ── Editable typed-model views ───────────────────────────────────
    {
        id: 'tree',
        match: kindAndMime(['tree'], isTreeMime),
        mode: 'typed-model',
        editLocation: 'client-memory',
        view: TreeView,
        codec: treeCodec,
    },
    {
        id: 'list',
        match: kindAndMime(['list'], isListMime),
        mode: 'typed-model',
        editLocation: 'client-memory',
        view: ListView,
        codec: listCodec,
    },
    {
        id: 'checklist',
        match: kindAndMime(['checklist'], isChecklistMime),
        mode: 'typed-model',
        editLocation: 'client-memory',
        view: ChecklistView,
        codec: checklistCodec,
    },
    {
        id: 'records',
        match: kindAndMime(['records'], isRecordsMime),
        mode: 'typed-model',
        editLocation: 'client-memory',
        view: RecordsView,
        codec: recordsCodec,
    },
    {
        id: 'chart',
        match: kindAndMime(['chart'], isChartMime),
        mode: 'typed-model',
        editLocation: 'client-memory',
        view: ChartView,
        codec: chartCodec,
    },
    {
        id: 'sheet',
        match: kindAndMime(['sheet'], isSheetMime),
        mode: 'typed-model',
        editLocation: 'client-memory',
        view: SheetView,
        codec: sheetCodec,
    },
    {
        id: 'graph',
        match: kindAndMime(['graph'], isGraphMime),
        mode: 'typed-model',
        editLocation: 'client-memory',
        view: GraphView,
        codec: graphCodec,
    },
    // ── Render-only views (no @update:doc emit in the source view) ──
    // Mindmap shares TreeDocument with TreeView; the MindmapView
    // markmap-renders read-only. Source-edit happens via TreeView for
    // kind: tree — for kind: mindmap we show the markmap.
    {
        id: 'mindmap',
        match: kindAndMime(['mindmap'], isTreeMime),
        mode: 'typed-model',
        editLocation: 'server-side',
        view: MindmapView,
        codec: treeCodec,
    },
    {
        id: 'slides',
        match: kindAndMime(['slides'], isSlidesMime),
        mode: 'typed-model',
        editLocation: 'server-side',
        view: SlidesView,
        codec: slidesCodec,
    },
    {
        id: 'diagram',
        match: kindAndMime(['diagram'], isDiagramMime),
        mode: 'typed-model',
        editLocation: 'server-side',
        view: DiagramView,
        codec: diagramCodec,
    },
    // ── Image: mime-type or extension; read-only. ──
    {
        id: 'image',
        match: (doc) => {
            const m = (doc.mimeType ?? '').toLowerCase();
            if (m.startsWith('image/'))
                return true;
            const p = doc.path.toLowerCase();
            return IMAGE_EXTS.some((ext) => p.endsWith(ext));
        },
        mode: 'image',
        editLocation: 'server-side',
    },
    // ── Catch-all: CodeEditor on the raw inlineText. Must stay last. ──
    {
        id: 'code',
        match: () => true,
        mode: 'code',
        editLocation: 'client-memory',
    },
];
/**
 * Resolve which binding renders the given document.
 *
 * Lookup order:
 *  1. {@code @vance/kind-registry} — addon-contributed Kinds (e.g.
 *     Calendar) and any host built-ins that have migrated to the
 *     registry.
 *  2. Hand-rolled bindings — for the kinds DocumentApp still dispatches
 *     via hard-coded {@code if/else}.
 *  3. Catch-all CodeEditor on the raw inlineText.
 */
export function resolveBinding(doc) {
    const kindEntry = resolveKindFor(doc.kind, doc.mimeType);
    if (kindEntry) {
        return {
            id: `kind-registry:${kindEntry.id}`,
            mode: 'kind-registry',
            editLocation: kindEntry.serialize ? 'client-memory' : 'server-side',
            kindEntry,
        };
    }
    for (const entry of handRolled) {
        if (entry.match(doc)) {
            return {
                id: entry.id,
                mode: entry.mode,
                editLocation: entry.editLocation,
                view: entry.view,
                codec: entry.codec,
            };
        }
    }
    // The last hand-rolled entry is the catch-all; defensive return in
    // case the array is empty.
    const fallback = handRolled[handRolled.length - 1];
    return {
        id: fallback.id,
        mode: fallback.mode,
        editLocation: fallback.editLocation,
        view: fallback.view,
        codec: fallback.codec,
    };
}
//# sourceMappingURL=docTypeRegistry.js.map