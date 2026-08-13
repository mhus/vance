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

/** The nine task types a state's `type:` may name (spec §3). */
export const WORKFLOW_TASK_TYPES = [
  'agent_task',
  'shell_task',
  'script_task',
  'tool_task',
  'gate_task',
  'timer_task',
  'condition_task',
  'workflow_task',
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
  /** `type: terminal` — ends the run. */
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
  /** True when {@link target} is a {@link WorkflowStateNode.missing} node. */
  dangling: boolean;
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
    return { start: null, parameters: [], states: [], edges: [], problems: ['empty document'] };
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
      isTerminal: type === 'terminal',
      terminalOutcome: type === 'terminal' ? optionalString(raw.outcome) : undefined,
      missing: false,
    });

    collectEdges(name, raw, edges);
  }

  // Second pass: any edge target that was never declared becomes a ghost
  // node, so the drawing shows the break.
  const declaredSet = new Set(declared);
  for (const edge of edges) {
    if (declaredSet.has(edge.target)) continue;
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
        `state '${edge.source}' points to undeclared state '${edge.target}'`,
      );
    }
  }

  return {
    start,
    description: optionalString(root.description),
    version: optionalString(root.version),
    parameters: parseParameters(root.parameters),
    states: [...states, ...missing.values()],
    edges,
    problems,
  };
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
  for (const entry of transitions) {
    if (!isRecord(entry)) continue;
    if ('else' in entry) {
      const target = optionalString(entry.else);
      if (target) out.push(edge(source, target, 'else', 'else', out.length));
      continue;
    }
    const target = optionalString(entry.to);
    if (!target) continue;
    const condition = optionalString(entry.if) ?? '';
    out.push(edge(source, target, truncate(condition), 'condition', out.length));
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
    out.push(edge(source, target, outcome, kind, out.length));
  }
}

function edge(
  source: string,
  target: string,
  label: string,
  kind: WorkflowEdgeKind,
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
    case 'workflow_task':
      return optionalString(raw.workflow);
    case 'timer_task':
      return optionalString(raw.duration);
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
