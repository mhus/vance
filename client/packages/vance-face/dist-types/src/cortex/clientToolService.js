import { ref } from 'vue';
export class CortexClientToolService {
    deps;
    /**
     * Reactive: {@code true} while at least one tool invocation is in
     * flight. The Cortex UI watches this to render a soft-lock label on
     * the bound document ("Agent bearbeitet…"). Counted, not boolean —
     * a single late result wouldn't otherwise drop the indicator while
     * other invocations are still running.
     */
    isExecuting = ref(false);
    invokeUnsub = null;
    inflight = 0;
    handlers = new Map();
    constructor(deps) {
        this.deps = deps;
        this.registerCortexHandlers();
    }
    /**
     * Push the tool registration and start listening for invocations.
     * Idempotent against a fresh socket — call from each WS open.
     */
    async attach(ws) {
        const specs = this.toolSpecs();
        await ws.send('client-tool-register', { tools: specs });
        this.invokeUnsub = ws.on('client-tool-invoke', (req) => { void this.onInvoke(ws, req); });
    }
    /**
     * Drop the invoke listener. Brain-side registry entries clear out
     * when the WS closes — no explicit "unregister" message needed.
     */
    detach() {
        this.invokeUnsub?.();
        this.invokeUnsub = null;
    }
    // ─── Handler routing ─────────────────────────────────────────────
    async onInvoke(ws, req) {
        const correlationId = req.correlationId;
        const handler = this.handlers.get(req.name);
        let response;
        if (!handler) {
            response = {
                correlationId,
                result: {},
                error: `Unknown client tool: ${req.name}`,
            };
        }
        else {
            this.beginExecuting();
            try {
                const result = await handler(req.params ?? {});
                response = { correlationId, result };
            }
            catch (e) {
                response = {
                    correlationId,
                    result: {},
                    error: e instanceof Error ? e.message : String(e),
                };
            }
            finally {
                this.endExecuting();
            }
        }
        ws.sendNoReply('client-tool-result', response);
    }
    beginExecuting() {
        this.inflight += 1;
        if (this.inflight === 1)
            this.isExecuting.value = true;
    }
    endExecuting() {
        this.inflight = Math.max(0, this.inflight - 1);
        if (this.inflight === 0)
            this.isExecuting.value = false;
    }
    // ─── Tool definitions ───────────────────────────────────────────
    toolSpecs() {
        return [
            {
                name: 'cortex_get_selection',
                description: 'Return the user\'s current text selection in the active '
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
                description: 'Return the document currently shown in the foreground of the '
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
                description: 'Open a document as a tab in the Cortex editor and bring it to '
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
                            description: 'Path of the document inside the project (e.g. '
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
    registerCortexHandlers() {
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
                throw new Error(`No document at path "${path}" in this project. `
                    + 'Use list-style tools to discover existing paths.');
            }
            return {
                documentId: result.documentId,
                path: result.path,
                alreadyOpen: result.alreadyOpen,
            };
        });
    }
}
function requireString(params, name) {
    const v = params[name];
    if (typeof v !== 'string') {
        throw new Error(`Tool parameter '${name}' must be a string.`);
    }
    return v;
}
//# sourceMappingURL=clientToolService.js.map