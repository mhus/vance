/**
 * Job-model tests for the per-account exec registry — the parity tests
 * for foot's {@code client_exec_*} behaviour: inline wait, background
 * RUNNING hand-off, status/tail shapes, kill semantics and the
 * deadline watchdog. Spawns real subprocesses (echo / sleep).
 */
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { ExecJobs } from './execOps';

const JOBS_ROOT = path.join(os.tmpdir(), 'desktop-agent-exec-test');
let workdir: string;

describe('ExecJobs', () => {
  beforeAll(async () => {
    await rm(JOBS_ROOT, { recursive: true, force: true });
    workdir = await mkdtemp(path.join(os.tmpdir(), 'exec-workdir-'));
  });

  afterAll(async () => {
    await rm(JOBS_ROOT, { recursive: true, force: true });
    await rm(workdir, { recursive: true, force: true });
  });

  function jobs(): ExecJobs {
    return new ExecJobs(JOBS_ROOT, workdir);
  }

  it('a fast command completes inline with status + output', async () => {
    const result = await jobs().run({ command: 'echo hello' });
    expect(result.status).toBe('COMPLETED');
    expect(result.exitCode).toBe(0);
    expect(String(result.stdout)).toContain('hello');
    expect(typeof result.id).toBe('string');
    expect(String(result.stdoutPath)).toContain(String(result.id));
  });

  it('a non-zero exit reports FAILED with the exit code', async () => {
    const result = await jobs().run({ command: 'echo oops >&2; exit 3' });
    expect(result.status).toBe('FAILED');
    expect(result.exitCode).toBe(3);
    expect(String(result.stderr)).toContain('oops');
  });

  it('a long command hands off as RUNNING and can be polled', async () => {
    const registry = jobs();
    const result = await registry.run({ command: 'sleep 2', waitMs: 200 });
    expect(result.status).toBe('RUNNING');
    const id = String(result.id);
    const status = await registry.status({ id });
    expect(status.status).toBe('RUNNING');
    // Kill it — the poll must then show KILLED, and a second kill is a no-op.
    const kill = await registry.kill({ id });
    expect(kill.killed).toBe(true);
    const after = await registry.status({ id });
    expect(after.status).toBe('KILLED');
    const rekill = await registry.kill({ id });
    expect(rekill.killed).toBe(false);
  });

  it('tail returns the last N lines of a stream, oldest-first', async () => {
    const registry = jobs();
    const run = await registry.run({ command: 'printf "a\\nb\\nc\\n"' });
    const id = String(run.id);
    const tail = await registry.tail({ id, n: 2 });
    expect(tail.stream).toBe('stdout');
    expect(tail.lines).toEqual(['b', 'c']);
  });

  it('stat reports metadata without log bodies', async () => {
    const registry = jobs();
    const run = await registry.run({ command: 'echo stat-me' });
    const stat = await registry.stat({ id: String(run.id) });
    expect(stat.status).toBe('COMPLETED');
    expect(stat.stdout).toBeUndefined();
    expect(stat.stderr).toBeUndefined();
    expect(Number(stat.stdoutBytes)).toBeGreaterThan(0);
    expect(String(stat.stdoutPath)).toContain('stdout.log');
  });

  it('a kill escalates to SIGKILL for a tree that ignores SIGTERM', async () => {
    // Review-20 finding: the shell AND its child trap TERM — a lone SIGTERM
    // to the shell would leave the group running (and writing logs) while
    // the job reports KILLED. The grace escalation must clear the group.
    const registry = new ExecJobs(JOBS_ROOT, workdir, 150);
    const run = await registry.run({
      command: `trap '' TERM; (trap '' TERM; while :; do sleep 1; done) & wait`,
      waitMs: 150,
    });
    const id = String(run.id);
    const pid = (
      registry as unknown as { jobs: Map<string, { process: { pid: number } }> }
    ).jobs.get(id)!.process.pid;

    expect(await registry.kill({ id })).toEqual({ id, killed: true });
    // The SIGTERM-ignoring group survives the signal …
    expect(() => process.kill(-pid, 0)).not.toThrow();
    // … and the SIGKILL escalation clears it.
    await new Promise((resolve) => setTimeout(resolve, 500));
    expect(() => process.kill(-pid, 0)).toThrow();
  }, 10_000);

  it('the deadline watchdog kills an over-running job', async () => {
    const registry = jobs();
    const run = await registry.run({ command: 'sleep 5', waitMs: 100, deadlineSeconds: 1 });
    const id = String(run.id);
    // Wait past the deadline, then the job must be KILLED (not RUNNING).
    await new Promise((resolve) => setTimeout(resolve, 1_400));
    const status = await registry.status({ id });
    expect(status.status).toBe('KILLED');
  });

  it('killAll terminates every running job', async () => {
    const registry = jobs();
    const a = await registry.run({ command: 'sleep 10', waitMs: 100 });
    const b = await registry.run({ command: 'sleep 10', waitMs: 100 });
    registry.killAll();
    expect((await registry.status({ id: String(a.id) })).status).toBe('KILLED');
    expect((await registry.status({ id: String(b.id) })).status).toBe('KILLED');
  });

  it('an unknown job id is a clear error', async () => {
    await expect(jobs().status({ id: 'no-such' })).rejects.toThrow(
      "Unknown client-exec job: 'no-such'",
    );
  });
});
