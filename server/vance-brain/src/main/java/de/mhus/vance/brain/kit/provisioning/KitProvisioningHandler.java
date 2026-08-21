package de.mhus.vance.brain.kit.provisioning;

import java.util.List;

/**
 * A way of learning which kits a project should have.
 *
 * <p>This is the <b>open</b> axis of kit provisioning: implementations
 * are Spring beans, chosen by a string id, and may come from any module.
 * Which mechanisms exist is a question that multiplies — an application
 * host, a list in a git repo, a sweep over a store account's
 * entitlements, a company inventory — and none of those should cost a
 * change in {@code vance-api}.
 *
 * <p>The other axis, <em>how a kit tree is fetched</em>, stays a closed
 * enum ({@code KitSourceType}): four places in the core branch on it,
 * and „fetch me a directory of files" is not a question that multiplies.
 * See {@code planning/kit-ode-provisioning.md} §1.1.
 *
 * <p>{@link #id()} names the mechanism, not the medium — the same rule
 * as share handlers. A second way of reaching the same kind of place is
 * a second handler, not an {@code if}.
 *
 * <p>Implementations must be safe to call from multiple threads.
 */
public interface KitProvisioningHandler {

    /**
     * Stable id, matched against the {@code type:} of a provisioning
     * entry. Two handlers claiming one id break the boot rather than
     * leaving one of them silently unreachable.
     */
    String id();

    /**
     * The kits this mechanism says the project should have.
     *
     * <p><b>An empty list is a legitimate answer</b> and means „nothing
     * for this project" — it must be distinguishable from a failure,
     * because a caller backs off from the second. Throw only when the
     * question could not be asked.
     *
     * <p>Expected to be cheap: this runs on a schedule, and the whole
     * point of a revision is that finding out „nothing changed" does not
     * cost what an install costs.
     */
    List<DesiredKit> discover(KitProvisioningContext context);
}
