import type { Edge, Node } from '@vue-flow/core';
import { fallbackPosition, type NodePosition } from './layout';
import type {
  WorkflowEdge,
  WorkflowEdgeKind,
  WorkflowGraph,
  WorkflowStateNode,
} from './workflowGraphCodec';

/** The look of one edge: the failure lane is dashed and warm, branches take the accent. */
export function edgeStyle(kind: WorkflowEdgeKind, dangling: boolean): Record<string, string> {
  if (dangling) return { stroke: 'var(--color-error)', strokeWidth: '1.5', strokeDasharray: '2 3' };
  switch (kind) {
    // `catch:` is the failure lane — dashed and warm, so an eye scanning
    // the diagram separates error routing from the happy path at once.
    case 'catch':
      return { stroke: 'var(--color-warning)', strokeWidth: '1.5', strokeDasharray: '6 4' };
    case 'condition':
      return { stroke: 'var(--color-primary)', strokeWidth: '1.5' };
    case 'else':
      return { stroke: 'var(--color-primary)', strokeWidth: '1.5', strokeDasharray: '6 4' };
    default:
      return { stroke: 'var(--color-base-content)', strokeWidth: '1.5' };
  }
}

/** Colour band per task type — the node's left edge. */
export function typeColor(state: WorkflowStateNode): string {
  if (state.missing || !state.knownType) return 'var(--color-error)';
  switch (state.type) {
    case 'agent_task': return 'var(--color-primary)';
    case 'gate_task': return 'var(--color-warning)';
    case 'timer_task': return 'var(--color-info)';
    // A neutral, readable grey: --color-secondary is so pale on a white node that
    // the type label and the node's left edge all but vanish.
    case 'condition_task': return 'color-mix(in oklab, var(--color-base-content) 62%, transparent)';
    case 'workflow_task': return 'var(--color-accent)';
    case 'terminal':
      return state.terminalOutcome === 'failure'
        ? 'var(--color-error)'
        : 'var(--color-success)';
    default: return 'var(--color-base-content)';
  }
}

/** One vue-flow edge per workflow edge, drawn the same wherever the graph shows up. */
export function toFlowEdges(edges: WorkflowEdge[]): Edge[] {
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: edge.label,
    type: 'smoothstep',
    markerEnd: 'arrowclosed',
    animated: false,
    class: `wf-edge wf-edge--${edge.kind}${edge.dangling ? ' wf-edge--dangling' : ''}`,
    style: edgeStyle(edge.kind, edge.dangling),
    labelBgStyle: { fill: 'var(--color-base-100)' },
    labelStyle: { fontSize: '11px', fill: 'var(--color-base-content)' },
  }));
}

/**
 * One vue-flow node per state, placed by the derived layout. The caller adds
 * what only it knows (run overlay, breakpoints, selection) through
 * {@code dataOf} and sets the interaction flags through {@code flags}.
 */
export function toFlowNodes(
  graph: WorkflowGraph,
  positions: Map<string, NodePosition>,
  dataOf: (state: WorkflowStateNode) => Record<string, unknown>,
  flags: Pick<Node, 'draggable' | 'selectable' | 'connectable'>,
): Node[] {
  return graph.states.map((state, idx) => ({
    id: state.name,
    type: 'state',
    position: positions.get(state.name) ?? fallbackPosition(idx),
    data: { state, ...dataOf(state) },
    ...flags,
  }));
}
