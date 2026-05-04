import type { WelcomeData } from '@vance/generated';
import { brainBaseUrl } from '../rest/restClient';
import {
  WebSocketClosedError,
  WebSocketRequestError,
  type WebSocketEnvelope,
  type WireErrorData,
} from './envelope';

/**
 * Public surface of {@link BrainWebSocket}. Exported separately so Vue
 * components can declare prop types without pulling in private class
 * fields — Vue's `UnwrapRef` reduces a ref-stored class instance to
 * its public-shape and the original class type stops fitting structurally.
 */
export type BrainWsApi = Pick<
  BrainWebSocket,
  'send' | 'sendNoReply' | 'on' | 'onClose' | 'closed' | 'close' | 'getWelcome' | 'getTenantId'
>;

/** What the chat editor passes when opening a connection. */
export interface BrainWebSocketOptions {
  tenant: string;
  /**
   * Bearer JWT for the upgrade request. Optional in the web-UI build:
   * the browser ships the {@code vance_access} cookie automatically on
   * same-origin WebSocket upgrades, so cookie-based clients leave this
   * blank. Cross-origin / non-cookie clients (CLI in a browser embed,
   * test harnesses) still pass it as the {@code ?token=} query param.
   */
  jwt?: string;
  /**
   * Connection-profile sent on the WebSocket handshake. Open string —
   * canonical values are `'foot' | 'web' | 'mobile' | 'daemon'` (see
   * `de.mhus.vance.api.ws.Profiles`). For browser-based UI always
   * `'web'`. Drives the recipe-profile-block selection on the brain
   * side (see specification/recipes.md §6a).
   */
  profile: string;
  clientVersion: string;
  /** Optional override for tests. */
  url?: string;
}

type FrameHandler<T = unknown> = (data: T) => void;

interface PendingRequest {
  resolve: (data: unknown) => void;
  reject: (err: Error) => void;
  /** Original request type — used in error messages. */
  type: string;
}

/**
 * Opens a WebSocket to the brain, waits for the `welcome` frame, and
 * exposes a typed request/response + push-subscription API on top.
 *
 * Browser WebSocket cannot send custom HTTP headers, so JWT and
 * client-identity travel as query parameters
 * (`?token=&profile=&clientVersion=`). The server's
 * {@code BrainAccessFilter} and {@code VanceHandshakeInterceptor}
 * accept those for the WS upgrade route — see
 * `specification/websocket-protokoll.md` §2.
 *
 * Lifecycle expectations:
 *  - {@link connect} returns a fresh, ready instance once `welcome` arrives.
 *  - The caller subscribes to server-pushed frames via {@link on}.
 *  - {@link send} resolves with the matching `replyTo` frame, or rejects
 *    with a {@link WebSocketRequestError} (server-sent `error`) or
 *    {@link WebSocketClosedError} (connection died first).
 *  - Reconnects are explicit: when the socket closes, the caller decides
 *    whether to open a fresh instance.
 */
export class BrainWebSocket {
  private readonly socket: WebSocket;
  private readonly handlers = new Map<string, Set<FrameHandler>>();
  private readonly pending = new Map<string, PendingRequest>();
  private readonly closeListeners = new Set<(event: CloseEvent) => void>();

  private requestSeq = 0;
  private readonly tenantId: string;
  private welcome: WelcomeData | null = null;
  private isClosed = false;

  private constructor(socket: WebSocket, tenant: string) {
    this.socket = socket;
    this.tenantId = tenant;
    this.socket.addEventListener('message', this.handleMessage);
    this.socket.addEventListener('close', this.handleClose);
  }

  /**
   * Open a connection and resolve once the server's `welcome` frame
   * has arrived. Rejects if the upgrade fails (HTTP error from the
   * filter) or the socket closes before `welcome`.
   */
  static connect(options: BrainWebSocketOptions): Promise<BrainWebSocket> {
    const url = options.url ?? buildBrainWsUrl(options);
    const socket = new WebSocket(url);
    const instance = new BrainWebSocket(socket, options.tenant);

    return new Promise<BrainWebSocket>((resolve, reject) => {
      let settled = false;

      const onWelcome = (data: WelcomeData): void => {
        if (settled) return;
        settled = true;
        instance.welcome = data;
        resolve(instance);
      };
      instance.on<WelcomeData>('welcome', onWelcome);

      // If the socket dies before we get a welcome, fail the connect.
      const onCloseBeforeReady = (event: CloseEvent): void => {
        if (settled) return;
        settled = true;
        socket.removeEventListener('close', onCloseBeforeReady);
        reject(new WebSocketClosedError(
          `WebSocket closed before welcome (code ${event.code})`));
      };
      socket.addEventListener('close', onCloseBeforeReady);

      // Likewise an explicit error event from the underlying socket.
      socket.addEventListener('error', () => {
        if (settled) return;
        settled = true;
        reject(new WebSocketClosedError('WebSocket error during handshake'));
      }, { once: true });
    });
  }

  /**
   * Send a request frame and wait for its reply (matched on
   * {@code replyTo === id}). Rejects on server `error` reply or socket
   * close.
   */
  send<TRequest, TResponse>(
    type: string,
    data?: TRequest,
  ): Promise<TResponse> {
    if (this.isClosed) {
      return Promise.reject(new WebSocketClosedError());
    }
    const id = `req_${++this.requestSeq}`;
    const envelope: WebSocketEnvelope<TRequest> = { id, type, data };
    return new Promise<TResponse>((resolve, reject) => {
      this.pending.set(id, {
        type,
        resolve: (raw) => resolve(raw as TResponse),
        reject,
      });
      this.socket.send(JSON.stringify(envelope));
    });
  }

  /** Send a frame without expecting a reply (e.g. `logout`). */
  sendNoReply<T>(type: string, data?: T): void {
    if (this.isClosed) return;
    const envelope: WebSocketEnvelope<T> = { type, data };
    this.socket.send(JSON.stringify(envelope));
  }

  /**
   * Subscribe to server-pushed frames of {@code type}. Returns an
   * unsubscribe function. Multiple handlers per type are allowed.
   *
   * Pushed frames have no {@code replyTo} — the dispatcher routes
   * frames with {@code replyTo} to the pending-request map instead.
   */
  on<T = unknown>(type: string, handler: FrameHandler<T>): () => void {
    let set = this.handlers.get(type);
    if (!set) {
      set = new Set();
      this.handlers.set(type, set);
    }
    set.add(handler as FrameHandler);
    return () => set!.delete(handler as FrameHandler);
  }

  /** Subscribe to the underlying socket close event. */
  onClose(handler: (event: CloseEvent) => void): () => void {
    this.closeListeners.add(handler);
    return () => this.closeListeners.delete(handler);
  }

  /** Welcome payload as received during connect. {@code null} only before {@link connect} resolves. */
  getWelcome(): WelcomeData | null {
    return this.welcome;
  }

  /** Tenant id this connection is scoped to. */
  getTenantId(): string {
    return this.tenantId;
  }

  /** Whether the socket has been observed closed (or close() called). */
  closed(): boolean {
    return this.isClosed;
  }

  /** Close the connection. Pending requests are rejected with {@link WebSocketClosedError}. */
  close(code?: number, reason?: string): void {
    if (this.isClosed) return;
    this.socket.close(code, reason);
  }

  // ── internals ───────────────────────────────────────────────────────

  private readonly handleMessage = (event: MessageEvent): void => {
    const raw = typeof event.data === 'string' ? event.data : '';
    if (!raw) return;
    let envelope: WebSocketEnvelope;
    try {
      envelope = JSON.parse(raw) as WebSocketEnvelope;
    } catch {
      // Malformed frame — log to console; protocol violation, but don't
      // crash the editor.
      console.warn('Discarding non-JSON WebSocket frame', raw);
      return;
    }

    // Reply path: a request the client is waiting for.
    if (envelope.replyTo) {
      const pending = this.pending.get(envelope.replyTo);
      if (pending) {
        this.pending.delete(envelope.replyTo);
        if (envelope.type === 'error') {
          const err = envelope.data as WireErrorData | undefined;
          pending.reject(new WebSocketRequestError(
            err?.errorCode ?? 0,
            pending.type,
            err?.errorMessage ?? `Server error on ${pending.type}`));
        } else {
          pending.resolve(envelope.data);
        }
        return;
      }
      // Else fall through — replyTo with no pending request gets
      // dispatched to handlers in case someone wants to observe it.
    }

    // Push path: dispatch to subscribers.
    const subscribers = this.handlers.get(envelope.type);
    if (subscribers && subscribers.size > 0) {
      for (const handler of subscribers) {
        try {
          handler(envelope.data);
        } catch (e) {
          console.error(`WS handler for '${envelope.type}' threw`, e);
        }
      }
    }
  };

  private readonly handleClose = (event: CloseEvent): void => {
    this.isClosed = true;
    for (const [id, pending] of this.pending) {
      pending.reject(new WebSocketClosedError(
        `WebSocket closed (code ${event.code}) — request '${pending.type}' (${id}) abandoned`));
    }
    this.pending.clear();
    for (const listener of this.closeListeners) {
      try {
        listener(event);
      } catch (e) {
        console.error('WS close listener threw', e);
      }
    }
  };
}

/**
 * Construct the WebSocket URL with auth + client-identity passed as
 * query parameters. Same-origin in production; in {@code vite dev} the
 * proxy upgrades to the locally running brain.
 */
function buildBrainWsUrl(options: BrainWebSocketOptions): string {
  const httpBase = brainBaseUrl();
  // Empty string == same origin. Convert http(s):// to ws(s)://.
  const origin = httpBase || `${window.location.protocol}//${window.location.host}`;
  const wsOrigin = origin
    .replace(/^http:\/\//, 'ws://')
    .replace(/^https:\/\//, 'wss://');
  const params = new URLSearchParams({
    profile: options.profile,
    clientVersion: options.clientVersion,
  });
  // Cookie-only callers (web UI same-origin) leave `jwt` empty; the
  // browser ships {@code vance_access} on the upgrade request. Other
  // callers still get the {@code ?token=} fallback for cross-origin
  // or header-less embeds.
  if (options.jwt) {
    params.set('token', options.jwt);
  }
  return `${wsOrigin}/brain/${encodeURIComponent(options.tenant)}/ws?${params}`;
}
