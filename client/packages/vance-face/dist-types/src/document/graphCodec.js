// Codec for `kind: graph` documents — parses an on-disk body into a
// typed GraphDocument and serializes it back. JSON and YAML only;
// markdown is intentionally not supported (see
// `specification/doc-kind-graph.md` §3.3).
//
// Data model: top-level `nodes` and `edges` arrays. Edges are
// first-class entities with their own metadata slot — matches the
// convention used by Cytoscape, GraphML/GEXF, vue-flow internally,
// and the JSON Graph Spec. Edges that point to unknown ids are
// preserved across round-trip but the renderer skips them.
import { dumpYamlMultiDoc, mergeYamlMultiDoc, unwrapJsonMeta, wrapJsonMeta, } from './documentHeaderCodec';
export class GraphCodecError extends Error {
    cause;
    constructor(message, cause) {
        super(message);
        this.cause = cause;
        this.name = 'GraphCodecError';
    }
}
// ── MIME helpers ─────────────────────────────────────────────────────
function isJson(mime) {
    return mime === 'application/json';
}
function isYaml(mime) {
    return mime === 'application/yaml'
        || mime === 'application/x-yaml'
        || mime === 'text/yaml'
        || mime === 'text/x-yaml';
}
// ── Public API ───────────────────────────────────────────────────────
export function parseGraph(body, mimeType) {
    if (isJson(mimeType))
        return parseGraphJson(body);
    if (isYaml(mimeType))
        return parseGraphYaml(body);
    throw new GraphCodecError(`Unsupported mime type for graph: ${mimeType}`);
}
export function serializeGraph(doc, mimeType) {
    if (isJson(mimeType))
        return serializeGraphJson(doc);
    if (isYaml(mimeType))
        return serializeGraphYaml(doc);
    throw new GraphCodecError(`Unsupported mime type for graph: ${mimeType}`);
}
/** Whether the codec can handle this mime type — used by the editor
 *  to decide whether to offer the Graph tab at all. Markdown is
 *  intentionally excluded; spec §3.3. */
export function isGraphMime(mimeType) {
    if (!mimeType)
        return false;
    return isJson(mimeType) || isYaml(mimeType);
}
/** Build a fresh empty node with a generated id. The caller passes
 *  the existing node ids so we pick the smallest free `node_<N>`. */
export function emptyNode(existingIds) {
    const taken = new Set(existingIds);
    let n = 1;
    while (taken.has(`node_${n}`))
        n++;
    return {
        id: `node_${n}`,
        extra: {},
    };
}
/** Synthesise the renderer-friendly id for an edge. The on-disk
 *  representation may omit {@code id}; the editor / renderer needs a
 *  unique handle to track the edge across renders. Falls back to
 *  {@code source->target} which is also what the legacy migration
 *  produces. */
export function edgeKey(edge) {
    return edge.id && edge.id.length > 0
        ? edge.id
        : `${edge.source}->${edge.target}`;
}
// ── JSON ─────────────────────────────────────────────────────────────
function parseGraphJson(body) {
    if (body.trim() === '')
        return emptyDoc();
    let parsed;
    try {
        parsed = JSON.parse(body);
    }
    catch (e) {
        throw new GraphCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    if (!isObject(parsed)) {
        throw new GraphCodecError('Top-level JSON must be an object');
    }
    return promoteToGraphDocument(unwrapJsonMeta(parsed));
}
function serializeGraphJson(doc) {
    const body = buildOnDiskBody(doc);
    return JSON.stringify(wrapJsonMeta(doc.kind || 'graph', body), null, 2) + '\n';
}
// ── YAML ─────────────────────────────────────────────────────────────
function parseGraphYaml(body) {
    if (body.trim() === '')
        return emptyDoc();
    let merged;
    try {
        merged = mergeYamlMultiDoc(body);
    }
    catch (e) {
        throw new GraphCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    return promoteToGraphDocument(merged);
}
function serializeGraphYaml(doc) {
    return dumpYamlMultiDoc(doc.kind || 'graph', buildOnDiskBody(doc));
}
// ── Shared promotion + writeback ────────────────────────────────────
function emptyDoc() {
    return {
        kind: 'graph',
        graph: { directed: true },
        nodes: [],
        edges: [],
        extra: {},
    };
}
function promoteToGraphDocument(obj) {
    const kind = typeof obj.kind === 'string' ? obj.kind : '';
    const graph = promoteConfig(obj.graph);
    // Promote nodes first so legacy `node.edges` migration can see the
    // valid node-id set when synthesising top-level edges.
    const { nodes, legacyEdges } = promoteNodes(obj.nodes);
    const explicitEdges = promoteEdges(obj.edges);
    // Combined: explicit top-level edges win over legacy node-edges of
    // the same shape. Legacy edges only fire for old-format documents
    // that hadn't been resaved since the spec change; new saves never
    // emit `edges` on a node, so this is a one-time migration.
    const edges = mergeEdges(explicitEdges, legacyEdges);
    const { kind: _k, graph: _g, nodes: _n, edges: _e, ...extra } = obj;
    return { kind, graph, nodes, edges, extra };
}
function promoteConfig(raw) {
    if (isObject(raw) && typeof raw.directed === 'boolean') {
        return { directed: raw.directed };
    }
    return { directed: true };
}
function promoteNodes(raw) {
    if (!Array.isArray(raw))
        return { nodes: [], legacyEdges: [] };
    const nodes = [];
    const legacyEdges = [];
    const seenIds = new Set();
    for (const r of raw) {
        if (!isObject(r))
            continue;
        const idRaw = r.id;
        if (typeof idRaw !== 'string')
            continue;
        const id = idRaw.trim();
        if (!id)
            continue;
        if (seenIds.has(id)) {
            throw new GraphCodecError(`Duplicate node id: ${id}`);
        }
        seenIds.add(id);
        const node = { id, extra: {} };
        if (typeof r.label === 'string')
            node.label = r.label;
        if (typeof r.color === 'string' && r.color)
            node.color = r.color;
        const pos = promotePosition(r.position);
        if (pos)
            node.position = pos;
        // Backward-compat: legacy out-edge string-list. Lift each entry
        // into a top-level edge; the new format never writes node.edges,
        // so this only triggers for old documents.
        if (Array.isArray(r.edges)) {
            for (const target of r.edges) {
                if (typeof target === 'string' && target.trim()) {
                    legacyEdges.push({
                        source: id,
                        target: target.trim(),
                        extra: {},
                    });
                }
            }
        }
        for (const [k, v] of Object.entries(r)) {
            if (k === 'id' || k === 'label' || k === 'color' || k === 'position' || k === 'edges')
                continue;
            node.extra[k] = v;
        }
        nodes.push(node);
    }
    return { nodes, legacyEdges };
}
function promoteEdges(raw) {
    if (!Array.isArray(raw))
        return [];
    const out = [];
    for (const r of raw) {
        if (!isObject(r))
            continue;
        const sourceRaw = r.source;
        const targetRaw = r.target;
        if (typeof sourceRaw !== 'string' || typeof targetRaw !== 'string')
            continue;
        const source = sourceRaw.trim();
        const target = targetRaw.trim();
        if (!source || !target)
            continue;
        const edge = { source, target, extra: {} };
        if (typeof r.id === 'string' && r.id.trim())
            edge.id = r.id.trim();
        if (typeof r.label === 'string')
            edge.label = r.label;
        if (typeof r.color === 'string' && r.color)
            edge.color = r.color;
        for (const [k, v] of Object.entries(r)) {
            if (k === 'id' || k === 'source' || k === 'target' || k === 'label' || k === 'color')
                continue;
            edge.extra[k] = v;
        }
        out.push(edge);
    }
    return out;
}
/** Combine explicit top-level edges with legacy node-edges. Explicit
 *  wins on collision (same id, or same source-target without ids). */
function mergeEdges(explicit, legacy) {
    if (legacy.length === 0)
        return explicit;
    const seen = new Set();
    for (const e of explicit)
        seen.add(edgeKey(e));
    const out = [...explicit];
    for (const e of legacy) {
        const key = edgeKey(e);
        if (seen.has(key))
            continue;
        seen.add(key);
        out.push(e);
    }
    return out;
}
function promotePosition(raw) {
    if (!isObject(raw))
        return undefined;
    const x = typeof raw.x === 'number' && Number.isFinite(raw.x) ? raw.x : undefined;
    const y = typeof raw.y === 'number' && Number.isFinite(raw.y) ? raw.y : undefined;
    if (x === undefined || y === undefined)
        return undefined;
    return { x, y };
}
/** Top-level body keys (everything except `kind`, which lives in
 *  the `$meta` wrapper). */
function buildOnDiskBody(doc) {
    return {
        graph: { directed: doc.graph?.directed ?? true },
        nodes: doc.nodes.map(nodeToObject),
        edges: doc.edges.map(edgeToObject),
        ...doc.extra,
    };
}
function nodeToObject(node) {
    const obj = { id: node.id };
    if (node.label !== undefined)
        obj.label = node.label;
    if (node.color !== undefined)
        obj.color = node.color;
    if (node.position !== undefined) {
        obj.position = { x: node.position.x, y: node.position.y };
    }
    for (const [k, v] of Object.entries(node.extra)) {
        if (!(k in obj))
            obj[k] = v;
    }
    return obj;
}
function edgeToObject(edge) {
    const obj = {};
    // `id` is optional on disk: only written when the user has set
    // one explicitly. Synthesised ids (from edgeKey() fallback) live
    // only in memory and don't get persisted, keeping the on-disk
    // shape minimal.
    if (edge.id !== undefined)
        obj.id = edge.id;
    obj.source = edge.source;
    obj.target = edge.target;
    if (edge.label !== undefined)
        obj.label = edge.label;
    if (edge.color !== undefined)
        obj.color = edge.color;
    for (const [k, v] of Object.entries(edge.extra)) {
        if (!(k in obj))
            obj[k] = v;
    }
    return obj;
}
function isObject(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
//# sourceMappingURL=graphCodec.js.map