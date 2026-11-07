import { Graph as DagreGraph, layout as dagreLayout } from '@dagrejs/dagre';
import { loopBody } from './loopBody';
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

/** A frame drawn around the body of a loop. */
export interface LoopFrame {
  /** The loop state whose body it surrounds. */
  loop: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

/** What a layout hands back: where each state goes, and the frames of the loops. */
export interface GraphLayout {
  /** State name → top-left corner. */
  positions: Map<string, NodePosition>;
  /** One frame per loop whose body has states, outer frames before the ones inside them. */
  frames: LoopFrame[];
}

/** How far a frame stands off the states it surrounds; a frame inside another gives another step to the outer one. */
const FRAME_PAD = 14;

/** A cluster's id in the layout graph. The NUL cannot occur in a YAML key that names a state. */
const clusterId = (loop: string): string => `\u0000frame\u0000${loop}`;

/**
 * Derived layout. Nothing is stored, every call lays the graph out afresh. Edges to or from undeclared states
 * are left out of the layout (the ghost node is placed by the caller's fallback).
 *
 * The body of each loop is laid out as a cluster, so its states stay together and no state that is not part
 * of it ends up inside its frame.
 */
export function layoutGraph(graph: WorkflowGraph, direction: GraphDirection): GraphLayout {
  const positions = new Map<string, NodePosition>();
  const frames: LoopFrame[] = [];
  if (graph.states.length === 0) return { positions, frames };

  // Every node of the drawing, ghosts of undeclared states included: the layout places them like any other.
  const known = new Set(graph.states.map((s) => s.name));
  const bodies = new Map<string, Set<string>>();
  for (const state of graph.states) {
    if (state.type !== 'for_each_task' || state.missing) continue;
    const body = new Set([...loopBody(graph.states, graph.edges, state.name)].filter((n) => known.has(n)));
    if (body.size > 0) bodies.set(state.name, body);
  }
  /** The innermost loop whose body holds {@code name}, other than {@code except}. */
  const innermost = (name: string, except?: string): string | null => {
    let best: string | null = null;
    for (const [loop, body] of bodies) {
      if (loop === except || !body.has(name)) continue;
      if (best === null || body.size < (bodies.get(best)?.size ?? Infinity)) best = loop;
    }
    return best;
  };

  const dag = new DagreGraph({ compound: bodies.size > 0 });
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
  for (const loop of bodies.keys()) dag.setNode(clusterId(loop), {});
  for (const state of graph.states) {
    const parent = innermost(state.name);
    if (parent) dag.setParent(state.name, clusterId(parent));
  }
  for (const loop of bodies.keys()) {
    const parent = innermost(loop, loop);
    if (parent) dag.setParent(clusterId(loop), clusterId(parent));
  }
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
    positions.set(state.name, { x: laid.x - NODE_W / 2, y: laid.y - NODE_H / 2 });
  }

  // How many frames lie inside each one, so an outer frame stands off its inner ones.
  const inside = (loop: string): number => {
    let depth = 0;
    for (const other of bodies.keys()) {
      if (other !== loop && innermost(other, other) === loop) depth = Math.max(depth, inside(other) + 1);
    }
    return depth;
  };
  for (const loop of bodies.keys()) {
    const box = dag.node(clusterId(loop)) as { x?: number; y?: number; width?: number; height?: number } | undefined;
    if (typeof box?.x !== 'number' || typeof box.y !== 'number'
      || typeof box.width !== 'number' || typeof box.height !== 'number') continue;
    const pad = FRAME_PAD * (1 + inside(loop));
    frames.push({
      loop,
      x: box.x - box.width / 2 - pad,
      y: box.y - box.height / 2 - pad,
      width: box.width + 2 * pad,
      height: box.height + 2 * pad,
    });
  }
  // The larger frame first: it is drawn first, so the ones inside it lie on top of it.
  frames.sort((a, b) => b.width * b.height - a.width * a.height);
  return { positions, frames };
}

/** The positions of {@link layoutGraph}, for a caller that has no use for the frames. */
export function layoutPositions(
  graph: WorkflowGraph,
  direction: GraphDirection,
): Map<string, NodePosition> {
  return layoutGraph(graph, direction).positions;
}

/** Where a node goes when the layout has no place for it: a column down the left. */
export function fallbackPosition(index: number): NodePosition {
  return { x: 40, y: 40 + index * (NODE_H + 30) };
}
