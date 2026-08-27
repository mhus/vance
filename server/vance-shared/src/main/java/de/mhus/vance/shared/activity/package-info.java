/**
 * Eddie's per-user activity feed — the document only.
 *
 * <p>{@code EddieActivityService} and everything that writes these rows stay in
 * {@code vance-brain}. The document is here for the same reason as
 * {@code ImageCallRecord}: the rows are keyed on a <em>user</em>, and the admin
 * shell — which has {@code vance-shared} and no brain — has to be able to remove
 * them when the account goes. A row it cannot see is a row the next account
 * created under that login inherits, and this one carries what somebody did.
 */
@NullMarked
package de.mhus.vance.shared.activity;

import org.jspecify.annotations.NullMarked;
