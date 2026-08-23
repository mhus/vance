package de.mhus.vance.store.brain;

import de.mhus.vance.shared.kit.KitException;

/**
 * The store answered, and its answer was no.
 *
 * <p>Split off {@link KitException} because the two failures need different
 * status codes and, more importantly, different alerting: a mistyped store
 * password used to leave a {@code 502 Bad Gateway} in the access log,
 * indistinguishable from the delivery service being down. A refusal is a
 * conflict with the state of an account at the store — the caller can act on
 * it. Everything else (unreachable, timeout, malformed answer, HTTP 5xx)
 * stays a plain {@code KitException} and a gateway error.
 *
 * <p>Not carrying the store's own status on purpose: a store's 401 must not
 * surface as our 401, or the browser reads it as its own session having
 * expired.
 */
public class StoreRefusedException extends KitException {

    public StoreRefusedException(String message) {
        super(message);
    }
}
