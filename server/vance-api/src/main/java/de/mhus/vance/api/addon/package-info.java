/**
 * Wire-contract DTOs for the addon registry — currently just the
 * read view returned by {@code GET /face/addons}. The face-bootstrap
 * consumes this list to know which Module Federation remotes to
 * register before booting the app.
 *
 * <p>Spec: {@code specification/addon-system.md}.
 */
@NullMarked
package de.mhus.vance.api.addon;

import org.jspecify.annotations.NullMarked;
