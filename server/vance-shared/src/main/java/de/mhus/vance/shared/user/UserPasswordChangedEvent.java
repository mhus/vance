package de.mhus.vance.shared.user;

/**
 * Published by {@link UserService#setPasswordHash} after a new password hash
 * was stored — by the owner or by an admin. Credentials that were handed out
 * under the old password and outlive a login (OAuth grants) listen for it and
 * end themselves: a password change is the user's way of saying "whoever had
 * access until now should not have it any more".
 *
 * <p>Delivered synchronously on the caller's thread; a listener that fails
 * propagates to the caller after the hash is already stored.
 *
 * @param tenantId tenant of the account
 * @param name     login name of the account
 */
public record UserPasswordChangedEvent(String tenantId, String name) {}
