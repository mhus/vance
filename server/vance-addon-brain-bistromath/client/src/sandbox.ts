import { IframeTransport, type GuestTransport } from './sandboxTransport';

/**
 * The sandbox: a long-running program, and the protocol that talks to it.
 *
 * <p>Where the program actually runs is {@link GuestTransport}'s business —
 * today a null-origin iframe, later possibly QuickJS. Everything here is
 * engine-agnostic: the message protocol, the call queue, the watchdog, and the
 * three lifecycle hooks.
 *
 * ## Lifetime
 *
 * One sandbox per open app, created on mount and disposed on unmount. Module
 * state in the program survives clicks, because this is one program that keeps
 * running — not one invocation per event. Guest calls are serialised, so no
 * handler starts before `init()` has finished.
 *
 * ## The watchdog
 *
 * An iframe cannot be interrupted the way QuickJS can be starved of fuel, so a
 * runaway loop is caught by silence rather than by elapsed time: every host
 * call re-arms the timer. Time spent waiting for a document read is the host's
 * and is not held against the program.
 */

/** What the host lets the guest do. Everything else is unreachable. */
export interface SandboxHost {
  /** `vance.state.set(key, value)` */
  stateSet(key: string, value: unknown): void;
  /** `vance.state.get(key)` — what the program, or a form, last put there. */
  stateGet(key: string): unknown;
  /** `vance.documents.list(path)` */
  documentsList(path: string): Promise<unknown>;
  /** `vance.documents.read(path)` */
  documentsRead(path: string): Promise<unknown>;
  /** `vance.documents.write(path, content, opts?)` */
  documentsWrite(path: string, content: unknown, opts?: unknown): Promise<unknown>;
  /** `vance.documents.create(path, content)` */
  documentsCreate(path: string, content: unknown): Promise<unknown>;
  /** `vance.documents.delete(path)` */
  documentsDelete(path: string): Promise<unknown>;
  /** `vance.app.current()` — what about the app can still change while it runs. */
  appCurrent(): unknown;
  /** `vance.view.patch(id, changes)` — change how a widget looks, or hide it. */
  viewPatch(id: string, changes: unknown): void;
  /** `vance.view.reset(id?)` — undo one widget's patch, or all of them. */
  viewReset(id?: string): void;
  /** `vance.ui.notify(text, severity)` */
  uiNotify(text: string, severity?: string): void;
  /** `vance.ui.show(handle)` */
  uiShow(handle: string): void;
}

export interface SandboxOptions {
  host: SandboxHost;
  /** Reported when the program throws, or when the watchdog fires. */
  onError: (message: string) => void;
  /** Watchdog window per call. */
  timeoutMs?: number;
  /** How long teardown waits for work already in flight. */
  drainMs?: number;
  /** Where the program runs. Defaults to a null-origin iframe. */
  transport?: GuestTransport;
  /**
   * Facts about the app that cannot change while it is open — its folder, its
   * project, the tenant, who is looking. Handed over **before** the program is
   * evaluated and frozen there, so `vance.app.folder` is a plain read with no
   * await and no chance of going stale.
   *
   * <p>Separate from {@link SandboxHost#appCurrent} on purpose: a constant that
   * is served by a call invites the reader to wonder when it changes. These do
   * not — a different project means a different document and a fresh mount.
   */
  context?: Record<string, unknown>;
  /**
   * Where the guest is shown, for an app whose view declares a drawing
   * surface. Omitted, the guest runs hidden — which is every app that only
   * uses widgets, and every test.
   */
  mount?: HTMLElement | null;
}

const DEFAULT_TIMEOUT_MS = 5000;

/**
 * How long teardown waits for work already in flight.
 *
 * <p>Short on purpose. An ordinary handler finishes in milliseconds, so this
 * lets it land instead of being cut mid-write; anything slower is not worth
 * delaying a teardown the reader has already walked away from.
 */
const DEFAULT_DRAIN_MS = 1500;

/**
 * The functions the runtime calls **if the program defines them**.
 *
 * <p>All three are optional, and their presence is asked about **once**, right
 * after the program is evaluated — not discovered from a failed call. The
 * earlier version invoked and matched the error text against
 * "no function named …", which is a string comparison standing in for a fact
 * the guest can simply be asked.
 */
export const HOOKS = [
  'init',
  'shutdown',
  'onBeforeUnload',
  'onDocumentChanged',
  'onViewOpened',
] as const;

interface Waiter {
  resolve: (value: unknown) => void;
  reject: (e: Error) => void;
  timer: ReturnType<typeof setTimeout>;
  /** Re-armed whenever the guest shows a sign of life. */
  rearm: () => void;
}

export class Sandbox {
  private readonly opts: SandboxOptions;
  private readonly timeoutMs: number;
  private readonly drainMs: number;
  private readonly transport: GuestTransport;
  private readonly context?: Record<string, unknown>;
  private readonly waiters = new Map<string, Waiter>();

  private started = false;
  private ready: Promise<void> | null = null;
  private markReady: (() => void) | null = null;
  private seq = 0;
  private disposed = false;
  /** Set the moment teardown starts, so nothing new gets queued behind it. */
  private disposing = false;

  /** Which of {@link HOOKS} the program actually defines. Asked once. */
  private readonly hooks = new Set<string>();

  /**
   * The program's last answer to "would leaving lose something?".
   *
   * <p>Cached, and that is not an optimisation. `beforeunload` must decide
   * **synchronously** while the guest is only reachable asynchronously, so the
   * answer has to already be here when the event fires. It is refreshed after
   * every guest call, which is exactly when it can have changed: a program's
   * state only moves because its own code ran.
   */
  private warnOnLeave = false;

  /**
   * Guest calls run one at a time, in order.
   *
   * <p>Without this, an async `init` yields and the guest happily accepts the
   * next message — so a click during startup could finish *before* `init` did
   * and read module state that was not set up yet.
   */
  private queue: Promise<unknown> = Promise.resolve();

  /**
   * Host calls the guest is currently waiting on.
   *
   * <p>The watchdog must not fire while this is non-zero. Re-arming when a call
   * *arrives* and when its answer *goes out* is not enough: between those two
   * moments the host is doing the work, and a document read against a slow
   * source produces exactly that silence. The first version of this fix did
   * only the two re-arms and still tore a reading program down — found by the
   * test, not by a browser.
   */
  private outstandingHostCalls = 0;

  private readonly onPageHide: () => void;
  private readonly onBeforeUnload: (e: BeforeUnloadEvent) => void;

  constructor(opts: SandboxOptions) {
    this.opts = opts;
    this.timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.drainMs = opts.drainMs ?? DEFAULT_DRAIN_MS;
    this.transport = opts.transport ?? new IframeTransport(opts.mount);
    this.context = opts.context;

    // The page going away is the case nothing else covers: no Vue hook runs and
    // the guest dies with the document. Best effort and it cannot be more — the
    // browser will not wait for a promise here, so a synchronous `shutdown` may
    // run and an async one will not finish.
    this.onPageHide = () => {
      if (!this.hooks.has('shutdown')) return;
      this.transport.post({ t: 'invoke', id: 'unload', fn: 'shutdown' });
    };

    // The browser shows its own generic wording and nothing else; a custom text
    // has not been possible for years, and the prompt is skipped entirely when
    // the reader never interacted with the page. A courtesy, never a guarantee.
    this.onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (!this.warnOnLeave) return;
      e.preventDefault();
      e.returnValue = '';
    };
  }

  get alive(): boolean {
    return this.started && !this.disposing;
  }

  /** Whether the program defines this hook. */
  has(hook: string): boolean {
    return this.hooks.has(hook);
  }

  /** The program's last answer to `onBeforeUnload()`. */
  get warnsOnLeave(): boolean {
    return this.warnOnLeave;
  }

  /**
   * Bring the guest up, evaluate the program, ask which hooks it has, run
   * `init()` if it has one.
   */
  async start(programSource: string | null, sourceName?: string): Promise<void> {
    return this.startAll(
      programSource && programSource.trim()
        ? [{ path: sourceName ?? 'program', source: programSource }]
        : [],
    );
  }

  /**
   * Bring the guest up and evaluate several documents, in order.
   *
   * <p>**Joined into one evaluation, not one per file** — and that was a
   * correction, measured rather than reasoned. One eval per file gives each its
   * own `sourceURL`, which is better for blame; but in an *indirect* eval only
   * `var` and function declarations reach the global object, while `let`,
   * `const` and `class` go into a lexical scope that dies with that call. So a
   * library written the way anyone writes JavaScript today —
   * `const core = {…}` — was simply invisible to the next file, and the error
   * was `core is not defined` with nothing pointing at the cause.
   *
   * <p>Joining also makes a collision **loud**: two libraries declaring the
   * same `const` is a SyntaxError naming it, where separate evals would have
   * silently shadowed.
   *
   * <p>The price is that one `sourceURL` covers everything, so a runtime stack
   * trace names the bundle. {@link blameFile} buys most of that back: when the
   * joined evaluation fails, each file is evaluated alone to find which one
   * does not parse. That path only runs on failure, so it costs nothing when
   * things work.
   */
  async startAll(sources: { path: string; source: string }[]): Promise<void> {
    if (this.started) throw new Error('sandbox already started');
    this.started = true;
    this.ready = new Promise<void>((resolve) => {
      this.markReady = resolve;
    });

    this.transport.start((message) => this.handle(message));
    // Guarded: a non-browser host has no page lifecycle, and the sandbox is
    // otherwise environment-agnostic above the transport.
    if (typeof window !== 'undefined') {
      window.addEventListener('pagehide', this.onPageHide);
      window.addEventListener('beforeunload', this.onBeforeUnload);
    }

    // The whole startup claims its place in the queue **synchronously**, before
    // this method yields. Awaiting first and enqueuing afterwards left a gap:
    // a click landing in it queued ahead of the evaluation and would have been
    // sent to a guest that had not seen the program yet. Everything below runs
    // inside one slot, so it uses `send` directly — re-entering the queue from
    // within it would deadlock.
    return this.enqueue(async () => {
      await this.ready;

      // Before any program code: the constants have to be there when the first
      // top-level line runs, not merely before the first handler.
      if (this.context) await this.send('context', { app: this.context });

      const files = sources.filter((f) => f.source.trim());
      if (files.length > 0) {
        // A banner per file so a reader of the joined source can orient, and a
        // sourceURL so the browser has a name at all instead of "<anonymous>".
        const joined = files
          .map((f) => `/* ── ${f.path} ── */\n${f.source}`)
          .join('\n;\n');
        const name = files.length === 1 ? files[0].path : `${files[files.length - 1].path}+${files.length - 1}`;
        try {
          await this.send('eval', { code: `${joined}\n//# sourceURL=${name}` });
        } catch (e) {
          throw await this.blameFile(files, e);
        }
      }

      const found = await this.send<Record<string, boolean>>('has', { names: [...HOOKS] });
      for (const [name, present] of Object.entries(found ?? {})) {
        if (present) this.hooks.add(name);
      }

      if (this.hooks.has('init')) await this.send('invoke', { fn: 'init' });
      await this.refreshLeaveGuard();
    });
  }

  /**
   * Find which file broke, when the joined evaluation did.
   *
   * <p>Evaluates each file alone until one fails, and names it. A file that
   * only fails *in company* — a redeclared `const`, say — will not fail alone,
   * so the original error is returned unchanged rather than a misleading
   * "everything is fine".
   */
  private async blameFile(
    files: { path: string; source: string }[],
    original: unknown,
  ): Promise<Error> {
    const message = original instanceof Error ? original.message : String(original);
    for (const file of files) {
      try {
        await this.send('eval', { code: `${file.source}\n//# sourceURL=${file.path}` });
      } catch (e) {
        const detail = e instanceof Error ? e.message : String(e);
        return new Error(`${file.path}: ${detail}`);
      }
    }
    return new Error(`${message} (in one of ${files.map((f) => f.path).join(', ')})`);
  }

  /**
   * Call a global function in the guest.
   *
   * <p>Queued behind whatever the guest is already doing — including `init`.
   * The watchdog starts when the call is actually sent, not when it is queued,
   * so waiting one's turn is never mistaken for hanging.
   *
   * <p>Rejects when the function does not exist, and that is right for a
   * *handler*: a view naming a function the program does not define is an
   * authoring mistake and should be visible. The lifecycle hooks are asked
   * about up front instead and skipped when absent.
   */
  invoke(fn: string, args: unknown[] = []): Promise<void> {
    if (this.disposing) return Promise.reject(new Error('the app is closing'));
    return this.enqueue(async () => {
      await this.send('invoke', { fn, args });
      await this.refreshLeaveGuard();
    });
  }

  /**
   * Run a lifecycle hook if the program defines one, and say nothing if not.
   *
   * <p>The opposite of {@link invoke} on purpose: a *handler* the program does
   * not define is an authoring mistake and must be visible, while a hook it
   * chose not to write is the normal case — the app just does not react.
   */
  async invokeHook(hook: string, args: unknown[] = []): Promise<void> {
    if (!this.hooks.has(hook) || this.disposing) return;
    await this.invoke(hook, args);
  }

  /**
   * Run `shutdown()` if there is one, then take the guest down.
   *
   * <p>Work already in flight gets {@link drainMs} to land — cutting a handler
   * off mid-write is what this avoids; waiting on a hung one is what it must
   * not do. The guest goes either way: it has a window, not a veto.
   *
   * <p>`shutdown` may not run at all (a closed browser tab never gets here), so
   * a program must not keep the only copy of anything until then.
   */
  async dispose(): Promise<void> {
    if (this.disposing) return;
    this.disposing = true;
    if (this.started) {
      await Promise.race([
        this.queue.catch(() => undefined),
        new Promise((r) => setTimeout(r, this.drainMs)),
      ]);
      if (this.hooks.has('shutdown')) {
        try {
          // Sent directly, not through the queue: the queue is closed by now,
          // and shutdown carries its own watchdog window.
          await this.send('invoke', { fn: 'shutdown' });
        } catch {
          // Throwing or timed out — both mean the same at this point.
        }
      }
    }
    this.disposed = true;
    if (typeof window !== 'undefined') {
      window.removeEventListener('pagehide', this.onPageHide);
      window.removeEventListener('beforeunload', this.onBeforeUnload);
    }
    for (const [, w] of this.waiters) {
      clearTimeout(w.timer);
      w.reject(new Error('sandbox disposed'));
    }
    this.waiters.clear();
    this.transport.dispose();
  }

  // ── protocol ─────────────────────────────────────────────────────

  /**
   * Re-ask the program whether leaving would lose something.
   *
   * <p>Sent directly rather than through the queue: it runs *inside* a queue
   * slot, and re-entering the queue from there would deadlock.
   *
   * <p>A program that changes its mind from a timer rather than from a handler
   * is not noticed until the next call. An accepted gap — the alternative is
   * polling a sandbox on a schedule, which costs more than the case is worth.
   */
  private async refreshLeaveGuard(): Promise<void> {
    if (!this.hooks.has('onBeforeUnload')) return;
    try {
      this.warnOnLeave = Boolean(await this.send<unknown>('invoke', { fn: 'onBeforeUnload' }));
    } catch {
      // Keep the last answer: a throwing guard is not a reason to decide
      // silently that nothing would be lost.
    }
  }

  /** Chain onto the queue, keeping it alive when a link rejects. */
  private enqueue<T>(task: () => Promise<T>): Promise<T> {
    const run = this.queue.then(task, task);
    // The queue must not inherit this task's rejection, or one failing handler
    // would reject every call after it.
    this.queue = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  private send<T = void>(
    t: 'eval' | 'invoke' | 'has' | 'context',
    payload: Record<string, unknown>,
  ): Promise<T> {
    if (!this.started || this.disposed) {
      return Promise.reject(new Error('sandbox is not running'));
    }
    const id = `h${++this.seq}`;
    return new Promise<T>((resolve, reject) => {
      // The watchdog fires only on *silence*. It is re-armed every time the
      // guest asks the host for something, because time spent waiting for a
      // document read is the host's, not the program's.
      //
      // A program that loops forever *while* calling the host keeps re-arming
      // and is never caught. Deliberate: it is making progress through the
      // bridge, so it is busy rather than hung.
      const fire = () => {
        // Silence while the host is working is the host's silence.
        if (this.outstandingHostCalls > 0) {
          waiter.rearm();
          return;
        }
        this.waiters.delete(id);
        this.opts.onError(
          'The program stopped responding and was stopped. Reload to run it again.',
        );
        void this.dispose();
        reject(new Error('program timed out'));
      };
      const waiter: Waiter = {
        resolve: resolve as (value: unknown) => void,
        reject,
        timer: setTimeout(fire, this.timeoutMs),
        rearm: () => {},
      };
      waiter.rearm = () => {
        clearTimeout(waiter.timer);
        waiter.timer = setTimeout(fire, this.timeoutMs);
      };
      this.waiters.set(id, waiter);
      this.transport.post({ t, id, ...payload });
    });
  }

  private handle(raw: unknown): void {
    const m = raw as Record<string, unknown> | null;
    if (!m || typeof m !== 'object') return;

    if (m.t === 'ready') {
      this.markReady?.();
      return;
    }

    if (m.t === 'done') {
      const id = String(m.id);
      const w = this.waiters.get(id);
      if (!w) return;
      this.waiters.delete(id);
      clearTimeout(w.timer);
      if (m.error) w.reject(new Error(String(m.error)));
      else w.resolve(m.value);
      return;
    }

    if (m.t === 'call') {
      // A sign of life: the program is running, not spinning.
      for (const [, w] of this.waiters) w.rearm();
      this.outstandingHostCalls++;
      void this.serve(String(m.id), String(m.method), Array.isArray(m.args) ? m.args : []);
    }
  }

  /** Answer one host call. Every rejection travels as a message, never as a throw. */
  private async serve(id: string, method: string, args: unknown[]): Promise<void> {
    let answered = false;
    const post = (ok: boolean, value?: unknown, message?: string) => {
      if (answered) return;
      answered = true;
      this.outstandingHostCalls--;
      // Re-arm on the way back too: the guest resumes here, so the clock on the
      // pending invoke should start from now rather than from before the read.
      for (const [, w] of this.waiters) w.rearm();
      this.transport.post({ t: 'result', id, ok, value, message });
    };
    try {
      const host = this.opts.host;
      switch (method) {
        case 'state.set':
          host.stateSet(String(args[0]), args[1]);
          post(true);
          break;
        case 'state.get':
          post(true, host.stateGet(String(args[0])));
          break;
        case 'documents.list':
          post(true, await host.documentsList(String(args[0])));
          break;
        case 'documents.read':
          post(true, await host.documentsRead(String(args[0])));
          break;
        case 'documents.write':
          post(true, await host.documentsWrite(String(args[0]), args[1], args[2]));
          break;
        case 'documents.create':
          post(true, await host.documentsCreate(String(args[0]), args[1]));
          break;
        case 'documents.delete':
          post(true, await host.documentsDelete(String(args[0])));
          break;
        case 'app.current':
          post(true, host.appCurrent());
          break;
        case 'view.patch':
          host.viewPatch(String(args[0]), args[1]);
          post(true, null);
          break;
        case 'view.reset':
          host.viewReset(args[0] === undefined || args[0] === null
            ? undefined : String(args[0]));
          post(true, null);
          break;
        case 'ui.notify':
          host.uiNotify(String(args[0]), args[1] === undefined ? undefined : String(args[1]));
          post(true);
          break;
        case 'ui.show':
          host.uiShow(String(args[0]));
          post(true);
          break;
        default:
          // Named rather than ignored: a typo in `vance.documnets.read` would
          // otherwise hang the program on a promise nobody settles.
          post(false, undefined, `no such host function: ${method}`);
      }
    } catch (e) {
      post(false, undefined, e instanceof Error ? e.message : String(e));
    }
  }
}
