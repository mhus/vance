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
 *   <li>{@code cortex_get_selection} — user's current text selection.</li>
 *   <li>{@code cortex_get_active_tab} — which tab is in the
 *       foreground.</li>
 *   <li>{@code cortex_open_file} — bring a document to the user's tab.</li>
 * </ul>
 */
export interface CortexToolDeps {
  /**
   * Returns the user's current editor selection or {@code null} when
   * nothing is highlighted. The renderer mirrors the CodeEditor's
   * selection events into the store; the {@code cortex_get_selection}
   * tool surfaces it on demand. Caret-only positions (zero-length
   * range) are stored as {@code null} — they're not a "selection" in
   * the user-intent sense.
   */
  getSelection(): CortexSelection | null;

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
        name: 'cortex_get_selection',
        description:
          'Return the user\'s current text selection in the active '
          + 'Cortex editor, or indicate that nothing is selected. Use '
          + 'this when the user refers to "this part", "the highlighted '
          + 'text", "what I selected", or similar — the selection is the '
          + 'piece of the document they want you to focus on. Returns '
          + 'the selected text plus its source document path (which may '
          + 'differ from the chat-bound document if the user is viewing '
          + 'another tab).',
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
    ];
  }

  private registerCortexHandlers(): void {
    this.handlers.set('cortex_get_selection', () => {
      const sel = this.deps.getSelection();
      if (!sel) {
        return { hasSelection: false };
      }
      return {
        hasSelection: true,
        path: sel.docPath,
        text: sel.text,
        from: sel.from,
        to: sel.to,
        length: sel.text.length,
      };
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
