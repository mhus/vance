/**
 * Self-service per-user Web-UI state endpoint
 * ({@code /brain/{tenant}/me/ui-state/*}). Storage delegates to
 * {@link de.mhus.vance.shared.settings.SettingService} on the
 * per-user {@code _user_<login>} project; the controller layer
 * provides the typed JSON surface that the Web-UI consumes.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.uistate;
