// Read-only projection of a `kind: vance-workflow` document (a Magrathea
// workflow) onto a node/edge graph for the flow renderer.
//
// This is NOT a round-trip codec: the kind-registry entry keeps the raw
// YAML as its model (identity parse/serialize) so the Edit tab stays a
// byte-exact CodeEditor. The projection here exists purely so the View
// tab can draw the state machine.
//
// It deliberately mirrors only what a picture needs — states, their task
// type, and where each outcome leads. Authoritative validation lives in
// `MagratheaWorkflowLoader` on the server (surfaced through the
// `vance-workflow` KindHandler); the `problems` list below is the subset
// a reader can *see* in the drawing, reported so a dangling arrow reads
// as an error rather than as a missing line.
//
// Spec: `specification/public/workflows.md` §2 + §3.

import { parseYamlBody } from '@vance/shared';
import { enclosingLoop } from './loopBody';

/** The eleven task types a state's `type:` may name (spec §3). */
export const WORKFLOW_TASK_TYPES = [
  'agent_task',
  'shell_task',
  'script_task',
  'tool_task',
  'gate_task',
  'timer_task',
  'condition_task',
  'workflow_task',
  'for_each_task',
  'end',
  'terminal',
] as const;

export type WorkflowTaskType = (typeof WORKFLOW_TASK_TYPES)[number];

/** How a state got into the graph, and how it should read. */
export interface WorkflowStateNode {
  /** State name — the key under `states:`, unique, used as the node id. */
  name: string;
  /** Normalised `type:` value (lower-case, dashes → underscores). */
  type: string;
  /** True when {@link type} is one of {@link WORKFLOW_TASK_TYPES}. */
  knownType: boolean;
  /** One-line summary of the type-specific payload (recipe, tool, …). */
  detail?: string;
  description?: string;
  storeAs?: string;
  hasRetry: boolean;
  isStart: boolean;
  /** `type: terminal` or `type: end` — a state with no way on of its own (the run ends, or a loop pass does). */
  isTerminal: boolean;
  /** `outcome:` of a terminal state (`success` / `failure`). */
  terminalOutcome?: string;
  /**
   * True for a placeholder node: something transitions here but no such
   * entry exists under `states:`. Drawn as a ghost so the broken edge is
   * visible instead of silently dropped.
   */
  missing: boolean;
}

/** Which block of the state definition produced an edge. */
export type WorkflowEdgeKind = 'on' | 'catch' | 'condition' | 'else';

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  /** Outcome name, error kind, or condition expression. */
  label: string;
  kind: WorkflowEdgeKind;
  /**
   * Where the edge sits inside its source state, so an editor can address it:
   * the untruncated outcome / error kind for {@code on} and {@code catch},
   * the position in {@code transitions:} (as a string) for {@code condition}
   * and {@code else}.
   */
  key: string;
  /** True when {@link target} is a {@link WorkflowStateNode.missing} node. */
  dangling: boolean;
  /** True for the {@code each} arrow of a {@code for_each_task}: the one that leads into the loop body. */
  intoLoop?: boolean;
}

/** One entry of the workflow's `parameters:` block. */
export interface WorkflowParameter {
  name: string;
  /** Declared type. Documentation only — v1 validates nothing but presence. */
  type: string;
  required: boolean;
  defaultValue?: unknown;
}

export interface WorkflowGraph {
  /** `start:` as written, or null when absent / not a string. */
  start: string | null;
  description?: string;
  version?: string;
  /** Declared caller parameters, in document order. */
  parameters: WorkflowParameter[];
  states: WorkflowStateNode[];
  edges: WorkflowEdge[];
  /**
   * Where the author put states, from the optional top-level `layout:` block ({@code plan: [120, 40]}): state name →
   * top-left corner. Empty when the workflow is arranged automatically. The engine ignores it.
   */
  layout: Record<string, { x: number; y: number }>;
  /** Reader-visible structural problems, in document order. */
  problems: string[];
}

/** Max characters of a condition expression kept on an edge label. */
const LABEL_MAX = 44;

/**
 * Project a workflow YAML body onto {@link WorkflowGraph}.
 *
 * Never throws: a syntax error, a non-mapping root or a missing
 * `states:` block come back as an empty graph carrying a `problems`
 * entry. A renderer that cannot draw anything should still be able to
 * say why.
 */
export function parseWorkflowGraph(body: string): WorkflowGraph {
  const problems: string[] = [];

  if (!body.trim()) {
    return { start: null, parameters: [], states: [], edges: [], layout: {}, problems: ['empty document'] };
  }

  let root: Record<string, unknown>;
  try {
    // parseYamlBody uses js-yaml's JSON schema, so `on:` / `no:` stay
    // strings — the same YAML-1.2 boolean semantics the server parser
    // installs for exactly this key (spec §2.3). It also lifts `$meta`.
    root = parseYamlBody(body);
  } catch (e) {
    return {
      start: null,
      parameters: [],
      states: [],
      edges: [],
      layout: {},
      problems: [e instanceof Error ? e.message : String(e)],
    };
  }

  const start = typeof root.start === 'string' && root.start.trim() ? root.start : null;
  if (!start) problems.push("missing required field 'start'");

  const rawStates = root.states;
  if (!isRecord(rawStates)) {
    problems.push("missing required field 'states'");
    return {
      start,
      description: optionalString(root.description),
      version: optionalString(root.version),
      parameters: parseParameters(root.parameters),
      states: [],
      edges: [],
      layout: parseLayout(root.layout),
      problems,
    };
  }

  const declared = Object.keys(rawStates);
  if (start && !declared.includes(start)) {
    problems.push(`'start: ${start}' does not match any state`);
  }

  const states: WorkflowStateNode[] = [];
  const edges: WorkflowEdge[] = [];
  const missing = new Map<string, WorkflowStateNode>();

  for (const name of declared) {
    const raw = rawStates[name];
    if (!isRecord(raw)) {
      problems.push(`state '${name}' is not a mapping`);
      continue;
    }
    const type = normaliseType(raw.type);
    const knownType = (WORKFLOW_TASK_TYPES as readonly string[]).includes(type);
    if (!knownType) {
      problems.push(
        type
          ? `state '${name}' has unknown type '${type}'`
          : `state '${name}' is missing required 'type'`,
      );
    }

    states.push({
      name,
      type,
      knownType,
      detail: detailFor(type, raw),
      description: optionalString(raw.description),
      storeAs: optionalString(raw.storeAs),
      hasRetry: isRecord(raw.retry),
      isStart: name === start,
      isTerminal: type === 'terminal' || type === 'end',
      terminalOutcome: type === 'terminal' ? optionalString(raw.outcome) : undefined,
      missing: false,
    });

    const before = edges.length;
    collectEdges(name, raw, edges);
    if (type === 'for_each_task') {
      markLoopEntry(edges, before);
      for (const outcome of ['each', 'done']) {
        if (!edges.slice(before).some((e) => e.kind === 'on' && e.key === outcome)) {
          problems.push(`state '${name}' (for_each_task) needs 'on: ${outcome}: <state>'`);
        }
      }
    }
  }

  // Second pass: any edge target without a node becomes a ghost node, so the
  // drawing shows the break. The set is built from the states that actually
  // made it into a node, not from the declared keys: an entry skipped above
  // (not a mapping) is declared but has no node, and an edge into it would
  // otherwise be handed to VueFlow with a target that does not exist — VueFlow
  // drops such an edge silently, which is exactly what the ghost avoids.
  const nodeNames = new Set(states.map((s) => s.name));
  const declaredSet = new Set(declared);
  for (const edge of edges) {
    if (nodeNames.has(edge.target)) continue;
    edge.dangling = true;
    if (!missing.has(edge.target)) {
      missing.set(edge.target, {
        name: edge.target,
        type: '',
        knownType: false,
        hasRetry: false,
        isStart: false,
        isTerminal: false,
        missing: true,
      });
      problems.push(
        declaredSet.has(edge.target)
          ? `state '${edge.source}' points to unusable state '${edge.target}'`
          : `state '${edge.source}' points to undeclared state '${edge.target}'`,
      );
    }
  }

  annotateEnds(states, edges, problems);

  return {
    start,
    description: optionalString(root.description),
    version: optionalString(root.version),
    parameters: parseParameters(root.parameters),
    states: [...states, ...missing.values()],
    edges,
    layout: parseLayout(root.layout),
    problems,
  };
}

/**
 * An {@code end} state ends the loop whose body it is part of: the node says which, and a problem is listed when
 * there is none, or when it is unclear which — the server would refuse the workflow for the same reason.
 */
function annotateEnds(
  states: WorkflowStateNode[],
  edges: readonly WorkflowEdge[],
  problems: string[],
): void {
  for (const state of states) {
    if (state.type !== 'end') continue;
    const found = enclosingLoop(states, edges, state.name);
    if (found.kind === 'loop') {
      state.detail = `↺ ${found.loop}`;
    } else if (found.kind === 'none') {
      problems.push(`state '${state.name}' (end) is not in the body of a for_each_task`);
    } else {
      problems.push(
        `state '${state.name}' (end) is in the bodies of the loops '${found.inner}' and '${found.outer}', `
          + 'which do not lie one inside the other',
      );
    }
  }
}

/**
 * The `layout:` block: state name → `[x, y]`. An entry that is not a pair of finite numbers is skipped — a
 * half-written position should not hide the rest, and the state is then placed automatically.
 */
function parseLayout(raw: unknown): Record<string, { x: number; y: number }> {
  const out: Record<string, { x: number; y: number }> = {};
  if (!isRecord(raw)) return out;
  for (const [name, at] of Object.entries(raw)) {
    if (Array.isArray(at) && at.length === 2 && at.every((n) => typeof n === 'number' && Number.isFinite(n))) {
      out[name] = { x: at[0] as number, y: at[1] as number };
    }
  }
  return out;
}

/**
 * The `parameters:` block, in document order — what the Start form has
 * to ask for. Entries that are not mappings are skipped rather than
 * reported: an unusable parameter declaration is a problem for the
 * server-side parser, not something the drawing can show.
 */
function parseParameters(raw: unknown): WorkflowParameter[] {
  if (!isRecord(raw)) return [];
  const out: WorkflowParameter[] = [];
  for (const [name, spec] of Object.entries(raw)) {
    if (!isRecord(spec)) continue;
    out.push({
      name,
      type: optionalString(spec.type) ?? 'string',
      required: spec.required === true,
      defaultValue: spec.default,
    });
  }
  return out;
}

/** Read the `on:` / `catch:` / `transitions:` blocks of one state. */
function collectEdges(source: string, raw: Record<string, unknown>, out: WorkflowEdge[]): void {
  pushMapEdges(source, raw.on, 'on', out);
  pushMapEdges(source, raw.catch, 'catch', out);

  const transitions = raw.transitions;
  if (!Array.isArray(transitions)) return;
  for (const [index, entry] of transitions.entries()) {
    if (!isRecord(entry)) continue;
    if ('else' in entry) {
      const target = optionalString(entry.else);
      if (target) out.push(edge(source, target, 'else', 'else', String(index), out.length));
      continue;
    }
    const target = optionalString(entry.to);
    if (!target) continue;
    const condition = optionalString(entry.if) ?? '';
    out.push(edge(source, target, truncate(condition), 'condition', String(index), out.length));
  }
}

/** The {@code each} arrow of a loop state leads into the body; the drawing sets it apart. */
function markLoopEntry(edges: WorkflowEdge[], from: number): void {
  for (const e of edges.slice(from)) {
    if (e.kind === 'on' && e.key === 'each') e.intoLoop = true;
  }
}

function pushMapEdges(
  source: string,
  raw: unknown,
  kind: 'on' | 'catch',
  out: WorkflowEdge[],
): void {
  if (!isRecord(raw)) return;
  for (const [outcome, target] of Object.entries(raw)) {
    if (typeof target !== 'string' || !target.trim()) continue;
    out.push(edge(source, target, outcome, kind, outcome, out.length));
  }
}

function edge(
  source: string,
  target: string,
  label: string,
  kind: WorkflowEdgeKind,
  key: string,
  index: number,
): WorkflowEdge {
  return {
    // The index keeps ids unique when two branches share source+target
    // (e.g. two conditions routing to the same state).
    id: `${kind}:${source}->${target}:${index}`,
    source,
    target,
    label,
    kind,
    key,
    dangling: false,
  };
}

/**
 * The one line under a node's name: whatever identifies *what this state
 * actually does*, picked per task type (spec §3.1–3.8).
 */
function detailFor(type: string, raw: Record<string, unknown>): string | undefined {
  switch (type) {
    case 'agent_task':
      return optionalString(raw.recipe);
    case 'tool_task':
      return optionalString(raw.tool);
    case 'shell_task':
      return truncate(optionalString(raw.run) ?? '');
    case 'script_task':
      return optionalString(raw.path);
    case 'workflow_task': {
      const workflow = optionalString(raw.workflow);
      // `items:` makes it a for-each: one sub-run per element.
      return raw.items === undefined || raw.items === null ? workflow : `${workflow ?? '?'} · for each`;
    }
    case 'timer_task':
      return optionalString(raw.duration);
    case 'for_each_task':
      return optionalString(raw.items);
    case 'gate_task':
      return isRecord(raw.inbox) ? optionalString(raw.inbox.kind) : undefined;
    case 'condition_task':
      return Array.isArray(raw.transitions)
        ? `${raw.transitions.length} branch(es)`
        : undefined;
    case 'terminal':
      return optionalString(raw.outcome);
    default:
      return undefined;
  }
}

/** Lower-case, dashes → underscores — the server's normalisation. */
function normaliseType(raw: unknown): string {
  if (typeof raw !== 'string') return '';
  return raw.trim().toLowerCase().replace(/-/g, '_');
}

function truncate(value: string): string {
  const flat = value.replace(/\s+/g, ' ').trim();
  return flat.length > LABEL_MAX ? `${flat.slice(0, LABEL_MAX - 1)}…` : flat;
}

function optionalString(raw: unknown): string | undefined {
  if (typeof raw === 'string' && raw.trim()) return raw;
  if (typeof raw === 'number' || typeof raw === 'boolean') return String(raw);
  return undefined;
}

function isRecord(raw: unknown): raw is Record<string, unknown> {
  return typeof raw === 'object' && raw !== null && !Array.isArray(raw);
}
