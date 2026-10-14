package de.mhus.vance.shared.lease;

/**
 * Per-lease-type cleanup reaction. A freed lease does not automatically
 * inform the consumer — a reap that only returns the slot would leave the
 * resource running (a Chromium session in a remote container, a compile
 * poll loop), and the next holder would start another instance while the
 * counter counts claims, not instances. Hence: every lease type has a
 * handler that reacts to the revocation.
 *
 * <p>Implementations are Spring beans; add-ons register their own (tex
 * add-on: {@code tex}; brain: {@code mcp}). The registry lives in
 * vance-brain; this interface sits in shared because add-ons implement it
 * and must not depend on brain internals.
 *
 * <p>{@link #onRevoked} runs when the lease was freed <b>against the
 * holder's will</b> (idle-reap, pod-reap). It must be idempotent and must
 * tolerate missing local state — with a dead holder pod it runs on a
 * foreign pod. A regular {@link Lease#release()} does <b>not</b> invoke
 * the handler: the holder cleans up its own resource.
 */
public interface LeaseHandler {

    /** Lease type this handler cleans up ({@code "tex"}, {@code "mcp"}, …). */
    String type();

    /**
     * The lease was revoked. Idempotent; may run on a pod that never saw
     * the holder ({@code podBound = false} + dead holder pod).
     */
    void onRevoked(LeaseHolderInfo info);
}
