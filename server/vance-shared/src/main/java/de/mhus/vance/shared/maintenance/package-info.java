/**
 * What the maintenance runs have in common.
 *
 * <p>Deleting a project and deleting a user are the same shape of work — ask
 * every entity, in order, and report what happened — over a different set of
 * handlers. What is genuinely shared is the <em>report</em>; the handler SPIs
 * stay apart, because the questions differ (a project is drained off a pod, a
 * user leaves references behind that outlive them).
 */
@NullMarked
package de.mhus.vance.shared.maintenance;

import org.jspecify.annotations.NullMarked;
