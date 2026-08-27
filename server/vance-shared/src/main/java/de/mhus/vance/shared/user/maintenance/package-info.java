/**
 * User maintenance — deleting an account and everything it touched, and
 * carrying a login to a new name.
 *
 * <p>Same shape as {@code project.maintenance}: one handler per entity, next to
 * that entity, collected by a service that runs them in order and reports what
 * happened. The difference is what a reference <em>means</em> — see
 * {@link de.mhus.vance.shared.user.maintenance.UserReference} and
 * {@link de.mhus.vance.shared.user.maintenance.UserTombstone}.
 *
 * <p>Spec: {@code specification/public/user-maintenance.md}.
 */
@NullMarked
package de.mhus.vance.shared.user.maintenance;

import org.jspecify.annotations.NullMarked;
