/**
 * DTOs for the per-user Web-UI state endpoint
 * ({@code /brain/{tenant}/me/ui-state/*}). Holds purely cosmetic
 * preferences (which sidebar groups are collapsed, …) that the
 * client wants to persist across sessions and devices. Storage
 * lives on the per-user {@code _user_<login>} project under
 * {@code webui.*} keys — same physical layer as
 * {@code ProfileController} writes to, but typed instead of
 * single-string.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.api.uistate;
