import { describe, it, expect } from 'vitest';
import jsyaml from 'js-yaml';
import {
  SCRIPT_COMPOSE_KINDS,
  kindByNodeName,
  extractScript,
  applyScript,
  normalizeManifest,
  initialManifest,
  type ScriptComposeKind,
} from './scriptComposeCodec';

function parse(yaml: string): Record<string, unknown> {
  return jsyaml.load(yaml) as Record<string, unknown>;
}
function firstTask(yaml: string): Record<string, unknown> {
  return (parse(yaml).tasks as Record<string, unknown>[])[0];
}
const kind = (nodeName: string): ScriptComposeKind => {
  const k = kindByNodeName(nodeName);
  if (!k) throw new Error(`no kind ${nodeName}`);
  return k;
};

describe('scriptComposeCodec', () => {
  it('bash block forces exactly one exec task carrying the command', () => {
    const yaml = applyScript('workspace:\n  name: w\n', kind('vanceComposeBash'), 'ls -la');
    const t = firstTask(yaml);
    expect(t.type).toBe('exec');
    expect(t.command).toBe('ls -la');
    expect((parse(yaml).tasks as unknown[]).length).toBe(1);
  });

  it('agent block: one agent task, session enabled + recipe arthur by default', () => {
    const yaml = initialManifest(kind('vanceComposeAgent'));
    const m = parse(yaml);
    expect(m.session).toEqual({ recipe: 'arthur', enabled: true });
    const t = firstTask(yaml);
    expect(t.type).toBe('agent');
    expect(typeof t.prompt).toBe('string');
    expect(t.recipe).toBeUndefined(); // recipe lives on the session, not the task
  });

  it('agent block keeps a user-set session.recipe but re-forces enabled', () => {
    // User edited the settings pane: custom recipe, enabled removed.
    const edited = 'session:\n  recipe: ford\nworkspace:\n  name: w\ntasks:\n  - type: agent\n    prompt: hi\n';
    const yaml = normalizeManifest(edited, kind('vanceComposeAgent'));
    const s = parse(yaml).session as Record<string, unknown>;
    expect(s.enabled).toBe(true); // forced back on
    expect(s.recipe).toBe('ford'); // user value wins over the arthur default
  });

  it('preserves a user session.name across a prompt (script) edit', () => {
    const edited = 'session:\n  name: my-agent\nworkspace:\n  name: w\ntasks:\n  - type: agent\n    prompt: hi\n';
    const after = applyScript(edited, kind('vanceComposeAgent'), 'new prompt');
    const m = parse(after);
    expect(firstTask(after).prompt).toBe('new prompt');
    const s = m.session as Record<string, unknown>;
    expect(s.name).toBe('my-agent');
    expect(s.enabled).toBe(true);
    expect(s.recipe).toBe('arthur'); // default filled since the user set no recipe
  });

  it('extractScript reads the prompt field for an agent task', () => {
    const yaml = 'workspace:\n  name: w\ntasks:\n  - type: agent\n    prompt: do it\n';
    expect(extractScript(yaml, 'prompt')).toBe('do it');
  });

  it('non-agent kinds carry no session block', () => {
    for (const nodeName of ['vanceComposeJs', 'vanceComposeBash', 'vanceComposePython']) {
      const yaml = initialManifest(kind(nodeName));
      expect(parse(yaml).session).toBeUndefined();
    }
  });

  it('exposes an agent kind with agent/prompt wiring', () => {
    const agent = SCRIPT_COMPOSE_KINDS.find((k) => k.nodeName === 'vanceComposeAgent');
    expect(agent).toMatchObject({ taskType: 'agent', scriptField: 'prompt', fence: 'vance-compose-agent' });
  });
});
