import { afterEach, describe, expect, it, vi } from 'vitest';
import { HOOKS, Sandbox, type SandboxHost } from './sandbox';
import type { GuestTransport } from './sandboxTransport';

/**
 * The sandbox protocol, without a browser.
 *
 * <p>These tests exist because every defect found while building this lived
 * **above** the transport: a watchdog measuring elapsed time instead of
 * silence, a click overtaking `init`, hooks detected by matching an error
 * string, a teardown cutting work off mid-flight. None of them needed an
 * iframe to reproduce — they needed the protocol driven by hand, which is what
 * {@link FakeGuest} is for.
 */

interface Msg {
  t: string;
  id: string;
  [k: string]: unknown;
}

/**
 * A guest the test drives.
 *
 * <p>Replies synchronously by default, which is safe because the host
 * registers its waiter *before* posting. A test that wants silence — the
 * watchdog cases — replaces {@link onPost}.
 */
class FakeGuest implements GuestTransport {
  readonly posted: Msg[] = [];
  disposed = false;
  onPost: ((m: Msg) => void) | null = null;
  private handler: ((m: unknown) => void) | null = null;

  start(onMessage: (message: unknown) => void): void {
    this.handler = onMessage;
    // A real guest announces itself the moment its bootstrap runs. Safe to do
    // synchronously: the host arms the ready-promise before starting us.
    this.emit({ t: 'ready' });
  }

  post(message: unknown): void {
    const m = message as Msg;
    this.posted.push(m);
    this.onPost?.(m);
  }

  dispose(): void {
    this.disposed = true;
  }

  emit(message: unknown): void {
    this.handler?.(message);
  }

  /** Every `invoke` the host sent, in order. */
  invocations(): string[] {
    return this.posted.filter((m) => m.t === 'invoke').map((m) => String(m.fn));
  }
}

/** Answer the handshake and every invoke, so a test can get to its own case. */
function autoRespond(
  guest: FakeGuest,
  opts: { hooks?: string[]; leave?: boolean; onInvoke?: (m: Msg) => boolean } = {},
): void {
  const hooks = opts.hooks ?? [];
  guest.onPost = (m) => {
    if (m.t === 'eval') {
      guest.emit({ t: 'done', id: m.id });
      return;
    }
    if (m.t === 'has') {
      const map: Record<string, boolean> = {};
      for (const h of HOOKS) map[h] = hooks.includes(h);
      guest.emit({ t: 'done', id: m.id, value: map });
      return;
    }
    if (m.t === 'invoke') {
      // A test may take over one invocation; returning true means "handled".
      if (opts.onInvoke?.(m)) return;
      if (m.fn === 'onBeforeUnload') {
        guest.emit({ t: 'done', id: m.id, value: opts.leave ?? false });
        return;
      }
      guest.emit({ t: 'done', id: m.id });
    }
  };
}

function makeHost(overrides: Partial<SandboxHost> = {}): SandboxHost {
  return {
    stateSet: vi.fn(),
    documentsList: vi.fn().mockResolvedValue([]),
    stateGet: vi.fn().mockReturnValue(undefined),
    viewPatch: vi.fn(),
    viewReset: vi.fn(),
    documentsRead: vi.fn().mockResolvedValue(''),
    documentsWrite: vi.fn().mockResolvedValue(undefined),
    documentsCreate: vi.fn().mockResolvedValue(undefined),
    documentsDelete: vi.fn().mockResolvedValue(undefined),
    uiNotify: vi.fn(),
    uiShow: vi.fn(),
    ...overrides,
  };
}

afterEach(() => {
  vi.useRealTimers();
});

// ── hooks ──────────────────────────────────────────────────────────

describe('lifecycle hooks', () => {
  it('asks once which hooks exist instead of calling and reading the error', async () => {
    const guest = new FakeGuest();
    autoRespond(guest, { hooks: ['init'] });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });

    await box.start('code', 'main.js');

    expect(guest.posted.filter((m) => m.t === 'has')).toHaveLength(1);
    expect(box.has('init')).toBe(true);
    expect(box.has('shutdown')).toBe(false);
  });

  it('does not call a hook the program does not define', async () => {
    const guest = new FakeGuest();
    autoRespond(guest, { hooks: [] });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });

    await box.start('code', 'main.js');

    expect(guest.invocations()).toEqual([]);
  });

  it('runs init when it exists', async () => {
    const guest = new FakeGuest();
    autoRespond(guest, { hooks: ['init'] });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });

    await box.start('code', 'main.js');

    expect(guest.invocations()).toEqual(['init']);
  });

  it('evaluates the program with a sourceURL so errors name the document', async () => {
    const guest = new FakeGuest();
    autoRespond(guest);
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });

    await box.start('function a(){}', 'apps/x/main.js');

    const evalMsg = guest.posted.find((m) => m.t === 'eval');
    expect(String(evalMsg?.code)).toContain('//# sourceURL=apps/x/main.js');
  });

  it('starts a program-less app without evaluating anything', async () => {
    const guest = new FakeGuest();
    autoRespond(guest);
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });

    await box.start(null);

    expect(guest.posted.filter((m) => m.t === 'eval')).toHaveLength(0);
  });
});

// ── the queue ──────────────────────────────────────────────────────

describe('serialisation', () => {
  /**
   * The bug this pins: an async `init` yields, the guest accepts the next
   * message, and a click during startup finishes *before* `init` did — reading
   * module state nobody set up yet. Only reproducible under timing, which is
   * why it needs a test rather than a look.
   */
  it('runs no handler before init has answered', async () => {
    const guest = new FakeGuest();
    let releaseInit: (() => void) | null = null;
    autoRespond(guest, {
      hooks: ['init'],
      onInvoke: (m) => {
        if (m.fn !== 'init') return false;
        releaseInit = () => guest.emit({ t: 'done', id: m.id });
        return true; // held open
      },
    });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });

    const starting = box.start('code', 'main.js');
    const clicked = box.invoke('hello');

    // Wait until init is genuinely out — the handshake takes several promise
    // hops, and asserting after a fixed number of them tests the flush count
    // rather than the ordering.
    await vi.waitFor(() => expect(guest.invocations()).toContain('init'));
    // Then give the queue every chance to let `hello` through wrongly.
    for (let i = 0; i < 10; i++) await Promise.resolve();

    expect(guest.invocations()).toEqual(['init']);

    releaseInit!();
    await starting;
    await clicked;
    expect(guest.invocations()).toEqual(['init', 'hello']);
  });

  it('keeps the queue usable after a handler fails', async () => {
    const guest = new FakeGuest();
    autoRespond(guest, {
      onInvoke: (m) => {
        if (m.fn !== 'boom') return false;
        guest.emit({ t: 'done', id: m.id, error: 'kaboom' });
        return true;
      },
    });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');

    await expect(box.invoke('boom')).rejects.toThrow('kaboom');
    await expect(box.invoke('fine')).resolves.toBeUndefined();
  });

  it('reports a handler the program does not define', async () => {
    const guest = new FakeGuest();
    autoRespond(guest, {
      onInvoke: (m) => {
        guest.emit({ t: 'done', id: m.id, error: `no function named ${m.fn}` });
        return true;
      },
    });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');

    await expect(box.invoke('missing')).rejects.toThrow('no function named missing');
  });
});

// ── the watchdog ───────────────────────────────────────────────────

describe('watchdog', () => {
  it('stops a program that goes silent', async () => {
    vi.useFakeTimers();
    const guest = new FakeGuest();
    autoRespond(guest);
    const onError = vi.fn();
    const box = new Sandbox({ host: makeHost(), onError, transport: guest, timeoutMs: 1000 });
    await box.start('code');

    guest.onPost = null; // the guest stops answering
    const hung = box.invoke('spin');
    await vi.advanceTimersByTimeAsync(1500);

    await expect(hung).rejects.toThrow('timed out');
    expect(onError).toHaveBeenCalledWith(expect.stringContaining('stopped responding'));
    expect(guest.disposed).toBe(true);
  });

  /**
   * The defect this pins cost nothing only because it was found before a slow
   * source hit it: the watchdog measured elapsed time, so a document read
   * against a mount slower than the window was reported as an endless loop and
   * the program was torn down mid-read. It fires on **silence** now — a host
   * call re-arms it.
   */
  it('does not count time the host spent answering a call', async () => {
    vi.useFakeTimers();
    const guest = new FakeGuest();
    autoRespond(guest);
    // A genuinely slow source: the host holds the read open.
    let releaseRead: ((v: unknown) => void) | null = null;
    const host = makeHost({
      documentsRead: vi.fn(
        () =>
          new Promise((resolve) => {
            releaseRead = resolve;
          }),
      ),
    });
    const onError = vi.fn();
    const box = new Sandbox({ host, onError, transport: guest, timeoutMs: 1000 });
    await box.start('code');

    guest.onPost = (m) => {
      if (m.t === 'invoke') {
        guest.emit({ t: 'call', id: 'c1', method: 'documents.read', args: ['slow.yaml'] });
      }
    };
    const reading = box.invoke('load');
    await vi.advanceTimersByTimeAsync(5000);

    // Five times the window, and the program is not blamed for it.
    expect(onError).not.toHaveBeenCalled();

    releaseRead!({ ok: true });
    await vi.advanceTimersByTimeAsync(0);
    const invokeMsg = guest.posted.find((m) => m.t === 'invoke')!;
    guest.emit({ t: 'done', id: invokeMsg.id });

    await expect(reading).resolves.toBeUndefined();
  });

  /**
   * The counter must come back down: a program that made one call and then
   * span forever is still hung, and would otherwise be immune.
   */
  it('still catches a program that goes silent after a completed call', async () => {
    vi.useFakeTimers();
    const guest = new FakeGuest();
    autoRespond(guest);
    const onError = vi.fn();
    const box = new Sandbox({ host: makeHost(), onError, transport: guest, timeoutMs: 1000 });
    await box.start('code');

    guest.onPost = (m) => {
      if (m.t === 'invoke') {
        // One call, answered, then nothing ever again.
        guest.emit({ t: 'call', id: 'c1', method: 'state.set', args: ['k', 1] });
      }
    };
    const spinning = box.invoke('load');
    await vi.advanceTimersByTimeAsync(1500);

    await expect(spinning).rejects.toThrow('timed out');
    expect(onError).toHaveBeenCalledWith(expect.stringContaining('stopped responding'));
  });
});

// ── the host surface ───────────────────────────────────────────────

describe('host calls', () => {
  it('routes a call to the host and answers with its result', async () => {
    const guest = new FakeGuest();
    autoRespond(guest);
    const host = makeHost({ documentsRead: vi.fn().mockResolvedValue({ a: 1 }) });
    const box = new Sandbox({ host, onError: vi.fn(), transport: guest });
    await box.start('code');

    guest.emit({ t: 'call', id: 'c1', method: 'documents.read', args: ['x.yaml'] });
    await vi.waitFor(() => expect(guest.posted.some((m) => m.t === 'result')).toBe(true));

    expect(host.documentsRead).toHaveBeenCalledWith('x.yaml');
    const result = guest.posted.find((m) => m.t === 'result')!;
    expect(result).toMatchObject({ ok: true, value: { a: 1 } });
  });

  it('answers a rejected host call instead of leaving the guest waiting', async () => {
    const guest = new FakeGuest();
    autoRespond(guest);
    const host = makeHost({ documentsRead: vi.fn().mockRejectedValue(new Error('gone')) });
    const box = new Sandbox({ host, onError: vi.fn(), transport: guest });
    await box.start('code');

    guest.emit({ t: 'call', id: 'c1', method: 'documents.read', args: ['x.yaml'] });
    await vi.waitFor(() => expect(guest.posted.some((m) => m.t === 'result')).toBe(true));

    expect(guest.posted.find((m) => m.t === 'result')).toMatchObject({ ok: false, message: 'gone' });
  });

  /**
   * A typo like `vance.documnets.read` must not hang the program on a promise
   * nobody settles — the guest is told the name is wrong.
   */
  it('names an unknown host function rather than ignoring the call', async () => {
    const guest = new FakeGuest();
    autoRespond(guest);
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');

    guest.emit({ t: 'call', id: 'c1', method: 'documnets.read', args: ['x'] });
    await vi.waitFor(() => expect(guest.posted.some((m) => m.t === 'result')).toBe(true));

    expect(guest.posted.find((m) => m.t === 'result')).toMatchObject({
      ok: false,
      message: expect.stringContaining('no such host function'),
    });
  });

  it('passes a write through with its content and options', async () => {
    const guest = new FakeGuest();
    autoRespond(guest);
    const host = makeHost();
    const box = new Sandbox({ host, onError: vi.fn(), transport: guest });
    await box.start('code');

    guest.emit({
      t: 'call', id: 'c1', method: 'documents.write',
      args: ['rows/1.yaml', { status: 'paid' }, { force: true }],
    });
    await vi.waitFor(() => expect(guest.posted.some((m) => m.t === 'result')).toBe(true));

    expect(host.documentsWrite).toHaveBeenCalledWith(
      'rows/1.yaml', { status: 'paid' }, { force: true },
    );
  });

  /**
   * A refused write must reach the program as a rejection it can catch — not
   * as a silence, and not as a success. This is the whole point of the
   * conditional write: the program gets to re-read and decide.
   */
  it('hands a refused write back to the program as an error', async () => {
    const guest = new FakeGuest();
    autoRespond(guest);
    const host = makeHost({
      documentsWrite: vi.fn().mockRejectedValue(new Error("'rows/1.yaml' changed since read")),
    });
    const box = new Sandbox({ host, onError: vi.fn(), transport: guest });
    await box.start('code');

    guest.emit({ t: 'call', id: 'c1', method: 'documents.write', args: ['rows/1.yaml', {}] });
    await vi.waitFor(() => expect(guest.posted.some((m) => m.t === 'result')).toBe(true));

    expect(guest.posted.find((m) => m.t === 'result')).toMatchObject({
      ok: false,
      message: expect.stringContaining('changed since read'),
    });
  });
});

// ── hooks with arguments ───────────────────────────────────────────

describe('invokeHook', () => {
  it('passes its arguments through to the guest', async () => {
    const guest = new FakeGuest();
    autoRespond(guest, { hooks: ['onDocumentChanged'] });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');

    await box.invokeHook('onDocumentChanged', [['a.yaml', 'b.yaml']]);

    const call = guest.posted.find((m) => m.t === 'invoke' && m.fn === 'onDocumentChanged');
    expect(call).toBeDefined();
    expect(call!.args).toEqual([['a.yaml', 'b.yaml']]);
  });

  /**
   * A hook the program chose not to write is the normal case, not a mistake —
   * the app simply does not react. That is the opposite of a *handler* a view
   * names and the program lacks, which must be visible.
   */
  it('says nothing when the program has no such hook', async () => {
    const guest = new FakeGuest();
    autoRespond(guest);
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');
    const before = guest.posted.length;

    await expect(box.invokeHook('onDocumentChanged', [[]])).resolves.toBeUndefined();

    expect(guest.posted.length).toBe(before);
  });
});

// ── teardown ───────────────────────────────────────────────────────

describe('dispose', () => {
  it('runs shutdown when there is one, then takes the guest down', async () => {
    const guest = new FakeGuest();
    autoRespond(guest, { hooks: ['shutdown'] });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');

    await box.dispose();

    expect(guest.invocations()).toContain('shutdown');
    expect(guest.disposed).toBe(true);
    expect(box.alive).toBe(false);
  });

  it('takes the guest down even without a shutdown hook', async () => {
    const guest = new FakeGuest();
    autoRespond(guest, { hooks: [] });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');

    await box.dispose();

    expect(guest.invocations()).toEqual([]);
    expect(guest.disposed).toBe(true);
  });

  /**
   * A hanging handler must not keep a closed tab alive. The drain is a window,
   * not a veto.
   */
  it('stops waiting for work in flight after the drain window', async () => {
    vi.useFakeTimers();
    const guest = new FakeGuest();
    autoRespond(guest);
    const box = new Sandbox({
      host: makeHost(), onError: vi.fn(), transport: guest,
      timeoutMs: 100_000, drainMs: 500,
    });
    await box.start('code');

    guest.onPost = null; // the handler never answers
    void box.invoke('spin');
    const disposing = box.dispose();
    await vi.advanceTimersByTimeAsync(600);

    await disposing;
    expect(guest.disposed).toBe(true);
  });

  it('refuses new calls once teardown has started', async () => {
    const guest = new FakeGuest();
    autoRespond(guest);
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');

    const disposing = box.dispose();
    await expect(box.invoke('late')).rejects.toThrow('closing');
    await disposing;
  });

  it('is idempotent', async () => {
    const guest = new FakeGuest();
    autoRespond(guest);
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');

    await box.dispose();
    await box.dispose();

    expect(guest.posted.filter((m) => m.t === 'invoke' && m.fn === 'shutdown')).toHaveLength(0);
  });
});

// ── the leave guard ────────────────────────────────────────────────

describe('onBeforeUnload', () => {
  /**
   * `beforeunload` decides synchronously while the guest is only reachable
   * asynchronously, so the answer has to be here already when the event fires.
   * It is refreshed after every call — which is exactly when it can change.
   */
  it('caches the program answer and refreshes it after each call', async () => {
    const guest = new FakeGuest();
    let dirty = false;
    autoRespond(guest, {
      hooks: ['onBeforeUnload'],
      onInvoke: (m) => {
        if (m.fn === 'onBeforeUnload') {
          guest.emit({ t: 'done', id: m.id, value: dirty });
          return true;
        }
        dirty = true; // the handler changed something
        guest.emit({ t: 'done', id: m.id });
        return true;
      },
    });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');

    expect(box.warnsOnLeave).toBe(false);

    await box.invoke('edit');

    expect(box.warnsOnLeave).toBe(true);
  });

  it('is never asked when the program does not define it', async () => {
    const guest = new FakeGuest();
    autoRespond(guest, { hooks: ['init'] });
    const box = new Sandbox({ host: makeHost(), onError: vi.fn(), transport: guest });
    await box.start('code');

    await box.invoke('hello');

    expect(guest.invocations()).toEqual(['init', 'hello']);
  });
});
