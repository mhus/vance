import { parseYamlBody } from '@vance/shared';

/** Fields the inspector shows in their own place, not in the free-form list. */
const SHOWN_ELSEWHERE = new Set(['type', 'description', 'on', 'catch', 'transitions']);

/**
 * The body of one state as written in the YAML, minus what the inspector
 * shows elsewhere (type, description, routing). {@code null} when the text
 * does not parse or has no such state. Read-only: changing goes through
 * {@code workflowMutations}.
 */
export function readStateFields(text: string, name: string): Record<string, unknown> | null {
  let root: Record<string, unknown>;
  try {
    root = parseYamlBody(text);
  } catch {
    return null;
  }
  const states = root.states;
  if (typeof states !== 'object' || states === null || Array.isArray(states)) return null;
  const state = (states as Record<string, unknown>)[name];
  if (typeof state !== 'object' || state === null || Array.isArray(state)) return null;
  return Object.fromEntries(
    Object.entries(state as Record<string, unknown>).filter(([k]) => !SHOWN_ELSEWHERE.has(k)),
  );
}
