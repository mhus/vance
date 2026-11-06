/**
 * Changes to a workflow definition, as text in → text out.
 *
 * The editor never builds a workflow from a model and dumps it: that would
 * drop the author's comments, key order and formatting. Every function here
 * parses the text into a {@code yaml} document, changes exactly the node it
 * is about and prints the document again, so everything else — comments,
 * unknown fields, quoting — comes back as it was.
 * One thing the printer does move: a comment at the end of a {@code key:} line (a
 * map key with nothing after it) lands on the line below. Comments on their own
 * line and after a value stay exactly where they are.
 *
 * Pure functions with no Vue in them. They refuse what would leave the
 * workflow structurally broken (a transition to a state that does not exist,
 * two states of one name); whether the result is a workflow the server will
 * run stays the server's call — see the {@code vance-workflow} kind handler.
 *
 * Spec: {@code specification/public/workflows.md} §2, §3.6, §4.
 */
import {
  type Document,
  isMap,
  isScalar,
  isSeq,
  parseDocument,
  type YAMLMap,
  type YAMLSeq,
} from 'yaml';
import type { WorkflowEdgeKind } from '@/workflowGraph/workflowGraphCodec';

/** A change that cannot be made; the message is meant for the person editing. */
export class WorkflowEditError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'WorkflowEditError';
  }
}

/** Where one transition sits: the state it leaves and its place inside that state. */
export interface EdgeRef {
  source: string;
  kind: WorkflowEdgeKind;
  /** Outcome / error kind for {@code on} and {@code catch}; index in {@code transitions} for the others. */
  key: string;
}

const STATE_NAME = /^[A-Za-z0-9_][A-Za-z0-9_-]*$/;

/** Print options: never re-wrap what the author wrote on one line. */
const PRINT = { lineWidth: 0 } as const;

// ── document access ──────────────────────────────────────────────

function load(text: string): Document {
  const doc = parseDocument(text);
  if (doc.errors.length > 0) {
    throw new WorkflowEditError(doc.errors[0].message);
  }
  if (doc.contents !== null && !isMap(doc.contents)) {
    throw new WorkflowEditError('The workflow is not a mapping.');
  }
  return doc;
}

function print(doc: Document): string {
  return doc.toString(PRINT);
}

function statesOf(doc: Document): YAMLMap | null {
  const states = doc.get('states', true);
  return isMap(states) ? states : null;
}

function stateOf(doc: Document, name: string): YAMLMap {
  const state = statesOf(doc)?.get(name, true);
  if (!isMap(state)) throw new WorkflowEditError(`There is no state '${name}'.`);
  return state;
}

function hasState(doc: Document, name: string): boolean {
  return isMap(statesOf(doc)?.get(name, true));
}

function scalarText(node: unknown): string | null {
  return isScalar(node) && typeof node.value === 'string' ? node.value : null;
}

function checkName(name: string): void {
  if (!STATE_NAME.test(name)) {
    throw new WorkflowEditError(
      `'${name}' is not a usable state name: letters, digits, '_' and '-' only.`,
    );
  }
}

/** Every {@code on} / {@code catch} map and {@code transitions} list of every state. */
function eachRouting(
  doc: Document,
  visit: (kind: 'map' | 'seq', node: YAMLMap | YAMLSeq, state: YAMLMap) => void,
): void {
  const states = statesOf(doc);
  if (!states) return;
  for (const pair of states.items) {
    const state = pair.value;
    if (!isMap(state)) continue;
    for (const key of ['on', 'catch']) {
      const routes = state.get(key, true);
      if (isMap(routes)) visit('map', routes, state);
    }
    const transitions = state.get('transitions', true);
    if (isSeq(transitions)) visit('seq', transitions, state);
  }
}

/** Drop a routing block that lost its last entry, so no empty {@code on: {}} stays behind. */
function pruneEmpty(state: YAMLMap): void {
  for (const key of ['on', 'catch', 'transitions']) {
    const node = state.get(key, true);
    if ((isMap(node) || isSeq(node)) && node.items.length === 0) state.delete(key);
  }
}

/**
 * Point every transition that names {@code from} at {@code to}, or remove it
 * when {@code to} is null.
 */
function retargetAll(doc: Document, from: string, to: string | null): void {
  eachRouting(doc, (kind, node, state) => {
    if (kind === 'map') {
      const map = node as YAMLMap;
      for (const pair of [...map.items]) {
        if (scalarText(pair.value) !== from) continue;
        if (to === null) map.items.splice(map.items.indexOf(pair), 1);
        else if (isScalar(pair.value)) pair.value.value = to;
      }
    } else {
      const seq = node as YAMLSeq;
      for (const item of [...seq.items]) {
        if (!isMap(item)) continue;
        for (const field of ['to', 'else']) {
          if (scalarText(item.get(field, true)) !== from) continue;
          const target = item.get(field, true);
          if (to === null) seq.items.splice(seq.items.indexOf(item), 1);
          else if (isScalar(target)) target.value = to;
        }
      }
    }
    pruneEmpty(state);
  });
}

// ── states ───────────────────────────────────────────────────────

/** A state name not taken yet, built from {@code base}: {@code shell_task}, {@code shell_task_2}, … */
export function freeStateName(text: string, base: string): string {
  const doc = load(text);
  const root = base.replace(/[^A-Za-z0-9_-]+/g, '_').replace(/^[^A-Za-z0-9_]+/, '') || 'state';
  if (!hasState(doc, root)) return root;
  for (let n = 2; ; n++) {
    const candidate = `${root}_${n}`;
    if (!hasState(doc, candidate)) return candidate;
  }
}

/**
 * Add a state at the end of {@code states:}. {@code fields} is the state's
 * body and must carry {@code type}. The first state of a workflow without a
 * {@code start:} becomes the start.
 */
export function addState(text: string, name: string, fields: Record<string, unknown>): string {
  checkName(name);
  if (typeof fields.type !== 'string' || !fields.type) {
    throw new WorkflowEditError("A state needs a 'type'.");
  }
  const doc = load(text);
  if (hasState(doc, name)) throw new WorkflowEditError(`There is already a state '${name}'.`);
  doc.setIn(['states', name], doc.createNode(fields));
  if (scalarText(doc.get('start', true)) === null) doc.set('start', name);
  return print(doc);
}

/**
 * Remove a state and every transition that led to it. When it was the start,
 * the first remaining state becomes the start (none left: {@code start:} goes).
 */
export function removeState(text: string, name: string): string {
  const doc = load(text);
  stateOf(doc, name);
  doc.deleteIn(['states', name]);
  retargetAll(doc, name, null);
  if (scalarText(doc.get('start', true)) === name) {
    const first = statesOf(doc)?.items.map((p) => scalarText(p.key)).find((n) => n !== null);
    if (first) doc.set('start', first);
    else doc.delete('start');
  }
  return print(doc);
}

/** Rename a state in place — key order and comments stay — and follow it in {@code start} and all transitions. */
export function renameState(text: string, from: string, to: string): string {
  checkName(to);
  if (from === to) return text;
  const doc = load(text);
  stateOf(doc, from);
  if (hasState(doc, to)) throw new WorkflowEditError(`There is already a state '${to}'.`);
  const pair = statesOf(doc)!.items.find((p) => scalarText(p.key) === from)!;
  (pair.key as { value: string }).value = to;
  const start = doc.get('start', true);
  if (scalarText(start) === from && isScalar(start)) start.value = to;
  retargetAll(doc, from, to);
  return print(doc);
}

/** Make {@code name} the state the run starts in. */
export function setStart(text: string, name: string): string {
  const doc = load(text);
  stateOf(doc, name);
  doc.set('start', name);
  return print(doc);
}

/**
 * Set a field of a state by path ({@code ['params', 'prompt']}); {@code undefined}
 * removes it, and a map it leaves empty. Intermediate maps are created.
 */
export function setStateField(
  text: string,
  state: string,
  path: (string | number)[],
  value: unknown,
): string {
  if (path.length === 0) throw new WorkflowEditError('A field path is needed.');
  const doc = load(text);
  stateOf(doc, state);
  const full = ['states', state, ...path];
  if (value === undefined) {
    doc.deleteIn(full);
    // A map that lost its last key goes too: no `params: {}` left behind.
    for (let depth = full.length - 1; depth > 2; depth--) {
      const parent = doc.getIn(full.slice(0, depth), true);
      if (isMap(parent) && parent.items.length === 0) doc.deleteIn(full.slice(0, depth));
      else break;
    }
  } else {
    doc.setIn(full, doc.createNode(value));
  }
  return print(doc);
}

/**
 * Replace the keys of the map at {@code path} inside a state ({@code []} is the
 * state itself, {@code ['params']} its parameters) that the caller does not own
 * with {@code extras}: keys missing from it are removed, new ones added,
 * changed ones replaced; a key whose value did not change is not touched, so
 * its formatting stays. {@code owned} are the keys the caller manages
 * elsewhere — {@code extras} may not name them. A map left empty is removed,
 * and one that is needed is created.
 */
export function setMapExtras(
  text: string,
  state: string,
  path: string[],
  extras: Record<string, unknown>,
  owned: ReadonlySet<string>,
): string {
  const doc = load(text);
  stateOf(doc, state);
  for (const key of Object.keys(extras)) {
    if (owned.has(key)) throw new WorkflowEditError(`'${key}' has its own field and cannot be set here.`);
  }
  const location = ['states', state, ...path];
  let map = path.length === 0 ? stateOf(doc, state) : doc.getIn(location, true);
  if (!isMap(map)) {
    if (Object.keys(extras).length === 0) return text;
    doc.setIn(location, doc.createNode({}));
    map = doc.getIn(location, true);
  }
  const target = map as YAMLMap;
  for (const pair of [...target.items]) {
    const key = scalarText(pair.key);
    if (key !== null && !owned.has(key) && !(key in extras)) target.items.splice(target.items.indexOf(pair), 1);
  }
  for (const [key, value] of Object.entries(extras)) {
    const current = target.get(key, true);
    const same = current !== undefined
      && JSON.stringify((current as { toJSON?: () => unknown }).toJSON?.() ?? current) === JSON.stringify(value);
    if (!same) target.set(key, doc.createNode(value));
  }
  if (path.length > 0 && target.items.length === 0) doc.deleteIn(location);
  return print(doc);
}

/** {@link setMapExtras} on the state itself: the fields no form control owns. */
export function setStateExtras(
  text: string,
  state: string,
  extras: Record<string, unknown>,
  owned: ReadonlySet<string>,
): string {
  return setMapExtras(text, state, [], extras, owned);
}

// ── connections ──────────────────────────────────────────────────

interface Located {
  /** The map holding the edge's key ({@code on}/{@code catch}), or the item map in {@code transitions}. */
  holder: YAMLMap;
  /** Key whose value is the target: the outcome, or {@code to} / {@code else}. */
  field: string;
  /** Remove the edge from its source state. */
  remove(): void;
}

function locate(doc: Document, ref: EdgeRef): Located {
  const state = stateOf(doc, ref.source);
  const missing = new WorkflowEditError(`State '${ref.source}' has no such connection.`);
  if (ref.kind === 'on' || ref.kind === 'catch') {
    const routes = state.get(ref.kind, true);
    if (!isMap(routes) || !routes.has(ref.key)) throw missing;
    return {
      holder: routes,
      field: ref.key,
      remove: () => {
        routes.delete(ref.key);
        pruneEmpty(state);
      },
    };
  }
  const transitions = state.get('transitions', true);
  const item = isSeq(transitions) ? transitions.items[Number(ref.key)] : undefined;
  const field = ref.kind === 'else' ? 'else' : 'to';
  if (!isSeq(transitions) || !isMap(item) || !item.has(field)) throw missing;
  return {
    holder: item,
    field,
    remove: () => {
      transitions.items.splice(Number(ref.key), 1);
      pruneEmpty(state);
    },
  };
}

function isElse(item: unknown): boolean {
  return isMap(item) && item.has('else');
}

/**
 * Add a transition from {@code source} to {@code target}. {@code label} is the
 * outcome ({@code on}), the error kind ({@code catch}) or the condition
 * ({@code condition}); an {@code else} has none. A condition lands before the
 * state's {@code else}, which has to stay last.
 */
export function connect(
  text: string,
  edge: { source: string; kind: WorkflowEdgeKind; label?: string; target: string },
): string {
  const doc = load(text);
  const state = stateOf(doc, edge.source);
  if (!hasState(doc, edge.target)) {
    throw new WorkflowEditError(`There is no state '${edge.target}' to connect to.`);
  }
  const sourceType = scalarText(state.get('type', true))?.toLowerCase().replace(/-/g, '_');
  if (sourceType === 'terminal') {
    throw new WorkflowEditError(`'${edge.source}' is a terminal state; it ends the run and leads nowhere.`);
  }
  if (sourceType === 'end') {
    throw new WorkflowEditError(`'${edge.source}' is an end state; it ends a loop pass and leads nowhere.`);
  }
  if ((edge.kind === 'condition' || edge.kind === 'else') && sourceType !== 'condition_task') {
    throw new WorkflowEditError(
      `Only a condition_task has branches; '${edge.source}' is ${sourceType ?? 'untyped'}.`,
    );
  }
  const label = edge.label?.trim() ?? '';

  if (edge.kind === 'on' || edge.kind === 'catch') {
    if (!label) throw new WorkflowEditError('A connection needs an outcome.');
    const routes = state.get(edge.kind, true);
    if (isMap(routes) && routes.has(label)) {
      throw new WorkflowEditError(`'${label}' already leads somewhere in '${edge.source}'.`);
    }
    doc.setIn(['states', edge.source, edge.kind, label], edge.target);
    return print(doc);
  }

  if (edge.kind === 'condition' && !label) {
    throw new WorkflowEditError('A branch needs a condition.');
  }
  const existing = state.get('transitions', true);
  let seq: YAMLSeq;
  if (isSeq(existing)) {
    seq = existing;
  } else {
    seq = doc.createNode([]) as YAMLSeq;
    state.set('transitions', seq);
  }
  const last = seq.items[seq.items.length - 1];
  if (edge.kind === 'else') {
    if (seq.items.some(isElse)) {
      throw new WorkflowEditError(`'${edge.source}' already has an else.`);
    }
    seq.items.push(doc.createNode({ else: edge.target }));
  } else {
    const entry = doc.createNode({ if: label, to: edge.target });
    if (isElse(last)) seq.items.splice(seq.items.length - 1, 0, entry);
    else seq.items.push(entry);
  }
  return print(doc);
}

/** Remove a transition. */
export function disconnect(text: string, ref: EdgeRef): string {
  const doc = load(text);
  locate(doc, ref).remove();
  return print(doc);
}

/** Make an existing transition lead to another state. */
export function retarget(text: string, ref: EdgeRef, target: string): string {
  const doc = load(text);
  if (!hasState(doc, target)) {
    throw new WorkflowEditError(`There is no state '${target}' to connect to.`);
  }
  const { holder, field } = locate(doc, ref);
  holder.set(field, target);
  return print(doc);
}

/**
 * Change the outcome / error kind of an {@code on} or {@code catch} transition,
 * or the condition of a branch. The transition keeps its place. An {@code else}
 * has no label.
 */
export function relabel(text: string, ref: EdgeRef, label: string): string {
  const next = label.trim();
  if (!next) throw new WorkflowEditError('A label cannot be empty.');
  const doc = load(text);
  const { holder, field } = locate(doc, ref);
  if (ref.kind === 'else') throw new WorkflowEditError('An else has no condition.');
  if (ref.kind === 'condition') {
    holder.set('if', next);
    return print(doc);
  }
  if (next === ref.key) return text;
  if (holder.has(next)) {
    throw new WorkflowEditError(`'${next}' already leads somewhere in '${ref.source}'.`);
  }
  const pair = holder.items.find((p) => scalarText(p.key) === field)!;
  (pair.key as { value: string }).value = next;
  return print(doc);
}
