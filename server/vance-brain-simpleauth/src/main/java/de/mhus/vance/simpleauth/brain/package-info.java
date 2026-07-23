/**
 * Brain-side management surface for the Simple-Auth permission provider: the
 * grant admin REST endpoint and the {@code permission_grant_*} LLM tools.
 * Consumes the shared-level core ({@code de.mhus.vance.simpleauth}) for
 * grant storage and the Brain for {@code RequestAuthority} / tooling. Loaded
 * only in the Brain.
 */
@NullMarked
package de.mhus.vance.simpleauth.brain;

import org.jspecify.annotations.NullMarked;
