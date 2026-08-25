import { describe, expect, it } from 'vitest';
import { actionAllowed, describeView } from './agentApi';
import type { RenderedView } from './generated/bistromath/RenderedView';
import type { ViewNode } from './generated/bistromath/ViewNode';

function node(over: Partial<ViewNode>): ViewNode {
  return {
    type: 'text',
    columns: [],
    options: [],
    fields: [],
    agent: false,
    on: {},
    children: [],
    ...over,
  } as ViewNode;
}

function view(root: ViewNode): RenderedView {
  return { handle: 'main', path: 'apps/x/main.yaml', notes: [], root } as RenderedView;
}

const CLICK = { kind: 'SCRIPT', function: 'run', raw: 'run()' } as never;

describe('describeView', () => {

  it('collects state keys from `from` and `show`, once each', () => {
    const v = view(node({
      type: 'page',
      children: [
        node({ type: 'table', from: 'rows' }),
        node({ type: 'input', from: 'name', show: 'editing' }),
        node({ type: 'text', from: 'rows' }),
      ],
    }));

    expect(describeView('apps/x', v, ['main']).stateKeys).toEqual(['rows', 'name', 'editing']);
  });

  it('lists only widgets that have an action, with their permission', () => {
    const v = view(node({
      type: 'page',
      children: [
        node({ type: 'button', id: 'save', label: 'Save', on: { click: CLICK }, agent: true }),
        node({ type: 'button', id: 'wipe', label: 'Delete all', on: { click: CLICK } }),
        node({ type: 'text', id: 'hint', text: 'no action here' }),
      ],
    }));

    expect(describeView('apps/x', v, []).actions).toEqual([
      { id: 'save', label: 'Save', type: 'button', agent: true },
      { id: 'wipe', label: 'Delete all', type: 'button', agent: false },
    ]);
  });

  it('reports a denied action rather than hiding it', () => {
    // Hiding it would make the agent claim the button does not exist. Saying
    // "there, but not yours" is the honest answer and the one a person can act
    // on — they can open it in the document.
    const v = view(node({ type: 'button', id: 'wipe', on: { click: CLICK } }));
    expect(describeView('apps/x', v, []).actions[0].agent).toBe(false);
  });

  it('survives a view that is not loaded yet', () => {
    expect(describeView('apps/x', null, [])).toEqual({
      app: 'apps/x',
      view: null,
      viewLabel: null,
      stateKeys: [],
      actions: [],
      views: [],
    });
  });

  it('walks nested widgets', () => {
    const v = view(node({
      type: 'page',
      children: [node({
        type: 'card',
        children: [node({ type: 'button', id: 'deep', on: { click: CLICK }, agent: true })],
      })],
    }));
    expect(describeView('apps/x', v, []).actions.map((a) => a.id)).toEqual(['deep']);
  });
});

describe('actionAllowed', () => {

  it('is true only with an explicit agent flag', () => {
    const v = view(node({
      type: 'page',
      children: [
        node({ type: 'button', id: 'ok', on: { click: CLICK }, agent: true }),
        node({ type: 'button', id: 'no', on: { click: CLICK } }),
      ],
    }));
    expect(actionAllowed(v, 'ok')).toBe(true);
    expect(actionAllowed(v, 'no')).toBe(false);
  });

  it('is false for a widget with the flag but no action', () => {
    // The parser refuses this combination, but the gate must not depend on the
    // parser having been the thing that produced the tree.
    const v = view(node({ type: 'text', id: 'x', agent: true }));
    expect(actionAllowed(v, 'x')).toBe(false);
  });

  it('is false for an unknown id and for no view at all', () => {
    expect(actionAllowed(view(node({ type: 'page' })), 'ghost')).toBe(false);
    expect(actionAllowed(null, 'anything')).toBe(false);
  });

  it('does not accept a truthy non-boolean as permission', () => {
    // The one flag that hands out a button: a permissive coercion here is the
    // wrong reflex, and YAML is happy to produce a string.
    const v = view(node({
      type: 'button', id: 'x', on: { click: CLICK },
      agent: 'true' as unknown as boolean,
    }));
    expect(actionAllowed(v, 'x')).toBe(false);
  });
});
