/**
 * Wire envelope used by the Brain's WebSocket protocol.
 *
 * See `specification/websocket-protokoll.md` §1 for the contract:
 *   - `type` is always present
 *   - `id` is set by the sender of a request and copied to `replyTo` on the response
 *   - `data` is the type-specific payload
 */
export interface WebSocketEnvelope<T = unknown> {
  id?: string;
  replyTo?: string;
  type: string;
  data?: T;
}

/** Server-side error frame payload — matches `de.mhus.vance.api.ws.ErrorData`. */
export interface WireErrorData {
  errorCode: number;
  errorMessage?: string;
  /**
   * Optional machine-readable discriminator — see `ErrorData.reason` on the
   * server. Lets the client branch on ambiguous codes (notably the several
   * distinct `409` conflict situations) without parsing the message string.
   */
  reason?: string;
}

/**
 * Error thrown when the brain replies with an `error` frame to a
 * request. Carries the HTTP-style status code so the chat editor can
 * distinguish a 409 (occupied) from a 404 (missing) without parsing
 * the message string. `reason` carries the optional machine-readable
 * discriminator (e.g. `session_bound_elsewhere`).
 */
export class WebSocketRequestError extends Error {
  constructor(
    public readonly errorCode: number,
    public readonly type: string,
    message: string,
    public readonly reason?: string,
  ) {
    super(message);
    this.name = 'WebSocketRequestError';
  }
}

/** Thrown when the connection closes before the request was answered. */
export class WebSocketClosedError extends Error {
  constructor(message = 'WebSocket connection closed') {
    super(message);
    this.name = 'WebSocketClosedError';
  }
}
