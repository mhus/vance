/**
 * Notification delivery records — the document only; the dispatcher stays in
 * {@code vance-brain}.
 *
 * <p>Here so a user delete can reach them. Leaving them behind is not merely
 * untidy: they are addressed to a login, and a new account under that name
 * would find its predecessor's notifications waiting.
 */
@NullMarked
package de.mhus.vance.shared.notifications;

import org.jspecify.annotations.NullMarked;
