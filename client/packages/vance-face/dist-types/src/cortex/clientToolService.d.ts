import { type Ref } from 'vue';
import type { BrainWsApi } from '@vance/shared';
import type { CortexDocument } from './types';
import type { CortexSelection } from './stores/cortexStore';
/**
 * Vance Brain's client-tool protocol (mirrors {@code vance-foot}'s
 * {@code ClientToolService}): the client pushes its tool surface on
 * connect, then handles {@code client-tool-invoke} frames pushed from
 * the brain and replies with {@code client-tool-result} carrying the
 * same correlation id. The brain blocks the LLM sampling loop on the
 * pending future until we answer (or the 30s timeout fires).
 *
 * <p>Cortex registers tools that operate on the chat-bound document.
 * They're deliberately small and stable so the LLM can use them with
 * minimal trial-and-error:
 * <ul>
 *   <li>{@code cortex_read} — return the current bound doc's content.</li>
 *   <li>{@code cortex_edit} — exact-match find/replace.</li>
 *   <li>{@code cortex_append} — append text at the end.</li>
 *   <li>{@code cortex_write} — full overwrite (use sparingly).</li>
 * </ul>
 *
 * <p>The bound document is read from a getter (not a constructor
 * argument) so changes propagate without re-registration. When no
 * document is bound, every tool fails fast with a clear error — the
 * agent sees that the chat has nothing to operate on.
 */
export interface CortexToolDeps {
    /**
     * Returns the currently chat-bound document or {@code null}. Read
     * fresh on every tool invocation so the agent always sees the latest
     * binding without us having to re-push the registration.
     */
    getBoundDocument(): CortexDocument | null;
    /**
     * Returns the user's current editor selection or {@code null} when
     * nothing is highlighted. The renderer mirrors the CodeEditor's
     * selection events into the store; the {@code cortex_get_selection}
     * tool surfaces it on demand. Caret-only positions (zero-length
     * range) are stored as {@code null} — they're not a "selection" in
     * the user-intent sense.
     */
    getSelection(): CortexSelection | null;
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
    private requireBound;
    /**
     * Variant of {@link requireBound} that also rejects binary documents.
     * Write tools need a text body to operate on; images / binaries
     * surface a clear error rather than silently corrupting the document.
     */
    private requireTextBound;
}
//# sourceMappingURL=clientToolService.d.ts.map