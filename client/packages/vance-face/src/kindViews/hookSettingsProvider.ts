import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentSearchResponse,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';

/**
 * Settings contribution of the hook kind: the UrsaHooks event hooks under
 * {@code _vance/hooks/<event>/<name>.yaml} (see
 * {@code UrsaHookLoader}). The Settings page's "Bereiche" tab shows this
 * as the Hooks area — one row per hook, grouped by the event its path
 * carries.
 *
 * <p><b>Structured create name, like the model area.</b> The event is a
 * path segment, not a body field: the registry lists per event and hands
 * the wire name to the parser, so a hook cannot exist without its event.
 * The add dialog therefore takes {@code <event>/<hook-name>} — e.g.
 * {@code process.completed/notify-owner} — validates the event against
 * the wire names of {@code UrsaHookEventName} and the hook name against
 * the loader's grammar, and seeds one minimal TriggerAction.
 *
 * <p><b>Raw text, deliberately.</b> A hook body is one TriggerAction
 * (recipe / script / workflow) plus a handful of lifecycle keys — the
 * shared action grammar is richer than any single form should own. The
 * kind registers no view and no codec, so the settings host falls back to
 * its raw YAML editor.
 *
 * <p><b>Location-based inventory</b>: the loader scans the path and never
 * looks at kind markers. Server truth: {@code UrsaHookLoader} +
 * {@code UrsaHookYamlParser} (a hook that does not parse is skipped —
 * logged, never fatal) and {@code HookDocKindHandler} for the edit-time
 * findings. The legacy schema ({@code type: js|llm}) is refused by the
 * parser and shows up as a finding.
 *
 * <p>Served for the tenant and project layers only — the hook cascade
 * (project → {@code _vance}) has no user layer.
 */
export const hookSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.hooks.title',
  createHintKey: 'settings.areas.hooks.createHint',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    return listHooks(scope.projectId);
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    if (scope.kind === 'user') {
      throw new Error('Hooks are not available in the user scope.');
    }
    // <event>/<hook-name> — the event is part of the path, not the body.
    const trimmed = name.trim();
    const slash = trimmed.indexOf('/');
    if (slash <= 0 || trimmed.indexOf('/', slash + 1) >= 0) {
      throw new Error('Expected <event>/<hook-name>, e.g. process.completed/notify-owner.');
    }
    const event = trimmed.substring(0, slash);
    if (!HOOK_EVENTS.includes(event)) {
      throw new Error(`Unknown event '${event}' — known events: ${HOOK_EVENTS.join(', ')}.`);
    }
    const hookName = trimmed.substring(slash + 1);
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(hookName)) {
      throw new Error('The hook name must be lowercase, alphanumeric + \'_-\', max 64 chars.');
    }
    const path = `_vance/hooks/${event}/${hookName}.yaml`;
    const body: DocumentCreateRequest = {
      path,
      // Minimal body the parser accepts: exactly one TriggerAction at the
      // top. The event comes from the path; enabled defaults to true.
      inlineText:
        '# New hook — exactly one TriggerAction at the top: recipe, script or workflow.\n'
        + '$meta:\n  kind: vance-hook\n'
        + `description: Fires on ${event}.\n`
        + 'recipe: arthur\n',
    };
    const params = new URLSearchParams({ projectId: scope.projectId });
    const doc = await brainFetch<DocumentDto>(
      'POST',
      `documents?${params.toString()}`,
      { body },
    );
    return {
      name: `${event}/${hookName}`,
      title: `${hookName} (${event})`,
      kindId: 'vance-hook',
      documentId: doc.id,
      projectId: scope.projectId,
      path,
    };
  },
};

/** Wire names of {@code UrsaHookEventName} — the event path segments the registry lists. */
const HOOK_EVENTS = [
  'process.completed',
  'process.failed',
  'inbox.item.created',
  'session.suspended',
  'session.resumed',
  'insight.saved',
  'relation.created',
];

const HOOKS_PREFIX = '_vance/hooks/';

async function listHooks(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    pathPrefix: HOOKS_PREFIX,
    size: '200',
  });
  const data = await brainFetch<DocumentSearchResponse>(
    'GET',
    `documents/search?${params.toString()}`,
  );
  return data.items
    .filter((item) => item.path.endsWith('.yaml'))
    .map((item) => {
      const rel = item.path.substring(HOOKS_PREFIX.length).replace(/\.yaml$/, '');
      return {
        name: rel,
        title: item.title || rel,
        kindId: 'vance-hook',
        documentId: item.id,
        projectId,
        path: item.path,
      };
    });
}
