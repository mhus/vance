/**
 * DTOs for the self-service profile endpoint
 * ({@code /brain/{tenant}/profile}). The profile API is the
 * non-admin path: every authenticated user can read and edit
 * their own identity (title, email) and {@code webui.*} settings,
 * without needing the {@code ADMIN} permission required by the
 * cross-user admin controllers.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.api.profile;
