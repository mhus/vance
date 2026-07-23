/**
 * anus-side surface of the Simple-Auth provider: spring-shell CRUD commands
 * over the shared grant store ({@code de.mhus.vance.simpleauth.PermissionGrantService}).
 * Loaded into the anus context via {@code @AutoConfiguration}; does not depend
 * on {@code vance-anus} (avoids a dependency cycle). Grant operations run in
 * the operator's cross-tenant god-mode — no per-scope enforcement.
 */
@NullMarked
package de.mhus.vance.simpleauth.anus;

import org.jspecify.annotations.NullMarked;
