import { describe, expect, it } from 'vitest';
import { toFlowEdges } from './flowElements';
import type { WorkflowEdge } from './workflowGraphCodec';

function edge(id: string, source: string, target: string, label: string, kind: WorkflowEdge['kind']): WorkflowEdge {
  return { id, source, target, label, kind, key: label, dangling: false };
}

describe('toFlowEdges', () => {
  it('draws edges between the same two states as one edge with all labels', () => {
    const flow = toFlowEdges([
      edge('a', 'work', 'failed', 'technical_error', 'catch'),
      edge('b', 'work', 'failed', 'business_error', 'catch'),
      edge('c', 'work', 'done', 'success', 'on'),
    ]);
    expect(flow).toHaveLength(2);
    expect(flow[0]!.id).toBe('a');
    expect(flow[0]!.label).toBe('technical_error, business_error');
    expect(flow[1]!.label).toBe('success');
  });

  it('keeps opposite directions apart and drops a repeated label', () => {
    const flow = toFlowEdges([
      edge('a', 'x', 'y', 'ok', 'on'),
      edge('b', 'y', 'x', 'ok', 'on'),
      edge('c', 'x', 'y', 'ok', 'catch'),
    ]);
    expect(flow).toHaveLength(2);
    expect(flow[0]!.label).toBe('ok');
  });
});
