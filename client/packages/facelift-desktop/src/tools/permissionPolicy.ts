/**
 * Per-account permission policy for the desktop agent tools — the
 * behavioral twin of the foot CLI sandbox
 * (`specification/public/foot-sandbox.md`): deny → allow → ask, glob
 * path rules, regex command rules, a non-overridable default-deny floor
 * and a separate {@code delete} domain so a read/write allow never
 * grants deletion.
 *
 * Differences from foot (planning/desktop-agent-tools.md §5): the policy
 * lives **per account** in the desktop config —
 * `<userData>/tools/<accountId>/permissions.yaml` — not in foot's
 * machine-central {@code ~/.vancetope/permissions.yaml}, and relative
 * globs resolve against the account's workdir, not a process CWD.
 */
import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import yaml from 'js-yaml';

/** Domains the gate knows — see foot-sandbox.md §3/§3.1. */
export type PermissionDomain = 'paths' | 'commands' | 'delete';

export type Verdict = 'ALLOW' | 'DENY' | 'ASK';

/** What the gate hands to the ask surface (dialog): tool + subject. */
export interface AskSubject {
  toolName: string;
  domain: PermissionDomain;
  /** Canonical path or raw command — what the rules were matched against. */
  subject: string;
}

interface DomainRules {
  allow: string[];
  deny: string[];
}

interface PermissionsShape {
  sandbox?: boolean;
  paths?: DomainRules;
  commands?: DomainRules;
  delete?: DomainRules;
}

interface PolicyFileShape {
  permissions?: PermissionsShape;
}

/**
 * Default-deny floor — always evaluated in addition to the user's
 * {@code paths} rules and impossible to override with an allow
 * (foot-sandbox.md §5.2).
 */
const DENY_FLOOR = ['~/.ssh/**', '~/.aws/**', '~/.gnupg/**', '~/.vancetope/**'];

/** Compiled policy. Holds the loaded rules; "always" answers mutate them. */
export class PermissionPolicy {
  private readonly sandbox: boolean;
  private readonly paths: DomainRules;
  private readonly commands: DomainRules;
  private readonly deleteRules: DomainRules;

  constructor(
    private readonly policyFile: string,
    private readonly workdir: string,
    shape: PolicyFileShape,
  ) {
    const permissions = shape.permissions ?? {};
    this.sandbox = permissions.sandbox !== false;
    this.paths = normalize(permissions.paths);
    this.commands = normalize(permissions.commands);
    this.deleteRules = normalize(permissions.delete);
  }

  isSandboxEnabled(): boolean {
    return this.sandbox;
  }

  /** Evaluate a file tool call against its (already canonicalized) path. */
  evaluatePath(canonicalPath: string, isDelete: boolean): Verdict {
    if (!this.sandbox) return 'ALLOW';
    if (isDelete) {
      // Two deny lists (paths incl. floor, then delete.deny), then only
      // delete.allow — a read/write allow never grants deletion
      // (foot-sandbox.md §3.1).
      if (this.matchesAny([...this.paths.deny, ...DENY_FLOOR], canonicalPath)) {
        return 'DENY';
      }
      if (this.matchesAny(this.deleteRules.deny, canonicalPath)) return 'DENY';
      if (this.matchesAny(this.deleteRules.allow, canonicalPath)) return 'ALLOW';
      return 'ASK';
    }
    if (this.matchesAny([...this.paths.deny, ...DENY_FLOOR], canonicalPath)) {
      return 'DENY';
    }
    if (this.matchesAny(this.paths.allow, canonicalPath)) return 'ALLOW';
    return 'ASK';
  }

  /** Evaluate an exec command against the regex rules (stage 2). */
  evaluateCommand(command: string): Verdict {
    if (!this.sandbox) return 'ALLOW';
    if (command.trim() === '') return 'DENY';
    if (this.commands.deny.some((rule) => matchesRegex(rule, command))) return 'DENY';
    if (this.commands.allow.some((rule) => matchesRegex(rule, command))) return 'ALLOW';
    return 'ASK';
  }

  /**
   * Persist an "always" answer as an exact rule in this account's policy
   * file — mirrors foot-sandbox.md §8: exact match only (glob without
   * wildcards for paths, fully anchored escaped regex for commands),
   * idempotent write. Broader rules are hand-written by the operator.
   */
  async persistAlways(subject: AskSubject, allowed: boolean): Promise<void> {
    const listName = allowed ? 'allow' : 'deny';
    let rule: string;
    let domain: DomainRules;
    if (subject.domain === 'commands') {
      rule = `^${escapeRegex(subject.subject)}$`;
      domain = this.commands;
    } else if (subject.domain === 'delete') {
      rule = subject.subject;
      domain = this.deleteRules;
    } else {
      rule = subject.subject;
      domain = this.paths;
    }
    if (domain[listName].includes(rule)) return;
    domain[listName].push(rule);
    const shape: PolicyFileShape = {
      permissions: {
        sandbox: this.sandbox,
        paths: this.paths,
        commands: this.commands,
        delete: this.deleteRules,
      },
    };
    await mkdir(path.dirname(this.policyFile), { recursive: true });
    await writeFile(this.policyFile, yaml.dump(shape), 'utf-8');
  }

  private matchesAny(rules: string[], canonicalPath: string): boolean {
    return rules.some((rule) => matchesGlob(rule, canonicalPath, this.workdir));
  }
}

/**
 * Absolute, normalized, symlink-resolved form of {@code raw}: expands
 * {@code ~}, resolves relative paths against the account workdir,
 * collapses {@code .}/{@code ..} and follows symlinks — falling back to
 * the deepest existing ancestor for not-yet-existing targets (a write to
 * a new file is legitimate, but a symlinked parent must still collapse).
 * Without this, {@code ~/foo/../.ssh/id_rsa} slips past every deny rule
 * (foot-sandbox.md §5.1).
 */
export async function canonicalize(raw: string, workdir: string): Promise<string> {
  const expanded = expandHome(raw);
  const abs = path.isAbsolute(expanded) ? expanded : path.join(workdir, expanded);
  const normalized = path.normalize(abs);
  try {
    return await realpath(normalized);
  } catch {
    // Target does not exist (yet). Resolve the deepest existing ancestor
    // and re-append the missing tail segments.
    const parent = path.dirname(normalized);
    if (parent === normalized) return normalized;
    const parentReal = await canonicalize(parent, workdir);
    return path.join(parentReal, path.basename(normalized));
  }
}

/** Load an account's policy file. Missing/corrupt file → empty policy —
 *  the deny floor + ASK defaults still protect. Never throws. */
export async function loadPolicy(policyFile: string, workdir: string): Promise<PermissionPolicy> {
  let shape: PolicyFileShape = {};
  try {
    const loaded = yaml.load(await readFile(policyFile, 'utf-8'));
    if (typeof loaded === 'object' && loaded !== null) {
      shape = loaded as PolicyFileShape;
    }
  } catch {
    shape = {};
  }
  return new PermissionPolicy(policyFile, workdir, shape);
}

// ─── Matching ──────────────────────────────────────────────────────────

function normalize(rules: DomainRules | undefined): DomainRules {
  return {
    allow: rules?.allow ?? [],
    deny: rules?.deny ?? [],
  };
}

/** Expand a leading {@code ~} to the user's home directory. */
export function expandHome(raw: string): string {
  if (raw === '~') return os.homedir();
  if (raw.startsWith('~/') || raw.startsWith('~\\')) {
    return path.join(os.homedir(), raw.slice(2));
  }
  return raw;
}

/**
 * Glob match against a canonical path. The glob may start with {@code ~}
 * or be relative (resolved against the workdir). {@code **} crosses
 * directory boundaries — as a trailing segment it also matches the
 * directory itself ({@code ~/projects/**} covers {@code ~/projects}).
 */
export function matchesGlob(glob: string, canonicalPath: string, workdir: string): boolean {
  const expanded = expandHome(glob);
  const absolute = path.isAbsolute(expanded) ? expanded : path.join(workdir, expanded);
  const pattern = toPosix(path.normalize(absolute));
  const target = toPosix(canonicalPath);
  return globToRegex(pattern).test(target);
}

/** Glob-style pattern → anchored regex ({@code *} and {@code ?} stay
 *  within one segment, {@code **} crosses, {@code dir/**} includes
 *  {@code dir} itself — Java {@code PathMatcher} semantics). */
export function globToRegex(pattern: string): RegExp {
  let re = '';
  let i = 0;
  while (i < pattern.length) {
    const c = pattern[i];
    if (c === '*') {
      if (pattern[i + 1] === '*') {
        i += 2;
        // Trailing `/**` also matches the directory itself.
        if (i >= pattern.length && re.endsWith('/')) {
          re = `${re.slice(0, -1)}(/.*)?$`;
          return new RegExp(re);
        }
        re += '.*';
      } else {
        re += '[^/]*';
        i++;
      }
    } else if (c === '?') {
      re += '[^/]';
      i++;
    } else if (c === '\\' || c === '^' || c === '$' || c === '.' || c === '|' || c === '+') {
      re += `\\${c}`;
      i++;
    } else if (c === '(' || c === ')' || c === '[' || c === ']' || c === '{' || c === '}') {
      re += `\\${c}`;
      i++;
    } else {
      re += c;
      i++;
    }
  }
  return new RegExp(`^${re}$`);
}

/** Command rules are regexes with find() semantics — unanchored unless
 *  the rule author anchors them (foot-sandbox.md §5). */
export function matchesRegex(rule: string, command: string): boolean {
  try {
    return new RegExp(rule).test(command);
  } catch {
    // Broken pattern: refuse to match rather than throw — a broken deny
    // must not wedge the gate. (foot fails fast at load; the desktop app
    // prefers resilience — the file is per-account and hand-edited.)
    return false;
  }
}

function escapeRegex(raw: string): string {
  return raw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function toPosix(p: string): string {
  return p.replace(/\\/g, '/');
}
