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
} from '@vance/generated';
import type { BrainWsApi } from '@vance/shared';

/** A parsed `/skill` line; `null` command means "usage error" (reply says so). */
export interface ParsedSkillCommand {
  command: ProcessSkillCommand | null;
  skillName?: string;
  oneShot: boolean;
  args?: string;
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
  return {
    command: ProcessSkillCommand.ACTIVATE,
    skillName: sub,
    oneShot: parts.includes('--once'),
    args: parts.slice(2).filter((p) => p !== '--once').join(' ') || undefined,
  };
}

export const SKILL_USAGE =
  '/skill → usage: /skill list | clear [name] | <name> [--once] [args…]';

/**
 * Round-trips the parsed command and returns the activity line to render.
 * Never throws — a failed round-trip is a rendered error line, the same
 * shape the success paths return.
 */
export async function sendSkillCommand(
  sock: BrainWsApi,
  processName: string,
  parsed: ParsedSkillCommand,
): Promise<string> {
  if (parsed.command === null) {
    return SKILL_USAGE;
  }
  try {
    const reply = await sock.send<ProcessSkillRequest, ProcessSkillResponse>('process-skill', {
      processName,
      command: parsed.command,
      skillName: parsed.skillName,
      oneShot: parsed.oneShot,
      args: parsed.args,
    });
    return renderSkillReplyForTest(parsed.command, parsed.skillName, reply);
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
  return `/skill → activated ${skillName} — instructions in effect from the next turn`;
}
