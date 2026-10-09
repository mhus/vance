/**
 * The {@code exec.*} operations of the desktop agent tools — the TS twin
 * of the foot CLI's {@code client_exec_*} tools
 * (`vance-foot .../tools/exec/`). Parity is the contract: job ids, result
 * shapes ({@code ClientExecJobRenderer} / {@code ClientExecStatTool.render}),
 * the inline-wait cap (~20 s, under the brain's 30 s tool timeout) and
 * the log-file layout (`stdout.log` / `stderr.log` per job dir) mirror
 * the foot implementation.
 *
 * One {@link ExecJobs} instance per account: job ids are scoped to the
 * account whose WebView issued the run, so account A can never poll or
 * kill account B's jobs (planning/desktop-agent-tools.md §8).
 *
 * Known v1 gap: foot posts {@code EXEC_FINISHED} / {@code EXEC_TIMEOUT}
 * events to the user's inbox when a background job settles; the desktop
 * app has no client→brain push channel, so long jobs must be polled via
 * {@code exec.status}.
 */
import { spawn, type ChildProcess } from 'node:child_process';
import { mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

import type { Params } from './fileOps';

/** foot: inline wait default 15 s, cap 20 s (under the brain's 30 s timeout). */
const DEFAULT_WAIT_MS = 15_000;
const MAX_WAIT_MS = 20_000;
/** Renderer cap for inline stdout/stderr — same as foot's INLINE_CHAR_CAP. */
const INLINE_CHAR_CAP = 8_000;
const DEFAULT_TAIL_LINES = 10;
const MAX_TAIL_LINES = 500;
/** foot caps its registry at 32 jobs; finished jobs are pruned first. */
const MAX_JOBS = 32;

type JobStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'KILLED';

interface ExecJob {
  id: string;
  command: string;
  workdir: string;
  jobDir: string;
  startedAt: number;
  lastOutputAt: number;
  finishedAt: number | null;
  exitCode: number | null;
  status: JobStatus;
  process: ChildProcess | null;
  watchdog: ReturnType<typeof setTimeout> | null;
}

/**
 * Per-account job registry. Owns spawning, the inline wait, the deadline
 * watchdog and the log files. {@link killAll} is wired to the app quit
 * so no orphaned subprocesses survive the desktop app.
 */
export class ExecJobs {
  private readonly jobs = new Map<string, ExecJob>();

  constructor(
    private readonly jobsRoot: string,
  /** The account workdir this registry spawns against — readable so the
   *  service layer can detect workdir changes between invokes. */
  readonly workdir: string,
  ) {}

  /** {@code client_exec_run} — spawn, wait inline (capped), render. */
  async run(params: Params): Promise<Record<string, unknown>> {
    const command = params.command;
    if (typeof command !== 'string' || command.trim() === '') {
      throw new Error("'command' is required and must be a non-empty string");
    }
    let waitMs = DEFAULT_WAIT_MS;
    const rawWait = params.waitMs;
    if (typeof rawWait === 'number' && rawWait >= 0) {
      waitMs = rawWait;
    }
    // Never block inline past the brain's tool timeout — background long
    // commands and let client_exec_status poll.
    waitMs = Math.min(waitMs, MAX_WAIT_MS);

    const job = await this.submit(command, params.deadlineSeconds);
    await this.waitFor(job, waitMs);
    return renderJob(job);
  }

  /** {@code client_exec_status} — full job render. */
  async status(params: Params): Promise<Record<string, unknown>> {
    const job = this.requireJob(params.id);
    return renderJob(job);
  }

  /** {@code client_exec_tail} — last N lines of a stream. */
  async tail(params: Params): Promise<Record<string, unknown>> {
    const id = requireId(params.id);
    const job = this.requireJob(id);
    let n = DEFAULT_TAIL_LINES;
    const rawN = params.n;
    if (typeof rawN === 'number') {
      n = Math.min(MAX_TAIL_LINES, Math.max(1, Math.trunc(rawN)));
    }
    const stream = params.stream === 'stderr' ? 'stderr' : 'stdout';
    // Strip one trailing newline before splitting — `wc -l` semantics:
    // 'a\nb\nc\n' has three lines, and tail n=2 returns ['b','c'].
    const text = await this.readLog(job, stream);
    const content = text.endsWith('\n') ? text.slice(0, -1) : text;
    const lines = content === '' ? [] : content.split('\n').slice(-n);
    return { id, stream, lines, returned: lines.length };
  }

  /** {@code client_exec_kill} — SIGTERM the job if still running. */
  async kill(params: Params): Promise<Record<string, unknown>> {
    const id = requireId(params.id);
    const job = this.requireJob(id);
    const killed = this.killJob(job);
    return { id, killed };
  }

  /** {@code client_exec_stat} — metadata-only render (no log content). */
  async stat(params: Params): Promise<Record<string, unknown>> {
    const id = requireId(params.id);
    const job = this.requireJob(id);
    const out: Record<string, unknown> = {
      id: job.id,
      status: job.status,
      command: job.command,
      startedAt: new Date(job.startedAt).toISOString(),
      lastOutputAt: new Date(job.lastOutputAt).toISOString(),
      durationMs: (job.finishedAt ?? Date.now()) - job.startedAt,
      stdoutPath: this.logPath(job, 'stdout'),
      stderrPath: this.logPath(job, 'stderr'),
    };
    if (job.finishedAt !== null) {
      out.finishedAt = new Date(job.finishedAt).toISOString();
    }
    if (job.exitCode !== null) {
      out.exitCode = job.exitCode;
    }
    const [stdoutSize, stderrSize] = await Promise.all([
      this.logSize(job, 'stdout'),
      this.logSize(job, 'stderr'),
    ]);
    out.stdoutBytes = stdoutSize;
    out.stderrBytes = stderrSize;
    if (stdoutSize > 0) out.stdoutMtime = new Date(await this.logMtime(job, 'stdout')).toISOString();
    if (stderrSize > 0) out.stderrMtime = new Date(await this.logMtime(job, 'stderr')).toISOString();
    return out;
  }

  /** Kill every running job — the app-quit hook. Never throws. */
  killAll(): void {
    for (const job of this.jobs.values()) {
      if (job.status === 'RUNNING') this.killJob(job);
    }
  }

  // ─── internals ────────────────────────────────────────────────────

  private async submit(command: string, deadlineSeconds: unknown): Promise<ExecJob> {
    const id = randomUUID().substring(0, 8);
    const jobDir = path.join(this.jobsRoot, id);
    await mkdir(jobDir, { recursive: true });
    const job: ExecJob = {
      id,
      command,
      workdir: this.workdir,
      jobDir,
      startedAt: Date.now(),
      lastOutputAt: Date.now(),
      finishedAt: null,
      exitCode: null,
      status: 'RUNNING',
      process: null,
      watchdog: null,
    };
    this.jobs.set(id, job);
    this.prune();
    await Promise.all([
      writeFile(this.logPath(job, 'stdout'), '', 'utf-8'),
      writeFile(this.logPath(job, 'stderr'), '', 'utf-8'),
    ]);

    const shell = process.platform === 'win32' ? 'cmd.exe' : '/bin/sh';
    const args = process.platform === 'win32' ? ['/c', command] : ['-c', command];
    const child = spawn(shell, args, {
      cwd: this.workdir,
      env: { ...process.env, VANCE_EXEC_ENV: 'container-client' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    job.process = child;
    child.stdout?.on('data', (chunk: Buffer) => {
      void this.appendLog(job, 'stdout', chunk);
      job.lastOutputAt = Date.now();
    });
    child.stderr?.on('data', (chunk: Buffer) => {
      void this.appendLog(job, 'stderr', chunk);
      job.lastOutputAt = Date.now();
    });
    child.on('error', (e) => {
      void this.appendLog(job, 'stderr', `${e.message}\n`);
      this.settle(job, 'FAILED', null);
    });
    child.on('close', (code) => {
      if (job.status === 'RUNNING') {
        this.settle(job, code === 0 ? 'COMPLETED' : 'FAILED', code);
      }
    });

    if (typeof deadlineSeconds === 'number' && deadlineSeconds > 0) {
      job.watchdog = setTimeout(() => {
        // Deadline reached: SIGTERM now, hard-kill after a grace period
        // — the command does NOT finish, output stays partial.
        if (job.status === 'RUNNING') {
          this.killJob(job);
        }
      }, Math.trunc(deadlineSeconds * 1000));
    }
    return job;
  }

  private settle(job: ExecJob, status: JobStatus, exitCode: number | null): void {
    job.status = status;
    job.exitCode = exitCode;
    job.finishedAt = Date.now();
    if (job.watchdog !== null) {
      clearTimeout(job.watchdog);
      job.watchdog = null;
    }
  }

  private killJob(job: ExecJob): boolean {
    if (job.status !== 'RUNNING') return false;
    const killed = job.process?.kill(process.platform === 'win32' ? undefined : 'SIGTERM') ?? false;
    this.settle(job, 'KILLED', job.exitCode);
    return killed;
  }

  private waitFor(job: ExecJob, waitMs: number): Promise<void> {
    if (job.status !== 'RUNNING' || waitMs <= 0) return Promise.resolve();
    return new Promise((resolve) => {
      const started = Date.now();
      const timer = setInterval(() => {
        if (job.status !== 'RUNNING' || Date.now() - started >= waitMs) {
          clearInterval(timer);
          resolve();
        }
      }, 100);
    });
  }

  /** Registry cap: prune oldest finished jobs beyond MAX_JOBS. */
  private prune(): void {
    if (this.jobs.size <= MAX_JOBS) return;
    const finished = [...this.jobs.values()]
      .filter((j) => j.status !== 'RUNNING')
      .sort((a, b) => (a.finishedAt ?? a.startedAt) - (b.finishedAt ?? b.startedAt));
    for (const job of finished) {
      if (this.jobs.size <= MAX_JOBS) break;
      this.jobs.delete(job.id);
      void rm(job.jobDir, { recursive: true, force: true });
    }
  }

  private requireJob(rawId: unknown): ExecJob {
    const id = requireId(rawId);
    const job = this.jobs.get(id);
    if (!job) {
      throw new Error(`Unknown client-exec job: '${id}'`);
    }
    return job;
  }

  private logPath(job: ExecJob, stream: 'stdout' | 'stderr'): string {
    return path.join(job.jobDir, `${stream}.log`);
  }

  private async appendLog(job: ExecJob, stream: 'stdout' | 'stderr', chunk: Buffer | string): Promise<void> {
    const fs = await import('node:fs');
    await new Promise<void>((resolve) => {
      fs.appendFile(this.logPath(job, stream), chunk, () => resolve());
    });
  }

  private async readLog(job: ExecJob, stream: 'stdout' | 'stderr'): Promise<string> {
    try {
      return await readFile(this.logPath(job, stream), 'utf-8');
    } catch {
      return '';
    }
  }

  private async logSize(job: ExecJob, stream: 'stdout' | 'stderr'): Promise<number> {
    return stat(this.logPath(job, stream)).then((s) => s.size).catch(() => 0);
  }

  private async logMtime(job: ExecJob, stream: 'stdout' | 'stderr'): Promise<number> {
    return stat(this.logPath(job, stream)).then((s) => s.mtimeMs).catch(() => 0);
  }
}

/** The flat map {@code client_exec_run}/{@code client_exec_status} return —
 *  {@code ClientExecJobRenderer} twin. */
async function renderJob(job: ExecJob): Promise<Record<string, unknown>> {
  const stdout = await readJobLog(job, 'stdout');
  const stderr = await readJobLog(job, 'stderr');
  const out: Record<string, unknown> = {
    id: job.id,
    status: job.status,
    command: job.command,
    durationMs: (job.finishedAt ?? Date.now()) - job.startedAt,
    lastOutputAt: new Date(job.lastOutputAt).toISOString(),
    stdoutPath: path.join(job.jobDir, 'stdout.log'),
    stderrPath: path.join(job.jobDir, 'stderr.log'),
    stdout: truncate(stdout),
    stderr: truncate(stderr),
  };
  if (job.exitCode !== null) {
    out.exitCode = job.exitCode;
  }
  if (stdout.length > INLINE_CHAR_CAP || stderr.length > INLINE_CHAR_CAP) {
    out.truncated = true;
    out.hint =
      'Output truncated. Re-run with bounded shell commands '
      + "(head -N / tail -N / sed -n 'A,Bp' <path>) against stdoutPath/stderrPath.";
  }
  return out;
}

async function readJobLog(job: ExecJob, stream: 'stdout' | 'stderr'): Promise<string> {
  try {
    return await readFile(path.join(job.jobDir, `${stream}.log`), 'utf-8');
  } catch {
    return '';
  }
}

function truncate(text: string): string {
  return text.length > INLINE_CHAR_CAP ? text.slice(0, INLINE_CHAR_CAP) : text;
}

function requireId(raw: unknown): string {
  if (typeof raw !== 'string' || raw.length === 0) {
    throw new Error("'id' is required");
  }
  return raw;
}

/** The account-scoped jobs root — sibling of the permissions file. */
export function execJobsRoot(accountToolsDir: string): string {
  return path.join(accountToolsDir, 'exec');
}
