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
                name: 'cortex_read',
                description: 'Read the document currently bound to the Cortex chat. '
                    + 'Returns the document path and full content. '
                    + 'Use this before editing so subsequent cortex_edit calls can '
                    + 'reference exact strings from the current text.',
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
                name: 'cortex_edit',
                description: 'Find-and-replace edit on the Cortex-bound document. '
                    + 'old_string must match the existing text exactly once; '
                    + 'new_string replaces it. The change is staged in the browser '
                    + '(the user must Save to persist). Read first via cortex_read '
                    + 'so old_string is precise; otherwise the call fails.',
                primary: true,
                source: 'cortex',
                paramsSchema: {
                    type: 'object',
                    properties: {
                        old_string: {
                            type: 'string',
                            description: 'Exact text in the document to replace. Must match once.',
                        },
                        new_string: {
                            type: 'string',
                            description: 'Replacement text.',
                        },
                    },
                    required: ['old_string', 'new_string'],
                },
                labels: ['write', 'cortex'],
                allowedProfiles: ['web'],
                deferred: false,
                searchHint: '',
                safety: 'MUTATING',
                requiresEngineRoles: [],
            },
            {
                name: 'cortex_append',
                description: 'Append text to the end of the Cortex-bound document. '
                    + 'Use for additive notes; for inline edits use cortex_edit instead. '
                    + 'The change is staged in the browser (user must Save to persist).',
                primary: true,
                source: 'cortex',
                paramsSchema: {
                    type: 'object',
                    properties: {
                        content: {
                            type: 'string',
                            description: 'Text appended at the end. A leading newline is added '
                                + 'automatically if the document does not end with one.',
                        },
                    },
                    required: ['content'],
                },
                labels: ['write', 'cortex'],
                allowedProfiles: ['web'],
                deferred: false,
                searchHint: '',
                safety: 'MUTATING',
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
            {
                name: 'cortex_write',
                description: 'Overwrite the Cortex-bound document with new content. '
                    + 'Destructive — prefer cortex_edit for small changes. '
                    + 'Useful when the document is empty, or when restructuring '
                    + 'requires more changes than a series of find/replace edits.',
                primary: true,
                source: 'cortex',
                paramsSchema: {
                    type: 'object',
                    properties: {
                        content: {
                            type: 'string',
                            description: 'New full content for the document.',
                        },
                    },
                    required: ['content'],
                },
                labels: ['write', 'cortex'],
                allowedProfiles: ['web'],
                deferred: false,
                searchHint: '',
                safety: 'MUTATING',
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
        this.handlers.set('cortex_read', () => {
            const doc = this.requireBound();
            if (isBinaryMime(doc.mimeType)) {
                return {
                    path: doc.path,
                    mimeType: doc.mimeType ?? null,
                    content: null,
                    note: 'Binary document — content not readable as text.',
                };
            }
            return {
                path: doc.path,
                mimeType: doc.mimeType ?? null,
                content: doc.inlineText,
                dirty: doc.dirty,
            };
        });
        this.handlers.set('cortex_edit', (params) => {
            const doc = this.requireTextBound();
            const oldString = requireString(params, 'old_string');
            const newString = requireString(params, 'new_string');
            const occurrences = countOccurrences(doc.inlineText, oldString);
            if (occurrences === 0) {
                throw new Error(`old_string not found in ${doc.path}. Call cortex_read to get the current content.`);
            }
            if (occurrences > 1) {
                throw new Error(`old_string matches ${occurrences} times in ${doc.path}. `
                    + 'Provide more context to make the match unique.');
            }
            doc.inlineText = doc.inlineText.replace(oldString, newString);
            doc.dirty = true;
            return { path: doc.path, replaced: 1 };
        });
        this.handlers.set('cortex_append', (params) => {
            const doc = this.requireTextBound();
            const content = requireString(params, 'content');
            const needsLeadingNewline = doc.inlineText.length > 0
                && !doc.inlineText.endsWith('\n');
            doc.inlineText = doc.inlineText
                + (needsLeadingNewline ? '\n' : '')
                + content;
            doc.dirty = true;
            return { path: doc.path, appendedChars: content.length };
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
        this.handlers.set('cortex_write', (params) => {
            const doc = this.requireTextBound();
            const content = requireString(params, 'content');
            doc.inlineText = content;
            doc.dirty = true;
            return { path: doc.path, length: content.length };
        });
    }
    requireBound() {
        const doc = this.deps.getBoundDocument();
        if (!doc) {
            throw new Error('No document is currently bound to the Cortex chat. '
                + 'Ask the user to open a document and bind the chat to it.');
        }
        return doc;
    }
    /**
     * Variant of {@link requireBound} that also rejects binary documents.
     * Write tools need a text body to operate on; images / binaries
     * surface a clear error rather than silently corrupting the document.
     */
    requireTextBound() {
        const doc = this.requireBound();
        if (isBinaryMime(doc.mimeType)) {
            throw new Error(`Bound document ${doc.path} is a binary type (${doc.mimeType}) — `
                + 'text edit tools do not apply. Ask the user to bind a text document.');
        }
        return doc;
    }
}
/**
 * Heuristic for "this document is not text we can edit through the
 * text tools". Anything starting with {@code image/}, {@code audio/},
 * {@code video/}, or the common archive types counts as binary.
 * Errs on the side of false positives — when in doubt the agent gets a
 * clear error and asks the user.
 */
function isBinaryMime(mime) {
    const m = (mime ?? '').toLowerCase();
    if (!m)
        return false;
    if (m.startsWith('image/'))
        return true;
    if (m.startsWith('audio/'))
        return true;
    if (m.startsWith('video/'))
        return true;
    if (m === 'application/pdf' || m === 'application/zip'
        || m === 'application/octet-stream')
        return true;
    return false;
}
function requireString(params, name) {
    const v = params[name];
    if (typeof v !== 'string') {
        throw new Error(`Tool parameter '${name}' must be a string.`);
    }
    return v;
}
function countOccurrences(haystack, needle) {
    if (needle.length === 0)
        return 0;
    let count = 0;
    let idx = haystack.indexOf(needle);
    while (idx !== -1) {
        count += 1;
        idx = haystack.indexOf(needle, idx + needle.length);
    }
    return count;
}
//# sourceMappingURL=clientToolService.js.map