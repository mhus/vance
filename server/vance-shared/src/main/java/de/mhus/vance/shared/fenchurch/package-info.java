/**
 * Fenchurch persistence — the image-call ledger.
 *
 * <p>Only the document lives here; the service that writes it
 * ({@code ImageCallTracker}) and everything else Fenchurch does stay in
 * {@code vance-brain} with the AI stack they need. The split follows the one
 * already made for {@code OAuthStateDocument} and the web caches, and it exists
 * for a concrete reason: these rows are project-scoped, so the admin shell —
 * which has {@code vance-shared} and no brain — has to be able to see them when
 * a project is deleted or renamed. A row it cannot see is a row that outlives
 * the project and is inherited by the next one created under that name.
 */
@NullMarked
package de.mhus.vance.shared.fenchurch;

import org.jspecify.annotations.NullMarked;
