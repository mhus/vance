export interface GraphPosition {
    x: number;
    y: number;
}
export interface GraphNode {
    id: string;
    label?: string;
    color?: string;
    position?: GraphPosition;
    /** Unknown per-node fields, preserved across round-trip. */
    extra: Record<string, unknown>;
}
export interface GraphEdge {
    /** Optional stable id. When missing on disk we synthesise
     *  {@code `${source}->${target}`} so the renderer always has one,
     *  but the synthetic value is not written back unless the user
     *  explicitly set it (round-trip preserves the original on-disk
     *  shape). */
    id?: string;
    source: string;
    target: string;
    label?: string;
    color?: string;
    /** Unknown per-edge fields, preserved across round-trip. */
    extra: Record<string, unknown>;
}
export interface GraphConfig {
    /** When true, the renderer draws arrowheads. Default true. */
    directed: boolean;
}
export interface GraphDocument {
    /** Always `'graph'` for graph documents. */
    kind: string;
    graph: GraphConfig;
    nodes: GraphNode[];
    edges: GraphEdge[];
    /** Unknown top-level fields. Re-emitted verbatim on save. */
    extra: Record<string, unknown>;
}
export declare class GraphCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
export declare function parseGraph(body: string, mimeType: string): GraphDocument;
export declare function serializeGraph(doc: GraphDocument, mimeType: string): string;
/** Whether the codec can handle this mime type — used by the editor
 *  to decide whether to offer the Graph tab at all. Markdown is
 *  intentionally excluded; spec §3.3. */
export declare function isGraphMime(mimeType: string | null | undefined): boolean;
/** Build a fresh empty node with a generated id. The caller passes
 *  the existing node ids so we pick the smallest free `node_<N>`. */
export declare function emptyNode(existingIds: Iterable<string>): GraphNode;
/** Synthesise the renderer-friendly id for an edge. The on-disk
 *  representation may omit {@code id}; the editor / renderer needs a
 *  unique handle to track the edge across renders. Falls back to
 *  {@code source->target} which is also what the legacy migration
 *  produces. */
export declare function edgeKey(edge: GraphEdge): string;
//# sourceMappingURL=graphCodec.d.ts.map