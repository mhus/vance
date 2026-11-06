import { Graph as DagreGraph, layout as dagreLayout } from '@dagrejs/dagre';
import type { WorkflowGraph } from './workflowGraphCodec';

/** Layout box for one state node — matches the node card's CSS. */
export const NODE_W = 210;
export const NODE_H = 74;

/** Top-down reads like a flowchart; wide graphs are easier left-right. */
export type GraphDirection = 'TB' | 'LR';

export interface NodePosition {
  x: number;
  y: number;
}

/**
 * Derived layout: state name → top-left corner. Nothing is stored, every call
 * lays the graph out afresh. Edges to or from undeclared states are left out
 * of the layout (the ghost node is placed by the caller's fallback).
 */
export function layoutPositions(
  graph: WorkflowGraph,
  direction: GraphDirection,
): Map<string, NodePosition> {
  const out = new Map<string, NodePosition>();
  if (graph.states.length === 0) return out;

  const dag = new DagreGraph();
  dag.setGraph({
    rankdir: direction,
    nodesep: 44,
    ranksep: 78,
    marginx: 24,
    marginy: 24,
  });
  dag.setDefaultEdgeLabel(() => ({}));
  for (const state of graph.states) {
    dag.setNode(state.name, { width: NODE_W, height: NODE_H });
  }
  const known = new Set(graph.states.map((s) => s.name));
  for (const edge of graph.edges) {
    if (known.has(edge.source) && known.has(edge.target)) {
      dag.setEdge(edge.source, edge.target);
    }
  }
  dagreLayout(dag);

  for (const state of graph.states) {
    const laid = dag.node(state.name) as { x?: number; y?: number } | undefined;
    if (typeof laid?.x !== 'number' || typeof laid?.y !== 'number') continue;
    // Dagre centres its boxes; vue-flow positions by top-left corner.
    out.set(state.name, { x: laid.x - NODE_W / 2, y: laid.y - NODE_H / 2 });
  }
  return out;
}

/** Where a node goes when the layout has no place for it: a column down the left. */
export function fallbackPosition(index: number): NodePosition {
  return { x: 40, y: 40 + index * (NODE_H + 30) };
}
