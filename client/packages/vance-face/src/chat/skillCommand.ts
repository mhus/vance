/**
 * The `/skill …` composer command — parsing, wire round-trip and reply
 * rendering, shared by every surface that hosts a chat composer.
 *
 * <p>Frontend-only interception: the composer swallows the line and emits
 * `skill-command` instead of steering it as chat input; the host runs it
 * here and renders the outcome as an ephemeral activity line. This module
 * exists because that handling lived in ChatApp only — so the Cortex side
 * panel silently dropped the command, the exact bug a shared
 * implementation cannot have again.
 *
 * <p>The raw trailing text of `/skill <name> [--once] <rest…>` travels as
 * `args`: the brain decides what happens with it — bound into the skill's
 * prompt template when it declares `arguments:`, injected as a plain user
 * message otherwise. Never both, so it must not be sent as a chat message
 * as well.
 */
import {
  ProcessSkillCommand,
  ProcessSkillRequest,
  ProcessSkillResponse,
  SkillModeResponse,
} from '@vance/generated';
import { brainFetch, type BrainWsApi } from '@vance/shared';

/**
 * Marker for the mode subcommand — scope configuration over REST, not a
 * process-skill WS command (no process involved, which is the whole point
 * of the separate surface).
 */
export const MODE = 'mode' as const;

/**
 * A parsed `/skill` line; `null` command means "usage error" (reply says
 * so). `MODE` means "set the activation mode" (REST write, user tier by
 * default).
 */
export interface ParsedSkillCommand {
  command: ProcessSkillCommand | typeof MODE | null;
  skillName?: string;
  oneShot: boolean;
  /** `--sticky`: keep a `lifecycle: turn` skill beyond one turn. */
  sticky?: boolean;
  args?: string;
  /** For MODE: manual | trigger | agent. */
  mode?: string;
  /** For MODE: user (default) | project | tenant. */
  scope?: string;
}

export function parseSkillCommand(line: string): ParsedSkillCommand {
  const parts = line.trim().split(/\s+/).filter(Boolean);
  const head = parts[0];

  if (head === '/skill-list') {
    return { command: ProcessSkillCommand.LIST, oneShot: false };
  }
  if (head === '/skill-clear') {
    return {
      command: parts[1] ? ProcessSkillCommand.CLEAR : ProcessSkillCommand.CLEAR_ALL,
      skillName: parts[1],
      oneShot: false,
    };
  }
  // head === '/skill'
  const sub = parts[1];
  if (!sub) {
    return { command: null, oneShot: false };
  }
  if (sub === 'list') {
    return { command: ProcessSkillCommand.LIST, oneShot: false };
  }
  if (sub === 'clear') {
    return {
      command: parts[2] ? ProcessSkillCommand.CLEAR : ProcessSkillCommand.CLEAR_ALL,
      skillName: parts[2],
      oneShot: false,
    };
  }
  if (sub === 'mode') {
    return parseMode(parts.slice(2));
  }
  return {
    command: ProcessSkillCommand.ACTIVATE,
    skillName: sub,
    oneShot: parts.includes('--once'),
    sticky: parts.includes('--sticky'),
    args: parts.slice(2).filter((p) => p !== '--once' && p !== '--sticky').join(' ') || undefined,
  };
}

/** `/skill mode <name> <hidden|manual|trigger|agent> [user|project|tenant]`. */
function parseMode(parts: string[]): ParsedSkillCommand {
  if (parts.length < 2) {
    return { command: null, oneShot: false };
  }
  return {
    command: MODE,
    skillName: parts[0],
    oneShot: false,
    mode: parts[1],
    scope: parts[2],
  };
}

export const SKILL_USAGE =
  '/skill → usage: /skill list | clear [name] | mode <name> <hidden|manual|trigger|agent> [scope]'
  + ' | <name> [--once|--sticky] [args…]';

/**
 * Round-trips the parsed command and returns the activity line to render.
 * Never throws — a failed round-trip is a rendered error line, the same
 * shape the success paths return.
 */
export async function sendSkillCommand(
  sock: BrainWsApi,
  processName: string,
  parsed: ParsedSkillCommand,
  projectId?: string,
): Promise<string> {
  if (parsed.command === null) {
    return SKILL_USAGE;
  }
  if (parsed.command === MODE) {
    return sendModeCommand(parsed, projectId);
  }
  try {
    const reply = await sock.send<ProcessSkillRequest, ProcessSkillResponse>('process-skill', {
      processName,
      command: parsed.command,
      skillName: parsed.skillName,
      oneShot: parsed.oneShot,
      sticky: parsed.sticky ?? false,
      args: parsed.args,
    });
    return renderSkillReplyForTest(parsed.command, parsed.skillName, reply, parsed.oneShot || false, parsed.sticky ?? false);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    return `/skill → error: ${msg}`;
  }
}

/** Test hook: the renderer is pure — exercising it keeps the reply sentences honest. */
export function renderSkillReplyForTest(
  command: ProcessSkillCommand,
  skillName: string | undefined,
  r: ProcessSkillResponse,
  oneShot = false,
  sticky = false,
): string {
  if (command === ProcessSkillCommand.LIST) {
    const active = r.activeSkills.map((a) => a.name).join(', ') || '—';
    const available = (r.availableSkills ?? []).map((s) => s.name).join(', ') || '—';
    return `/skill list → active: ${active}  ·  available: ${available}`;
  }
  if (command === ProcessSkillCommand.CLEAR) {
    return `/skill → cleared ${skillName}`;
  }
  if (command === ProcessSkillCommand.CLEAR_ALL) {
    return '/skill → cleared all';
  }
  // ACTIVATE: the backend distinguishes "freshly activated" (its action:
  // turn fired — visible as work starting in the chat) from "already
  // active" (at most arguments updated). One sentence each, so the
  // command's effect is readable without opening /skill list.
  if (r.newlyActivated === false) {
    return `/skill → ${skillName} is already active`;
  }
  if (r.lifecycle === 'shot') {
    return `/skill → fired ${skillName} (macro)`;
  }
  // A turn skill (the default lifecycle) lives for one turn unless --sticky.
  if (oneShot || (r.lifecycle === 'turn' && !sticky)) {
    return `/skill → activated ${skillName} for the next turn — clears itself afterwards (--sticky keeps it)`;
  }
  return `/skill → activated ${skillName} — instructions in effect from the next turn`;
}

/**
 * The mode write — REST, not the process-skill WS channel: the mode is
 * scope configuration that applies to every future turn of every process,
 * so there is no process to steer (planning/skill-activation-levels.md
 * §3.5). The write lands in the user's own override tier by default;
 * project/tenant target the wider tiers (write access enforced server
 * side).
 */
async function sendModeCommand(parsed: ParsedSkillCommand, projectId?: string): Promise<string> {
  const mode = (parsed.mode ?? '').toLowerCase();
  if (!['hidden', 'manual', 'trigger', 'agent'].includes(mode)) {
    return '/skill mode → usage: /skill mode <name> <hidden|manual|trigger|agent> [user|project|tenant]';
  }
  const scope = (parsed.scope ?? 'user').toLowerCase();
  if (!['user', 'project', 'tenant'].includes(scope)) {
    return `/skill mode → unknown scope '${parsed.scope}' — expected user | project | tenant`;
  }
  try {
    const name = parsed.skillName ?? '';
    const query = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
    const reply = await brainFetch<SkillModeResponse>(
      'POST',
      `skills/${encodeURIComponent(name)}/activation${query}`,
      { body: { mode: mode.toUpperCase(), scope: scope.toUpperCase() } },
    );
    return `/skill mode → ${name}: ${reply.activation.toLowerCase()}`
      + ` (override tier: ${(reply.activationOverrideScope ?? 'user').toLowerCase()})`
      + ' — effective from the next turn';
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    return `/skill mode → error: ${msg}`;
  }
}
