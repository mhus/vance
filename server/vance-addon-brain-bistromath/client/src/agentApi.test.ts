import { describe, expect, it } from 'vitest';
import { actionAllowed, describeView, snapshotView } from './agentApi';
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
      snapshot: '(no view loaded)',
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

describe('snapshotView', () => {

  const CLICK_RUN = { kind: 'SCRIPT', function: 'run', raw: 'main.js:run' } as never;

  function snap(root: ViewNode, state: Record<string, unknown> = {},
                hidden: (n: ViewNode) => boolean = () => false): string {
    return snapshotView(view(root), state, hidden);
  }

  it('says how a container arranges its children', () => {
    // The point of the annotation: nesting alone only tells a reader who
    // already knows that `row` means horizontal. An agent that does not will
    // report two stacked boxes as being side by side.
    const out = snap(node({
      type: 'page',
      children: [
        node({ type: 'row', children: [node({ type: 'text', text: 'a' }),
                                       node({ type: 'text', text: 'b' })] }),
        node({ type: 'column', children: [node({ type: 'text', text: 'c' })] }),
      ],
    }));

    expect(out).toContain('row (side by side: 2)');
    expect(out).toContain('column (stacked: 1)');
  });

  it('marks tabs as one at a time', () => {
    const out = snap(node({
      type: 'tabs',
      children: [node({ type: 'text', text: 'a' }), node({ type: 'text', text: 'b' })],
    }));
    expect(out).toContain('tabs (one at a time: 2)');
  });

  it('shows the id as the handle and the bound key with its value', () => {
    const out = snap(
      node({ type: 'page', children: [node({ type: 'input', id: 'nameField',
                                             label: 'Name', from: 'name' })] }),
      { name: 'Trillian' });

    expect(out).toContain('input #nameField "Name" ← name = "Trillian"');
  });

  it('says (not set) for a key nothing has written', () => {
    // Distinct from an empty string or an empty list — the difference between
    // "the program has not run" and "there is nothing".
    const out = snap(node({ type: 'table', id: 't', from: 'rows' }));
    expect(out).toContain('← rows = (not set)');
  });

  it('summarises a large value instead of printing it', () => {
    const rows = Array.from({ length: 40 }, (_, i) => ({ key: String(i), amount: i }));
    const out = snap(node({ type: 'table', id: 't', from: 'rows' }), { rows });

    expect(out).toContain('[40 entries: key, amount]');
    expect(out).not.toContain('"39"');
  });

  it('shortens a long string and says how long it was', () => {
    const out = snap(node({ type: 'markdown', id: 'm', from: 'body' }),
                     { body: 'x'.repeat(500) });

    expect(out).toMatch(/\(500 chars\)/);
    expect(out.length).toBeLessThan(200);
  });

  it('spells the permission on the line with the action', () => {
    // So a model never has to correlate two lists to know what it may press.
    const out = snap(node({
      type: 'page',
      children: [
        node({ type: 'button', id: 'save', label: 'Save', on: { click: CLICK_RUN },
               agent: true }),
        node({ type: 'button', id: 'wipe', label: 'Delete all', on: { click: CLICK_RUN } }),
      ],
    }));

    expect(out).toContain('#save "Save" → main.js:run [agent]');
    expect(out).toContain('#wipe "Delete all" → main.js:run [closed]');
  });

  it('lists a hidden widget, marked, rather than omitting it', () => {
    // A browser snapshot omits hidden elements. Here a widget can be hidden by
    // the program — possibly by the agent a moment ago — and leaving it out
    // would read as "it does not exist".
    const hiddenOne = node({ type: 'text', id: 'hint', text: 'gone' });
    const out = snap(node({ type: 'page', children: [hiddenOne] }),
                     {}, (n) => n.id === 'hint');

    expect(out).toContain('#hint');
    expect(out).toContain('(hidden by the program)');
  });

  it('names the columns of a table and the fields of a form', () => {
    const out = snap(node({
      type: 'page',
      children: [
        node({ type: 'table', id: 't', from: 'rows', columns: ['key', 'amount'] }),
        node({ type: 'form', id: 'f', from: 'rec',
               fields: [{ name: 'betrag' }, { name: 'note' }] as never }),
      ],
    }));

    expect(out).toContain('columns: key, amount');
    expect(out).toContain('fields: betrag, note');
  });

  it('indents to show nesting', () => {
    const out = snap(node({
      type: 'page',
      children: [node({ type: 'card', children: [node({ type: 'text', text: 'deep' })] })],
    }));

    const lines = out.split('\n');
    expect(lines[0]).toMatch(/^page/);
    expect(lines[1]).toMatch(/^  card/);
    expect(lines[2]).toMatch(/^    text/);
  });
});
