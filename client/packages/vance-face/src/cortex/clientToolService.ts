import { ref, type Ref } from 'vue';
import type { BrainWsApi } from '@vance/shared';
import type { CortexSelection } from './stores/cortexStore';

// Wire-format mirrors of the brain DTOs. Inlined deliberately: the
// upstream @vance/generated package's hand-maintained index.ts has to
// be rebuilt (tsc -b) when new re-exports are added, and we don't want
// the Cortex page to depend on that build step having run. Shapes
// match the @GenerateTypeScript-annotated Java classes:
// vance-api/de.mhus.vance.api.tools.ToolSpec / .ClientToolInvokeRequest
// / .ClientToolInvokeResponse / .ToolSafety.

type ToolSafety = 'SAFE_PROBE' | 'MUTATING';

/**
 * What a mounted custom app offers the chat beside it.
 *
 * <p>Mirrored structurally rather than imported: the app is a **federated**
 * addon bundle and this page cannot import from it — the seam is
 * `provide('vance:register-app-agent', …)`, and the shape is the contract. It
 * matches `AppAgentApi` in `vance-addon-brain-bistromath/client/src/agentApi.ts`.
 */
export interface AppAgentApi {
  describe(): {
    app: string;
    view: string | null;
    /** The rendered view as an indented tree — the part a model reads. */
    snapshot: string;
    stateKeys: string[];
    actions: { id: string; label: string | null; type: string; agent: boolean }[];
    views: string[];
  };
  stateGet(key?: string | null): unknown;
  stateSet(key: string, value: unknown): void;
  action(id: string, args?: unknown[]): Promise<void>;
  reload(): Promise<void>;
}

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
 * Vance Brain's client-tool protocol (mirrors {@code vance-foot}'s
 * {@code ClientToolService}): the client pushes its tool surface on
 * connect, then handles {@code client-tool-invoke} frames pushed from
 * the brain and replies with {@code client-tool-result} carrying the
 * same correlation id. The brain blocks the LLM sampling loop on the
 * pending future until we answer (or the 30s timeout fires).
 *
 * <p>Cortex registers a small set of <b>UI-state</b> tools — reading
 * the user's selection, the active tab, and opening files. The body /
 * note edit tools that used to live here ({@code cortex_read /
 * cortex_edit / cortex_append / cortex_write}) were removed: the agent
 * uses the server-side {@code doc_*} family instead, and the Cortex
 * tab refreshes via {@code DOCUMENT_INVALIDATE} frames (see
 * {@code planning/cortex-document-invalidation.md}).
 *
 * <p>Tool surface today:
 * <ul>
 *   <li>{@code doc_get_selection} — user's current text selection.</li>
 *   <li>{@code cortex_get_active_tab} — which tab is in the
 *       foreground.</li>
 *   <li>{@code cortex_open_file} — bring a document to the user's tab.</li>
 * </ul>
 */
export interface CortexToolDeps {
  /**
   * Returns the user's current editor selection or {@code null} when
   * nothing is highlighted. The renderer mirrors the CodeEditor's
   * selection events into the store; the {@code doc_get_selection}
   * tool surfaces it on demand. Caret-only positions (zero-length
   * range) are stored as {@code null} — they're not a "selection" in
   * the user-intent sense.
   */
  getSelection(): CortexSelection | null;

  /**
   * The custom app mounted in the foreground tab, or {@code null}.
   *
   * <p>A getter rather than a value: the app mounts and unmounts as tabs come
   * and go, and this service outlives several of those.
   */
  getAppAgent?(): AppAgentApi | null;

  /**
   * Open the document with the given path (relative inside the chat's
   * project) as a Cortex tab and activate it. Idempotent — when the
   * document is already open the existing tab is brought to the
   * foreground rather than duplicated. The brain's
   * {@code cortex_open_file} tool routes here. Returns the resolved
   * doc info or {@code null} when the path is unknown to the project.
   */
  openFileByPath(
    path: string,
  ): Promise<{ documentId: string; path: string; alreadyOpen: boolean } | null>;

  /**
   * Returns the currently active editor tab — what the user has in the
   * foreground. May differ from the chat-bound document (the user can
   * switch tabs without rebinding the chat). The
   * {@code cortex_get_active_tab} tool exposes this so the agent can
   * disambiguate "this file" between the bound doc and what's on
   * screen.
   */
  getActiveTab(): { documentId: string; path: string } | null;
}

type ToolHandler = (
  params: Record<string, unknown>,
) => Promise<Record<string, unknown>> | Record<string, unknown>;

export class CortexClientToolService {
  /**
   * Reactive: {@code true} while at least one tool invocation is in
   * flight. The Cortex UI watches this to render a soft-lock label on
   * the bound document ("Agent bearbeitet…"). Counted, not boolean —
   * a single late result wouldn't otherwise drop the indicator while
   * other invocations are still running.
   */
  readonly isExecuting: Ref<boolean> = ref(false);

  private invokeUnsub: (() => void) | null = null;
  private inflight = 0;
  private readonly handlers = new Map<string, ToolHandler>();

  /**
   * The app, or a refusal that says what to do instead.
   *
   * <p>Asked for through a **getter in the deps**, not held here: this service
   * is a `computed` that is rebuilt whenever the session changes, and a
   * registration stored on the instance would be silently lost the first time
   * somebody switched sessions with an app open.
   *
   * <p>One app, not a map keyed by tab: the tools act on the app the reader is
   * looking at. Two apps open means the background one is not addressable,
   * which is right — the agent was asked about "the app" and there is one
   * answer to that.
   */
  private requireApp(): AppAgentApi {
    const app = this.deps.getAppAgent?.() ?? null;
    if (!app) {
      throw new Error(
        'No custom app is open in the foreground. Open one (a folder with '
          + '`app: custom`) and try again — these tools act on what the reader is '
          + 'looking at.',
      );
    }
    return app;
  }

  constructor(private readonly deps: CortexToolDeps) {
    this.registerCortexHandlers();
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
        name: 'cortex_get_active_tab',
        description:
          'Return the document currently shown in the foreground of the '
          + 'Cortex editor — what the user is actively looking at. May '
          + 'differ from the chat-bound document (the user can browse '
          + 'tabs without rebinding the chat). Use to disambiguate '
          + '"this file" / "the open one" in user requests. Returns '
          + '{ hasActiveTab, documentId?, path? }.',
        primary: true,
        source: 'cortex',
        paramsSchema: {
          type: 'object',
          properties: {},
          required: [],
        },
        labels: ['read-only', 'cortex'],
        allowedProfiles: ['web'],
        deferred: false,
        searchHint: '',
        safety: 'SAFE_PROBE',
        requiresEngineRoles: [],
      },
      {
        name: 'cortex_open_file',
        description:
          'Open a document as a tab in the Cortex editor and bring it to '
          + 'the foreground. Idempotent — calling it on an already-open '
          + 'document just focuses that tab. Use to show the user a '
          + 'document you reference (e.g. before quoting from it, or when '
          + 'the user asks to "open / show / look at X"). Returns the '
          + 'document\'s id and whether it was already open. '
          + 'Fails when the path is not present in the project.',
        primary: true,
        source: 'cortex',
        paramsSchema: {
          type: 'object',
          properties: {
            path: {
              type: 'string',
              description:
                'Path of the document inside the project (e.g. '
                + '"documents/notes/idea.md"). Must match an existing file.',
            },
          },
          required: ['path'],
        },
        labels: ['ui', 'cortex'],
        allowedProfiles: ['web'],
        deferred: false,
        searchHint: '',
        safety: 'SAFE_PROBE',
        requiresEngineRoles: [],
      },
      // ── the custom app beside the chat ──────────────────────────
      //
      // Four names, always registered. NOT one tool per app: each app has its
      // own state keys and buttons, so per-app registration would change the
      // inventory on every tab switch — against the endpoint tool cap and, worse,
      // against the cache-anchored prefix the whole prompt sits on. The
      // *arguments* name the key or the action, and `app_describe` says what
      // there is.
      {
        name: 'app_describe',
        description:
          'Snapshot the custom app (`app: custom`) open in the foreground of '
          + 'the Cortex — an indented tree of its widgets, like a browser '
          + 'accessibility snapshot. One line per widget: its type, its `#id` '
          + '(the handle you pass to other app tools), its label, the state key '
          + 'it reads and that key\'s current value, and for anything with an '
          + 'action whether it is `[agent]` (you may press it) or `[closed]`. '
          + 'There is no selector language — read the tree and name an id. '
          + 'Call this first: ids, keys and structure differ per app. Returns '
          + '{ app, view, snapshot, stateKeys, actions, views }. Fails when no '
          + 'such app is open.',
        primary: true,
        source: 'cortex',
        paramsSchema: { type: 'object', properties: {}, required: [] },
        labels: ['read-only', 'cortex', 'app'],
        allowedProfiles: ['web'],
        deferred: false,
        searchHint: '',
        safety: 'SAFE_PROBE',
        requiresEngineRoles: [],
      },
      {
        name: 'app_state_get',
        description:
          'Read the state of the custom app open in the foreground — one key, '
          + 'or every key when none is named. This is what its widgets show, '
          + 'including the values in its forms. Returns { state } for all keys '
          + 'or { key, value } for one.',
        primary: true,
        source: 'cortex',
        paramsSchema: {
          type: 'object',
          properties: {
            key: {
              type: 'string',
              description:
                'A state key from `app_describe`. Omit to read all of them.',
            },
          },
          required: [],
        },
        labels: ['read-only', 'cortex', 'app'],
        allowedProfiles: ['web'],
        deferred: false,
        searchHint: '',
        safety: 'SAFE_PROBE',
        requiresEngineRoles: [],
      },
      {
        name: 'app_state_set',
        description:
          'Set one state key of the custom app open in the foreground — how '
          + 'you fill in its form, since form values live in state. The change '
          + 'is visible to the reader immediately and commits nothing: the '
          + 'app\'s own handlers do NOT run, so a person still presses the '
          + 'button. Use `app_action` for that, if the app allows it.',
        primary: true,
        source: 'cortex',
        paramsSchema: {
          type: 'object',
          properties: {
            key: { type: 'string', description: 'A state key from `app_describe`.' },
            value: {
              description:
                'The new value. Any JSON — a string for a text field, a number, '
                + 'a boolean for a toggle, an object for a form, an array for a table.',
            },
          },
          required: ['key', 'value'],
        },
        labels: ['ui', 'cortex', 'app'],
        allowedProfiles: ['web'],
        deferred: false,
        searchHint: '',
        safety: 'MUTATING',
        requiresEngineRoles: [],
      },
      {
        name: 'app_action',
        description:
          'Press a button of the custom app open in the foreground, by widget '
          + 'id. Only works for widgets the app opened to agents (`agent: '
          + 'true` in its view document) — `app_describe` says which, and a '
          + 'refusal here means the app did not offer it, not that you asked '
          + 'wrongly. This runs the app\'s own code, so it can write '
          + 'documents: read the state first and say what you are about to do.',
        primary: true,
        source: 'cortex',
        paramsSchema: {
          type: 'object',
          properties: {
            id: {
              type: 'string',
              description: 'Widget id, from the `actions` list of `app_describe`.',
            },
          },
          required: ['id'],
        },
        labels: ['ui', 'cortex', 'app'],
        allowedProfiles: ['web'],
        deferred: false,
        searchHint: '',
        safety: 'MUTATING',
        requiresEngineRoles: [],
      },
      {
        name: 'app_reload',
        description:
          'Re-read the custom app open in the foreground from its documents '
          + 'and restart its program. This is the way back: it discards every '
          + 'state value and widget change you or the app made, and shows '
          + 'exactly what the documents say. Use it after editing the app\'s '
          + 'view or program so the reader sees the new version, or when the '
          + 'app is in a state you cannot explain. Commits nothing.',
        primary: true,
        source: 'cortex',
        paramsSchema: { type: 'object', properties: {}, required: [] },
        labels: ['ui', 'cortex', 'app'],
        allowedProfiles: ['web'],
        deferred: false,
        searchHint: '',
        safety: 'MUTATING',
        requiresEngineRoles: [],
      },
    ];
  }

  private registerCortexHandlers(): void {
    // Note: the selection *read* is now a server-side tool
    // (`doc_get_selection`), fed by the per-turn `boundDocSelection`
    // range that rides with the steer. The client no longer serves it.
    this.handlers.set('app_describe', () => {
      return this.requireApp().describe() as unknown as Record<string, unknown>;
    });

    this.handlers.set('app_state_get', (params) => {
      const key = typeof params.key === 'string' && params.key ? params.key : null;
      const app = this.requireApp();
      if (!key) return { state: app.stateGet() };
      return { key, value: app.stateGet(key) };
    });

    this.handlers.set('app_state_set', (params) => {
      const key = typeof params.key === 'string' ? params.key : '';
      if (!key) throw new Error('`key` is required — see `app_describe`.');
      const app = this.requireApp();
      // Named rather than accepted: writing a key no widget reads changes
      // nothing visible, and an agent would report success on a spelling
      // mistake. The keys are knowable, so the check is fair.
      const known = app.describe().stateKeys;
      if (known.length > 0 && !known.includes(key)) {
        throw new Error(
          `No widget reads '${key}'. This app's keys are: ${known.join(', ')}.`,
        );
      }
      app.stateSet(key, params.value);
      return { key, value: params.value };
    });

    this.handlers.set('app_action', async (params) => {
      const id = typeof params.id === 'string' ? params.id : '';
      if (!id) throw new Error('`id` is required — see `app_describe`.');
      const app = this.requireApp();
      await app.action(id);
      return { id, triggered: true, state: app.stateGet() };
    });

    this.handlers.set('app_reload', async () => {
      const app = this.requireApp();
      await app.reload();
      // The description afterwards, not before: an agent reloads because it
      // changed the documents, and what it needs next is what the app looks
      // like now.
      return { reloaded: true, app: app.describe() as unknown as Record<string, unknown> };
    });

    this.handlers.set('cortex_get_active_tab', () => {
      const tab = this.deps.getActiveTab();
      if (!tab) {
        return { hasActiveTab: false };
      }
      return {
        hasActiveTab: true,
        documentId: tab.documentId,
        path: tab.path,
      };
    });

    this.handlers.set('cortex_open_file', async (params) => {
      const path = requireString(params, 'path').trim();
      if (!path) {
        throw new Error('path must not be empty');
      }
      const result = await this.deps.openFileByPath(path);
      if (!result) {
        throw new Error(
          `No document at path "${path}" in this project. `
          + 'Use list-style tools to discover existing paths.',
        );
      }
      return {
        documentId: result.documentId,
        path: result.path,
        alreadyOpen: result.alreadyOpen,
      };
    });

  }
}

function requireString(params: Record<string, unknown>, name: string): string {
  const v = params[name];
  if (typeof v !== 'string') {
    throw new Error(`Tool parameter '${name}' must be a string.`);
  }
  return v;
}
