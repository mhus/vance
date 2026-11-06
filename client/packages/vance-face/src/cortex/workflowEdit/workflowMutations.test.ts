import { describe, expect, it } from 'vitest';
import { parseWorkflowGraph } from '@/workflowGraph/workflowGraphCodec';
import {
  addState,
  connect,
  disconnect,
  freeStateName,
  relabel,
  removeState,
  renameState,
  retarget,
  setStart,
  setStateExtras,
  setStateField,
  WorkflowEditError,
} from './workflowMutations';

const DOC = `$meta:
  kind: vance-workflow

# What this flow does, in the author's words.
start: plan

states:
  # first step
  plan:
    type: agent_task
    recipe: jeltz # who thinks
    on:
      success: check
    catch:
      agent_error: done

  check:
    type: condition_task
    transitions:
      - if: "#state['x'] == 1"
        to: done
      - else: plan

  done:
    type: terminal
    outcome: success
`;

function edges(text: string): string[] {
  return parseWorkflowGraph(text).edges.map((e) => `${e.source}-${e.kind}:${e.label}->${e.target}`);
}

function names(text: string): string[] {
  return parseWorkflowGraph(text).states.map((s) => s.name);
}

function fails(fn: () => unknown, message: RegExp): void {
  expect(fn).toThrow(WorkflowEditError);
  expect(fn).toThrow(message);
}

describe('round trip', () => {
  it('keeps comments, order and formatting of an untouched document', () => {
    expect(setStart(DOC, 'plan')).toBe(DOC);
  });

  it('keeps comments when something else changes', () => {
    const out = setStateField(DOC, 'plan', ['recipe'], 'ford');
    expect(out).toContain("# What this flow does, in the author's words.");
    expect(out).toContain('# first step');
    expect(out).toContain('recipe: ford');
    expect(out).toContain('kind: vance-workflow');
  });

  it('keeps the on: key a string', () => {
    expect(setStateField(DOC, 'plan', ['storeAs'], 'out')).toContain('    on:\n');
  });

  it('refuses text that is not a workflow mapping', () => {
    fails(() => setStart('- a\n- b\n', 'x'), /not a mapping/);
    fails(() => setStart('start: [', 'x'), /./);
  });
});

describe('addState', () => {
  it('appends a state at the end', () => {
    const out = addState(DOC, 'notify', { type: 'tool_task', tool: 'slack_rest__post' });
    expect(names(out)).toEqual(['plan', 'check', 'done', 'notify']);
    const node = parseWorkflowGraph(out).states.find((s) => s.name === 'notify');
    expect(node?.type).toBe('tool_task');
    expect(node?.detail).toBe('slack_rest__post');
  });

  it('makes the first state of an empty workflow the start', () => {
    const out = addState('', 'first', { type: 'terminal', outcome: 'success' });
    const g = parseWorkflowGraph(out);
    expect(g.start).toBe('first');
    expect(g.problems).toEqual([]);
  });

  it('keeps an existing start', () => {
    expect(parseWorkflowGraph(addState(DOC, 'x', { type: 'terminal' })).start).toBe('plan');
  });

  it('adds nested fields', () => {
    const out = addState(DOC, 'a', { type: 'agent_task', params: { prompt: 'hi' } });
    expect(out).toMatch(/params:\n\s+prompt: hi/);
  });

  it('rejects duplicates, bad names and a missing type', () => {
    fails(() => addState(DOC, 'plan', { type: 'terminal' }), /already a state/);
    fails(() => addState(DOC, 'two words', { type: 'terminal' }), /usable state name/);
    fails(() => addState(DOC, 'x', {}), /needs a 'type'/);
  });
});

describe('freeStateName', () => {
  it('numbers a name that is taken', () => {
    expect(freeStateName(DOC, 'check')).toBe('check_2');
    expect(freeStateName(DOC, 'fresh')).toBe('fresh');
  });

  it('turns a type or tool name into a legal one', () => {
    expect(freeStateName(DOC, 'slack.rest post')).toBe('slack_rest_post');
  });
});

describe('removeState', () => {
  it('removes the state and every transition to it', () => {
    const out = removeState(DOC, 'done');
    expect(names(out)).toEqual(['plan', 'check']);
    expect(edges(out)).toEqual(['plan-on:success->check', 'check-else:else->plan']);
    expect(parseWorkflowGraph(out).problems).toEqual([]);
  });

  it('drops a routing block that lost its last entry', () => {
    const out = removeState(DOC, 'check');
    expect(out).not.toContain('success: check');
    const plan = out.slice(out.indexOf('plan:'));
    expect(plan).toContain('catch:');
    expect(plan).not.toMatch(/\bon:/);
  });

  it('moves the start to the first remaining state', () => {
    const out = removeState(DOC, 'plan');
    expect(parseWorkflowGraph(out).start).toBe('check');
  });

  it('removes the start when no state is left', () => {
    const one = 'start: a\nstates:\n  a:\n    type: terminal\n';
    expect(parseWorkflowGraph(removeState(one, 'a')).start).toBeNull();
  });

  it('rejects an unknown state', () => {
    fails(() => removeState(DOC, 'nope'), /no state 'nope'/);
  });
});

describe('renameState', () => {
  it('renames in place and follows start and every transition', () => {
    const out = renameState(DOC, 'plan', 'design');
    expect(names(out)).toEqual(['design', 'check', 'done']);
    expect(parseWorkflowGraph(out).start).toBe('design');
    expect(edges(out)).toEqual([
      'design-on:success->check',
      'design-catch:agent_error->done',
      'check-condition:#state[\'x\'] == 1->done',
      'check-else:else->design',
    ]);
    expect(parseWorkflowGraph(out).problems).toEqual([]);
    expect(out).toContain('# first step');
  });

  it('is a no-op for the same name', () => {
    expect(renameState(DOC, 'plan', 'plan')).toBe(DOC);
  });

  it('rejects a taken or illegal name', () => {
    fails(() => renameState(DOC, 'plan', 'done'), /already a state/);
    fails(() => renameState(DOC, 'plan', 'a b'), /usable state name/);
    fails(() => renameState(DOC, 'nope', 'x'), /no state 'nope'/);
  });
});

describe('setStart', () => {
  it('moves the start', () => {
    expect(parseWorkflowGraph(setStart(DOC, 'check')).start).toBe('check');
  });

  it('rejects an unknown state', () => {
    fails(() => setStart(DOC, 'nope'), /no state 'nope'/);
  });
});

describe('setStateField', () => {
  it('creates intermediate maps', () => {
    const out = setStateField(DOC, 'plan', ['params', 'prompt'], 'go');
    expect(out).toMatch(/params:\n\s+prompt: go/);
  });

  it('removes a field with undefined', () => {
    expect(setStateField(DOC, 'plan', ['recipe'], undefined)).not.toContain('recipe:');
  });

  it('removes a map that the removal leaves empty, up to the state', () => {
    const doc = 'start: a\nstates:\n  a:\n    type: agent_task\n    params:\n      prompt: hi\n';
    const out = setStateField(doc, 'a', ['params', 'prompt'], undefined);
    expect(out).not.toContain('params');
    expect(out).toContain('type: agent_task');
    expect(setStateField(out, 'a', ['type'], undefined)).toContain('a: {}');
  });

  it('rejects an unknown state and an empty path', () => {
    fails(() => setStateField(DOC, 'nope', ['a'], 1), /no state 'nope'/);
    fails(() => setStateField(DOC, 'plan', [], 1), /path is needed/);
  });
});

describe('connect', () => {
  it('adds an outcome to an existing on block', () => {
    const out = connect(DOC, { source: 'plan', kind: 'on', label: 'failure', target: 'done' });
    expect(edges(out)).toContain('plan-on:failure->done');
  });

  it('creates the block when the state has none', () => {
    const out = connect(DOC, { source: 'check', kind: 'on', label: 'x', target: 'plan' });
    expect(edges(out)).toContain('check-on:x->plan');
  });

  it('puts a branch before the else', () => {
    const out = connect(DOC, {
      source: 'check', kind: 'condition', label: '#state[\'y\'] > 2', target: 'plan',
    });
    const kinds = parseWorkflowGraph(out).edges.filter((e) => e.source === 'check').map((e) => e.kind);
    expect(kinds).toEqual(['condition', 'condition', 'else']);
  });

  it('creates the transitions list and appends an else', () => {
    const bare = removeState(DOC, 'check');
    const withCondition = addState(bare, 'route', { type: 'condition_task' });
    const out = connect(withCondition, { source: 'route', kind: 'else', target: 'done' });
    expect(edges(out)).toContain('route-else:else->done');
    expect(parseWorkflowGraph(out).problems).toEqual([]);
  });

  it('refuses to leave a terminal state or to branch from a state that is no condition_task', () => {
    fails(() => connect(DOC, { source: 'done', kind: 'on', label: 'x', target: 'plan' }), /terminal/);
    fails(
      () => connect(DOC, { source: 'plan', kind: 'else', target: 'done' }),
      /Only a condition_task/,
    );
  });

  it('rejects a second else, a taken outcome, an unknown target and a missing label', () => {
    fails(() => connect(DOC, { source: 'check', kind: 'else', target: 'done' }), /already has an else/);
    fails(
      () => connect(DOC, { source: 'plan', kind: 'on', label: 'success', target: 'done' }),
      /already leads/,
    );
    fails(
      () => connect(DOC, { source: 'plan', kind: 'on', label: 'x', target: 'nope' }),
      /no state 'nope'/,
    );
    fails(() => connect(DOC, { source: 'plan', kind: 'on', target: 'done' }), /needs an outcome/);
    fails(
      () => connect(DOC, { source: 'check', kind: 'condition', target: 'done' }),
      /needs a condition/,
    );
  });
});

describe('disconnect / retarget / relabel', () => {
  const g = parseWorkflowGraph(DOC);
  const ref = (kind: string, label?: string) => {
    const e = g.edges.find((x) => x.kind === kind && (label === undefined || x.label === label))!;
    return { source: e.source, kind: e.kind, key: e.key };
  };

  it('addresses an edge by its codec key', () => {
    expect(g.edges.map((e) => `${e.kind}:${e.key}`)).toEqual([
      'on:success', 'catch:agent_error', 'condition:0', 'else:1',
    ]);
  });

  it('removes an on transition and prunes the empty block', () => {
    const out = disconnect(DOC, ref('on'));
    expect(edges(out)).not.toContain('plan-on:success->check');
    expect(out.slice(out.indexOf('plan:'), out.indexOf('check:'))).not.toMatch(/\bon:/);
  });

  it('removes a branch by index, leaving the else', () => {
    const out = disconnect(DOC, ref('condition'));
    expect(parseWorkflowGraph(out).edges.filter((e) => e.source === 'check').map((e) => e.kind))
      .toEqual(['else']);
  });

  it('removes the last transition and the list with it', () => {
    const one = disconnect(disconnect(DOC, ref('condition')), { source: 'check', kind: 'else', key: '0' });
    expect(one).not.toContain('transitions');
  });

  it('retargets', () => {
    expect(edges(retarget(DOC, ref('on'), 'done'))).toContain('plan-on:success->done');
    expect(edges(retarget(DOC, ref('else'), 'done'))).toContain('check-else:else->done');
    expect(edges(retarget(DOC, ref('condition'), 'plan'))).toContain(
      'check-condition:#state[\'x\'] == 1->plan',
    );
  });

  it('relabels an outcome in place and a condition', () => {
    const out = relabel(DOC, ref('on'), 'ok');
    expect(edges(out)).toContain('plan-on:ok->check');
    expect(edges(relabel(DOC, ref('condition'), '#state.a'))).toContain(
      'check-condition:#state.a->done',
    );
  });

  it('rejects what cannot be done', () => {
    fails(() => relabel(DOC, ref('else'), 'x'), /no condition/);
    fails(() => relabel(DOC, ref('on'), ' '), /cannot be empty/);
    fails(
      () => disconnect(DOC, { source: 'plan', kind: 'on', key: 'nope' }),
      /no such connection/,
    );
    fails(() => retarget(DOC, ref('on'), 'nope'), /no state 'nope'/);
  });

  it('refuses to relabel onto an outcome that exists', () => {
    const two = connect(DOC, { source: 'plan', kind: 'on', label: 'failure', target: 'done' });
    fails(() => relabel(two, ref('on'), 'failure'), /already leads/);
  });
});

describe('setStateExtras', () => {
  const OWNED = new Set(['type', 'description', 'on', 'catch', 'recipe', 'params']);
  const WITH_EXTRAS = DOC.replace(
    '    recipe: jeltz # who thinks\n',
    '    recipe: jeltz # who thinks\n    retry:\n      maxAttempts: 2\n    storeAs: out\n',
  );

  it('removes the keys that are gone, adds new ones and leaves owned keys alone', () => {
    const out = setStateExtras(WITH_EXTRAS, 'plan', { timeoutSeconds: 30, storeAs: 'out' }, OWNED);
    expect(out).toContain('timeoutSeconds: 30');
    expect(out).toContain('storeAs: out');
    expect(out).not.toContain('retry');
    expect(out).toContain('recipe: jeltz # who thinks');
    expect(out).toContain('success: check');
  });

  it('does not touch a key whose value did not change', () => {
    const same = setStateExtras(WITH_EXTRAS, 'plan', { retry: { maxAttempts: 2 }, storeAs: 'out' }, OWNED);
    expect(same).toBe(WITH_EXTRAS);
  });

  it('replaces a changed value', () => {
    const out = setStateExtras(WITH_EXTRAS, 'plan', { retry: { maxAttempts: 5 }, storeAs: 'out' }, OWNED);
    expect(out).toContain('maxAttempts: 5');
  });

  it('refuses an owned key and an unknown state', () => {
    fails(() => setStateExtras(DOC, 'plan', { recipe: 'x' }, OWNED), /own field/);
    fails(() => setStateExtras(DOC, 'nope', {}, OWNED), /no state 'nope'/);
  });
});
