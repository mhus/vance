package de.mhus.vance.brain.milliways;

import de.mhus.vance.shared.permission.SecurityContext;

/**
 * What the caller names: who wants to share which document. The inbound
 * layer builds this; {@link MilliwaysService} turns it into a
 * {@link ShareScope} by resolving the document and enforcing {@code READ}
 * on it.
 *
 * <p>{@code tenantId} stays explicit rather than being read off
 * {@link SecurityContext#tenantId()} — the inbound layer knows it from the
 * request path, and letting the two disagree silently is exactly the bug a
 * permission check is supposed to surface.
 */
public record ShareTarget(
        SecurityContext ctx,
        String tenantId,
        String projectId,
        String path) {
}
