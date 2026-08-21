package de.mhus.vance.brain.milliways;

import de.mhus.vance.shared.permission.SecurityContext;

/**
 * What the caller names: who wants to share, where, and what. The inbound
 * layer builds this; {@link MilliwaysService} turns it into a
 * {@link ShareScope} by sanitising the subject, resolving a referenced
 * document and enforcing {@code READ} on it.
 *
 * <p>{@code projectId} is the project the sharer is acting <em>in</em>, not a
 * property of the subject — a link-only share still happens inside a project,
 * and the pack lookup and the authorization hang off it.
 *
 * <p>{@code tenantId} stays explicit rather than being read off
 * {@link SecurityContext#tenantId()}: the inbound layer knows it from the
 * request path, and letting the two disagree silently is exactly the bug a
 * permission check is supposed to surface.
 */
public record ShareTarget(
        SecurityContext ctx,
        String tenantId,
        String projectId,
        ShareSubject subject) {
}
