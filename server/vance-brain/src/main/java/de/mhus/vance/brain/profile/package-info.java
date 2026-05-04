/**
 * Self-service profile endpoint for the Web-UI's {@code profile.html}.
 * Sits parallel to {@code admin.users.UserAdminController} but
 * targets only the caller's own user — no path-supplied username,
 * no admin permission check. See {@link ProfileController} for
 * details.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.profile;
