import { type Ref } from 'vue';
import type { BrainWsApi } from '@vance/shared';
import type { CortexSelection } from './stores/cortexStore';
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
    openFileByPath(path: string): Promise<{
        documentId: string;
        path: string;
        alreadyOpen: boolean;
    } | null>;
    /**
     * Returns the currently active editor tab — what the user has in the
     * foreground. May differ from the chat-bound document (the user can
     * switch tabs without rebinding the chat). The
     * {@code cortex_get_active_tab} tool exposes this so the agent can
     * disambiguate "this file" between the bound doc and what's on
     * screen.
     */
    getActiveTab(): {
        documentId: string;
        path: string;
    } | null;
}
export declare class CortexClientToolService {
    private readonly deps;
    /**
     * Reactive: {@code true} while at least one tool invocation is in
     * flight. The Cortex UI watches this to render a soft-lock label on
     * the bound document ("Agent bearbeitet…"). Counted, not boolean —
     * a single late result wouldn't otherwise drop the indicator while
     * other invocations are still running.
     */
    readonly isExecuting: Ref<boolean>;
    private invokeUnsub;
    private inflight;
    private readonly handlers;
    constructor(deps: CortexToolDeps);
    /**
     * Push the tool registration and start listening for invocations.
     * Idempotent against a fresh socket — call from each WS open.
     */
    attach(ws: BrainWsApi): Promise<void>;
    /**
     * Drop the invoke listener. Brain-side registry entries clear out
     * when the WS closes — no explicit "unregister" message needed.
     */
    detach(): void;
    private onInvoke;
    private beginExecuting;
    private endExecuting;
    private toolSpecs;
    private registerCortexHandlers;
}
//# sourceMappingURL=clientToolService.d.ts.map