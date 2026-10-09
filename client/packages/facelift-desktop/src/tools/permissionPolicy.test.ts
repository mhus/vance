/**
 * Behavioural parity tests for the desktop permission policy — the gate
 * that stands between the LLM and the user's machine. The verdict model
 * (deny → allow → ask, delete as its own domain, non-overridable deny
 * floor) mirrors `specification/public/foot-sandbox.md` §3–§8; these
 * tests pin the semantics so the TS twin cannot drift from the spec.
 */
import { mkdir, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import {
  canonicalize,
  globToRegex,
  loadPolicy,
  matchesGlob,
  PermissionPolicy,
} from './permissionPolicy';

const WORKDIR = path.join(os.tmpdir(), 'desktop-agent-policy-test');
/** realpath form — macOS resolves /var → /private/var, canonicalize
 *  follows that, so expectations must compare against the real path. */
let WORKDIR_REAL = WORKDIR;

/** The deny floor must protect these even with a blanket allow. */
const FLOOR_PATHS = [
  path.join(os.homedir(), '.ssh', 'id_rsa'),
  path.join(os.homedir(), '.aws', 'credentials'),
  path.join(os.homedir(), '.gnupg', 'pubring.kbx'),
  path.join(os.homedir(), '.vancetope', 'permissions.yaml'),
];

describe('globToRegex', () => {
  it('keeps a single * within one segment', () => {
    expect(globToRegex('/a/*.ts').test('/a/b.ts')).toBe(true);
    expect(globToRegex('/a/*.ts').test('/a/x/b.ts')).toBe(false);
  });

  it('lets ** cross directories', () => {
    expect(globToRegex('/a/**').test('/a/b/c/d.txt')).toBe(true);
    expect(globToRegex('/a/**/*.md').test('/a/b/c/d.md')).toBe(true);
  });

  it('a trailing dir/** also matches the directory itself', () => {
    expect(globToRegex('/a/b/**').test('/a/b')).toBe(true);
  });
});

describe('matchesGlob', () => {
  it('expands ~ and resolves relative globs against the workdir', () => {
    const file = path.join(WORKDIR, 'src', 'x.ts');
    expect(matchesGlob('./src/**', file, WORKDIR)).toBe(true);
    expect(matchesGlob('~/src/**', file, WORKDIR)).toBe(false);
  });
});

describe('PermissionPolicy.evaluatePath', () => {
  it('empty policy: ASK for normal paths, DENY for the floor', () => {
    const policy = policyOf({});
    expect(policy.evaluatePath(path.join(WORKDIR, 'notes.txt'), false)).toBe('ASK');
    for (const p of FLOOR_PATHS) {
      expect(policy.evaluatePath(p, false)).toBe('DENY');
    }
  });

  it('deny beats allow; allow beats ask', () => {
    const policy = policyOf({
      paths: {
        allow: ['~/projects/**'],
        deny: ['~/projects/secret/**'],
      },
    });
    expect(policy.evaluatePath(path.join(os.homedir(), 'projects', 'x', 'a.txt'), false)).toBe('ALLOW');
    expect(policy.evaluatePath(path.join(os.homedir(), 'projects', 'secret', 'a.txt'), false)).toBe(
      'DENY',
    );
  });

  it('a blanket home allow cannot open the deny floor', () => {
    const policy = policyOf({ paths: { allow: ['~/**'] } });
    for (const p of FLOOR_PATHS) {
      expect(policy.evaluatePath(p, false)).toBe('DENY');
    }
  });

  it('sandbox off allows everything', () => {
    const policy = policyOf({ sandbox: false });
    expect(policy.evaluatePath(FLOOR_PATHS[0], false)).toBe('ALLOW');
  });

  it('delete has its own domain: a read allow grants no deletion', () => {
    const policy = policyOf({
      paths: { allow: ['~/projects/**'] },
      delete: { allow: ['~/projects/scratch/**'] },
    });
    const scratch = path.join(os.homedir(), 'projects', 'scratch', 'a.txt');
    const code = path.join(os.homedir(), 'projects', 'src', 'a.ts');
    expect(policy.evaluatePath(scratch, true)).toBe('ALLOW');
    // Read/write allowed, deletion still asks.
    expect(policy.evaluatePath(code, true)).toBe('ASK');
  });

  it('delete inherits the paths deny list and the floor', () => {
    const policy = policyOf({
      paths: { deny: ['~/projects/locked/**'] },
      delete: { allow: ['~/projects/**'] },
    });
    expect(policy.evaluatePath(path.join(os.homedir(), 'projects', 'locked', 'a'), true)).toBe(
      'DENY',
    );
    expect(policy.evaluatePath(FLOOR_PATHS[0], true)).toBe('DENY');
  });

  it('commands: deny regex beats allow, empty command denies', () => {
    const policy = policyOf({
      commands: { allow: ['^git '], deny: ['rm\\s+-rf'] },
    });
    expect(policy.evaluateCommand('git status')).toBe('ALLOW');
    expect(policy.evaluateCommand('git push && rm -rf /')).toBe('DENY');
    expect(policy.evaluateCommand('ls -la')).toBe('ASK');
    expect(policy.evaluateCommand('   ')).toBe('DENY');
  });
});

describe('canonicalize', () => {
  it('collapses .. traversal so a deny rule cannot be bypassed', async () => {
    const traversed = path.join(os.homedir(), 'foo', '..', '.ssh', 'id_rsa');
    const canonical = await canonicalize(traversed, WORKDIR);
    expect(canonical).toBe(path.join(os.homedir(), '.ssh', 'id_rsa'));
  });

  it('resolves relative paths against the workdir', async () => {
    const canonical = await canonicalize('src/x.ts', WORKDIR);
    expect(canonical).toBe(path.join(WORKDIR_REAL, 'src', 'x.ts'));
  });

  it('resolves symlinked parents for not-yet-existing targets', async () => {
    // link -> real-dir; writing link/new-file must canonicalize into
    // real-dir — a symlinked parent must not mask the target location
    // (foot-sandbox.md §5.1).
    const real = path.join(WORKDIR, 'real-dir');
    await mkdir(real, { recursive: true });
    const link = path.join(WORKDIR, 'dir-link');
    await rm(link, { force: true });
    await symlink(real, link);
    const canonical = await canonicalize(path.join(link, 'new-file'), WORKDIR);
    expect(canonical).toBe(path.join(await realpath(real), 'new-file'));
  });
});

describe('loadPolicy', () => {
  it('a corrupt file yields an empty (fail-safe ASK) policy', async () => {
    const file = path.join(WORKDIR, 'corrupt.yaml');
    await writeFile(file, ':not: [valid yaml', 'utf-8');
    const policy = await loadPolicy(file, WORKDIR);
    expect(policy.evaluatePath(path.join(WORKDIR, 'x'), false)).toBe('ASK');
  });
});

type PolicyShape = ConstructorParameters<typeof PermissionPolicy>[2];

function policyOf(permissions: PolicyShape['permissions']): PermissionPolicy {
  return new PermissionPolicy(path.join(WORKDIR, 'unused.yaml'), WORKDIR, { permissions });
}

beforeAll(async () => {
  await rm(WORKDIR, { recursive: true, force: true });
  await mkdir(WORKDIR, { recursive: true });
  WORKDIR_REAL = await realpath(WORKDIR);
});

afterAll(async () => {
  await rm(WORKDIR, { recursive: true, force: true });
});
