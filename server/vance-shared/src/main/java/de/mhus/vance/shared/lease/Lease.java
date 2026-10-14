package de.mhus.vance.shared.lease;

import java.util.Map;

/**
 * A held lease. Consumer contract (see {@code planning/lease-system.md} §6):
 * the holder calls {@link #check()} at <b>every usage step</b> — it verifies
 * the lease is still held (throws {@link LeaseLostException} otherwise) and
 * doubles as the TTL touch ({@code lastSeenAt = now}). {@link #release()}
 * frees the slot and is idempotent; {@link #close()} delegates to it so
 * try-with-resources works.
 *
 * <p>A NOOP lease (no caps configured / no lease referenced) is a valid
 * instance: every method is a no-op, no Mongo traffic.
 */
public interface Lease extends AutoCloseable {

    /** Holder token — identifies this acquisition in the holder collection. */
    String holderToken();

    /** Usage step: verifies the lease is still held and touches {@code lastSeenAt}. */
    void check();

    /** Replaces the payload — values often become known only after acquire (job id, session id). */
    void updatePayload(Map<String, Object> payload);

    /** Frees the slot. Idempotent. */
    void release();

    /** Whether this lease does anything at all — {@code true} for NOOP leases. */
    boolean isNoop();

    @Override
    default void close() {
        release();
    }

    /** Shared NOOP implementation — used when no lease was requested or no cap is configured. */
    Lease NOOP = new Lease() {
        @Override
        public String holderToken() {
            return "noop";
        }

        @Override
        public void check() {
            /* no cap configured — nothing to verify or touch */
        }

        @Override
        public void updatePayload(Map<String, Object> payload) {
            /* nothing tracked */
        }

        @Override
        public void release() {
            /* nothing held */
        }

        @Override
        public boolean isNoop() {
            return true;
        }

        @Override
        public String toString() {
            return "Lease.NOOP";
        }
    };
}
