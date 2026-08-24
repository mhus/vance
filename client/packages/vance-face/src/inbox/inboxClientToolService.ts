import { ref, type Ref } from 'vue';
import type { BrainWsApi } from '@vance/shared';

// Wire-format mirrors of the brain DTOs, inlined for the same reason
// cortex/clientToolService.ts inlines them: @vance/generated's index.ts is
// hand-maintained and would have to be rebuilt before this page could import
// the shapes. Matches the @GenerateTypeScript-annotated Java classes
// vance-api/de.mhus.vance.api.tools.ToolSpec / .ClientToolInvokeRequest /
// .ClientToolInvokeResponse / .ToolSafety.

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
 * The inbox page's client-tool surface: one tool, which moves the reader's
 * screen to a thread.
 *
 * <p><b>Only navigation lives here.</b> Reading is server-side
 * ({@code thread_get}), and the reader's current selection is server-side too —
 * it rides with each turn as {@code activeInbox} and the prompt names it. That
 * is the same split Cortex settled on: {@code doc_get_selection} used to be a
 * client tool and is now fed by the per-turn {@code boundDocSelection} pointer.
 * A client tool is for what only the browser can do — and pointing the person
 * at something is exactly that.
 */
export interface InboxToolDeps {
  /**
   * Open the given thread in the panel and, when a contribution is named,
   * scroll to it and select it. Returns {@code false} when the thread is not in
   * the list the reader is currently looking at — the agent then knows the
   * screen did not move, instead of assuming it did.
   */
  showThread(threadId: string, messageId?: string | null): Promise<boolean>;
}

type ToolHandler = (
  params: Record<string, unknown>,
) => Promise<Record<string, unknown>> | Record<string, unknown>;

export class InboxClientToolService {
  /** Reactive: true while an invocation is in flight. */
  readonly isExecuting: Ref<boolean> = ref(false);

  private invokeUnsub: (() => void) | null = null;
  private inflight = 0;
  private readonly handlers = new Map<string, ToolHandler>();

  constructor(private readonly deps: InboxToolDeps) {
    this.handlers.set('inbox_show_thread', async (params) => {
      const threadId = requireString(params, 'threadId').trim();
      if (!threadId) throw new Error('threadId must not be empty');
      const raw = params.messageId;
      const messageId = typeof raw === 'string' && raw.trim() ? raw.trim() : null;
      const shown = await this.deps.showThread(threadId, messageId);
      if (!shown) {
        throw new Error(
          `Thread "${threadId}" is not in the list the reader currently has open `
          + '(they may be on a different filter or the archive). Nothing moved on '
          + 'their screen.',
        );
      }
      return { threadId, messageId, shown: true };
    });
  }

  /** Push the registration and start listening. Call on each WS open. */
  async attach(ws: BrainWsApi): Promise<void> {
    await ws.send('client-tool-register', { tools: this.toolSpecs() });
    this.invokeUnsub = ws.on<ClientToolInvokeRequest>(
      'client-tool-invoke',
      (req) => { void this.onInvoke(ws, req); },
    );
  }

  /** Drop the listener; the brain-side registry clears when the WS closes. */
  detach(): void {
    this.invokeUnsub?.();
    this.invokeUnsub = null;
  }

  private async onInvoke(ws: BrainWsApi, req: ClientToolInvokeRequest): Promise<void> {
    const correlationId = req.correlationId;
    const handler = this.handlers.get(req.name);
    let response: ClientToolInvokeResponse;
    if (!handler) {
      response = { correlationId, result: {}, error: `Unknown client tool: ${req.name}` };
    } else {
      this.beginExecuting();
      try {
        response = { correlationId, result: await handler(req.params ?? {}) };
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

  private toolSpecs(): ToolSpec[] {
    return [
      {
        name: 'inbox_show_thread',
        description:
          'Put an inbox thread on the reader\'s screen: open it in their inbox '
          + 'panel and, when messageId is given, scroll to that contribution and '
          + 'highlight it. Use it when you want them to look at something you are '
          + 'talking about ("look at this one", "the second request"). '
          + 'IT RETURNS NO CONTENT — to read a thread yourself use thread_get. '
          + 'It also changes nothing about the thread: not read, not answered, '
          + 'not archived.',
        primary: true,
        source: 'inbox',
        paramsSchema: {
          type: 'object',
          properties: {
            threadId: {
              type: 'string',
              description: 'Thread to show, from inbox_list.',
            },
            messageId: {
              type: 'string',
              description:
                'Optional contribution inside the thread to scroll to and '
                + 'highlight, from thread_get.',
            },
          },
          required: ['threadId'],
        },
        labels: ['ui', 'inbox'],
        allowedProfiles: ['web'],
        deferred: false,
        searchHint: '',
        safety: 'SAFE_PROBE',
        requiresEngineRoles: [],
      },
    ];
  }
}

function requireString(params: Record<string, unknown>, name: string): string {
  const v = params[name];
  if (typeof v !== 'string') {
    throw new Error(`Tool parameter '${name}' must be a string.`);
  }
  return v;
}
