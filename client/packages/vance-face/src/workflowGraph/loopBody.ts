import type { WorkflowEdge, WorkflowStateNode } from './workflowGraphCodec';

/**
 * The body of a `for_each_task`, as the drawing sees it: the states reachable from the loop's `each` arrow without
 * passing the loop itself. It is the same rule as the server's `MagratheaLoopBody`; the two are kept in step by
 * tests that run the same workflows through both. The editor needs it to say which loop an `end` state ends, and to
 * flag an `end` that sits in no body.
 *
 * Spec: `specification/public/workflows.md` §3.9.
 */
export function loopBody(
  states: readonly WorkflowStateNode[],
  edges: readonly WorkflowEdge[],
  loop: string,
): Set<string> {
  const body = new Set<string>();
  const entry = edges.find((e) => e.source === loop && e.kind === 'on' && e.key === 'each')?.target;
  if (!entry || entry === loop) return body;

  const known = new Set(states.map((s) => s.name));
  const queue = [entry];
  while (queue.length > 0) {
    const name = queue.shift() as string;
    if (name === loop || body.has(name) || !known.has(name)) continue;
    body.add(name);
    for (const e of edges) if (e.source === name) queue.push(e.target);
  }
  return body;
}

/** What {@link enclosingLoop} found. */
export type EnclosingLoop =
  /** The state is in no loop body. */
  | { kind: 'none' }
  /** The innermost loop whose body holds the state. */
  | { kind: 'loop'; loop: string }
  /** The state is in the bodies of two loops that do not lie one inside the other. */
  | { kind: 'ambiguous'; inner: string; outer: string };

/** The loop an `end` state ends: the one with the smallest body that holds it, which lies wholly inside the others. */
export function enclosingLoop(
  states: readonly WorkflowStateNode[],
  edges: readonly WorkflowEdge[],
  name: string,
): EnclosingLoop {
  const holding = states
    .filter((s) => s.type === 'for_each_task' && !s.missing)
    .map((s) => ({ loop: s.name, body: loopBody(states, edges, s.name) }))
    .filter((l) => l.body.has(name))
    .sort((a, b) => a.body.size - b.body.size);
  if (holding.length === 0) return { kind: 'none' };
  for (let i = 1; i < holding.length; i++) {
    const inner = holding[i - 1];
    const outer = holding[i];
    if (inner.body.size >= outer.body.size || ![...inner.body].every((s) => outer.body.has(s))) {
      return { kind: 'ambiguous', inner: inner.loop, outer: outer.loop };
    }
  }
  return { kind: 'loop', loop: holding[0].loop };
}
