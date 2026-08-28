import { ref, type Ref } from 'vue';
import type { BrainWsApi } from '@vance/shared';

// Wire-format mirrors of the brain DTOs. Inlined deliberately for the same
// reason cortex/clientToolService.ts and inbox/inboxClientToolService.ts
// inline them: the upstream @vance/generated package's hand-maintained
// index.ts has to be rebuilt (tsc -b) when new re-exports are added, and we
// don't want the chat page to depend on that build step having run. Shapes
// match the @GenerateTypeScript-annotated Java classes:
// vance-api/de.mhus.vance.api.tools.ToolSpec / .ClientToolInvokeRequest
// / .ClientToolInvokeResponse / .ToolSafety.

type ToolSafety = 'SAFE_PROBE' | 'MUTATING';

interface ToolSpec {
  name: string;
  description: string;
  primary: boolean;
  source?: string;
  paramsSchema: Record<string, unknown>;
  labels: string[];
  allowedProfiles: string[];
  deferred: boolean;
  searchHint: string;
  safety: ToolSafety;
  requiresEngineRoles: string[];
}

interface ClientToolInvokeRequest {
  correlationId: string;
  name: string;
  params: Record<string, unknown>;
}

interface ClientToolInvokeResponse {
  correlationId: string;
  result: Record<string, unknown>;
  error?: string | null;
}

/**
 * The chat page's client-tool surface: one tool, which reads the browser's
 * geolocation on the agent's behalf.
 *
 * <p>The chat session is the one place a client tool can be "always
 * available": Cortex and the inbox register their tools only while their
 * page is open ({@link CortexClientToolService}, {@link InboxClientToolService}),
 * but the chat session is always alive and the reader is always in front of
 * it. Location is the first tool that is not bound to a page — it is bound to
 * the conversation, not to a tab.
 *
 * <p><b>The tool does not block on the permission prompt.</b> The browser
 * geolocation callback is asynchronous and timeless — the reader may accept
 * in five seconds or never — while the client-tool mechanism blocks the LLM
 * sampling loop on a pending future with its own timeout. So this tool never
 * waits for the prompt; it answers within a short local wait and returns a
 * <em>status</em>, not necessarily a location. The status is the result; a
 * non-GRANTED status is not an error (see {@link LocationResult}).
 *
 * <p>The permission state is read from the browser itself
 * ({@code navigator.permissions.query({ name: 'geolocation' })}), which the
 * browser holds per origin across reloads — once granted, a reload does not
 * revoke it. The tool keeps no state of its own: the browser is the cache.
 *
 * <p>Headless / worker clients have no browser geolocation and never reach
 * this service (it is registered only by the web chat page), so there is no
 * "no browser" branch to answer — but {@link UNAVAILABLE} covers the
 * in-browser case where the API is absent or fails.
 *
 * <p>Tool surface today:
 * <ul>
 *   <li>{@code location_get} — the reader's location, or a status.</li>
 * </ul>
 */

/**
 * What the reader's location resolves to. A status rather than a bare
 * error: {@code DECLINED} and {@code UNAVAILABLE} are valid results the agent
 * must not retry, and {@code PENDING} is the only one that invites a retry.
 */
export type LocationStatus = 'GRANTED' | 'DECLINED' | 'PENDING' | 'UNAVAILABLE';

export interface LocationResult {
  status: LocationStatus;
  /** Human-readable reason the agent can relay to the reader. */
  message: string;
  /** Present only when {@link status} is `GRANTED`. */
  latitude?: number;
  longitude?: number;
  /** Accuracy in metres (W3C Geolocation), when the browser supplied it. */
  accuracy?: number;
  /** Unix epoch millis of the reading, when the browser supplied it. */
  timestamp?: number;
}

type ToolHandler = (
  params: Record<string, unknown>,
) => Promise<Record<string, unknown>> | Record<string, unknown>;

/**
 * Local wait for the permission prompt before answering with `PENDING`.
 * Short on purpose — the LLM sampling loop is blocked while this runs, and a
 * longer wait would stall the conversation for a decision that may never come.
 * Must stay below the brain's client-tool timeout (30s) so this answers first.
 */
const PERMISSION_WAIT_MS = 5000;

/**
 * A location tool that the web chat page exposes to the agent.
 *
 * <p>Session-scoped like {@link InboxClientToolService}: attached to the
 * WebSocket whenever the chat session goes live (see {@code ChatSidePanel}'s
 * attach/detach, or the plain {@code /chat} host). One instance per page
 * lifetime, outliving session switches.
 */
export class ChatClientToolService {
  /** Reactive: true while an invocation is in flight. */
  readonly isExecuting: Ref<boolean> = ref(false);

  private invokeUnsub: (() => void) | null = null;
  private inflight = 0;
  private readonly handlers = new Map<string, ToolHandler>();

  /**
   * A location reading in flight, if any. Two concurrent invocations must not
   * fire two permission prompts at the reader — the second waits for the
   * first's result. Held on the instance because it outlives a single call.
   */
  private pendingRequest: Promise<LocationResult> | null = null;

  constructor() {
    this.handlers.set('location_get', (params) => this.locationGet(params));
  }

  /**
   * Push the tool registration and start listening for invocations.
   * Idempotent against a fresh socket — call from each WS open.
   */
  async attach(ws: BrainWsApi): Promise<void> {
    const specs: ToolSpec[] = this.toolSpecs();
    await ws.send('client-tool-register', { tools: specs });
    this.invokeUnsub = ws.on<ClientToolInvokeRequest>(
      'client-tool-invoke',
      (req) => { void this.onInvoke(ws, req); },
    );
  }

  /**
   * Drop the invoke listener. Brain-side registry entries clear out
   * when the WS closes — no explicit "unregister" message needed.
   */
  detach(): void {
    this.invokeUnsub?.();
    this.invokeUnsub = null;
  }

  // ─── Handler routing ─────────────────────────────────────────────

  private async onInvoke(ws: BrainWsApi, req: ClientToolInvokeRequest): Promise<void> {
    const correlationId = req.correlationId;
    const handler = this.handlers.get(req.name);
    let response: ClientToolInvokeResponse;
    if (!handler) {
      response = {
        correlationId,
        result: {},
        error: `Unknown client tool: ${req.name}`,
      };
    } else {
      this.beginExecuting();
      try {
        const result = await handler(req.params ?? {});
        response = { correlationId, result };
      } catch (e) {
        response = {
          correlationId,
          result: {},
          error: e instanceof Error ? e.message : String(e),
        };
      } finally {
        this.endExecuting();
      }
    }
    ws.sendNoReply('client-tool-result', response);
  }

  private beginExecuting(): void {
    this.inflight += 1;
    if (this.inflight === 1) this.isExecuting.value = true;
  }

  private endExecuting(): void {
    this.inflight = Math.max(0, this.inflight - 1);
    if (this.inflight === 0) this.isExecuting.value = false;
  }

  // ─── Tool definitions ───────────────────────────────────────────

  private toolSpecs(): ToolSpec[] {
    return [
      {
        name: 'location_get',
        description:
          'Return the reader\'s current location, or a status if it is not '
          + 'yet available. May trigger a browser location-permission prompt '
          + 'when the permission is not already granted. Returns { status, ... }.\n'
          + '  status "GRANTED"     — location available (latitude, longitude, '
          + 'accuracy, timestamp). This is the answer.\n'
          + '  status "DECLINED"    — the reader declined the permission. Do '
          + 'NOT retry — a second call would not re-ask the browser.\n'
          + '  status "PENDING"     — the reader was asked and has not yet '
          + 'responded. You may call again after a short wait; it will not '
          + 're-trigger the prompt, only read the answer.\n'
          + '  status "UNAVAILABLE" — no geolocation support in this client. '
          + 'Do not retry.\n'
          + 'Accuracy is a radius in metres, not a point — treat the reading '
          + 'as an area, and do not report false precision.',
        primary: true,
        source: 'chat',
        paramsSchema: {
          type: 'object',
          properties: {},
          required: [],
        },
        labels: ['read-only', 'chat'],
        allowedProfiles: ['web'],
        deferred: false,
        searchHint: 'location geolocation position lat lng where am i',
        safety: 'SAFE_PROBE',
        requiresEngineRoles: [],
      },
    ];
  }

  // ─── Handlers ────────────────────────────────────────────────────

  /**
   * Answer with the location, or a status. Never throws for a declined or
   * unavailable permission — those are results the agent acts on, not errors
   * that would make it retry.
   */
  private async locationGet(_params: Record<string, unknown>): Promise<Record<string, unknown>> {
    // No geolocation API at all (headless, or a browser without it) — a
    // terminal status, answered immediately.
    if (typeof navigator === 'undefined' || !('geolocation' in navigator)) {
      return this.toResult(this.unavailable('This client has no geolocation support.'));
    }

    // Read the permission state from the browser — it is the cache, held
    // per origin across reloads, so no state of ours must outlive a call.
    const permission = await this.readPermissionState();

    if (permission === 'denied') {
      return this.toResult({
        status: 'DECLINED',
        message: 'The reader declined to share their location. Do not retry.',
      });
    }
    if (permission === 'unavailable') {
      return this.toResult(this.unavailable('Location is not available in this client.'));
    }

    // `granted` and `prompt` both go to a position request. On `prompt` the
    // browser shows the permission dialog; on `granted` it answers straight
    // away. Either way the result arrives asynchronously, and we do not block
    // on it — we race it against a short local wait and answer PENDING if the
    // reader has not responded in time.
    //
    // A single in-flight request per service: a second call while the first
    // is pending reuses it rather than firing a second prompt at the reader.
    if (!this.pendingRequest) {
      this.pendingRequest = this.requestPosition(permission === 'prompt')
        .finally(() => { this.pendingRequest = null; });
    }
    const result = await this.raceWait(this.pendingRequest);
    return this.toResult(result);
  }

  /**
   * Ask the browser for the position, resolving with the raw status. The
   * caller ({@link locationGet}) races this against a timeout; this only
   * settles when the browser answers, however long that takes.
   */
  private requestPosition(triggersPrompt: boolean): Promise<LocationResult> {
    return new Promise<LocationResult>((resolve) => {
      let settled = false;
      const settle = (result: LocationResult): void => {
        if (settled) return;
        settled = true;
        resolve(result);
      };
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          settle({
            status: 'GRANTED',
            message: 'Location available.',
            latitude: pos.coords.latitude,
            longitude: pos.coords.longitude,
            accuracy: pos.coords.accuracy,
            timestamp: pos.timestamp,
          });
        },
        (err) => {
          // err.code 1 is PERMISSION_DENIED; anything else (13/2/…) is the
          // reader being unreachable, a dead signal, or a timeout — all map to
          // the terminal UNAVAILABLE, not a retryable state.
          const declined = err.code === 1;
          settle(declined
            ? { status: 'DECLINED', message: 'The reader declined to share their location.' }
            : this.unavailable(
                triggersPrompt
                  ? 'Location could not be obtained (no signal or unsupported).'
                  : 'Location could not be obtained (no signal or unsupported).',
              ));
        },
        { enableHighAccuracy: false, timeout: PERMISSION_WAIT_MS, maximumAge: 0 },
      );
    });
  }

  /**
   * Race a position request against the local wait. Whichever settles first
   * decides the answer; the browser callback may still arrive later, but the
   * agent already has its PENDING (or GRANTED/DECLINED) and moves on.
   */
  private async raceWait(
    request: Promise<LocationResult>,
  ): Promise<LocationResult> {
    let timer: ReturnType<typeof setTimeout> | null = null;
    const timeout = new Promise<LocationResult>((resolve) => {
      timer = setTimeout(
        () => resolve({
          status: 'PENDING',
          message: 'The reader was asked to share their location but has not responded yet. '
            + 'You may call location_get again after a short wait — it will read the answer, '
            + 'not re-ask the browser. Otherwise continue without it.',
        }),
        PERMISSION_WAIT_MS,
      );
    });
    try {
      return await Promise.race([request, timeout]);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  private unavailable(message: string): LocationResult {
    return { status: 'UNAVAILABLE', message };
  }

  /**
   * Read the permission state from the browser, mapping its tri-state to
   * `granted` / `denied` / `unavailable`. `prompt` collapses to a truthy
   * "ask now" signal; the position request below is what surfaces the prompt.
   *
   * <p>Falls back to the position request when {@code permissions} is absent
   * (older browsers): we cannot know the state up front, so we ask directly
   * and let the result tell us.
   */
  private async readPermissionState(): Promise<'granted' | 'denied' | 'prompt' | 'unavailable'> {
    if (!('permissions' in navigator) || !navigator.permissions) {
      // No permissions API — the request itself will surface the prompt and
      // its result will say whether the reader was reachable. Treat as prompt.
      return 'prompt';
    }
    try {
      const status = await navigator.permissions.query({ name: 'geolocation' as PermissionName });
      return status.state === 'granted'
        ? 'granted'
        : status.state === 'denied'
          ? 'denied'
          : 'prompt';
    } catch {
      // The query itself rejected (some browsers reject unknown names) — ask
      // directly rather than guess, and let the position result speak.
      return 'prompt';
    }
  }

  private toResult(result: LocationResult): Record<string, unknown> {
    return { ...result } as Record<string, unknown>;
  }
}
