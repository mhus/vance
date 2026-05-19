/**
 * Wire-contract DTOs for the OAuth integration subsystem. Per-tenant
 * provider configurations live as YAML documents (server-side); user
 * tokens live as encrypted user-settings. Both surfaces are managed
 * through the {@code /brain/{tenant}/oauth/…} REST endpoints; this
 * package carries only the JSON shapes the Web-UI consumes.
 *
 * <p>See {@code planning/tool-oauth.md} for the full architecture.
 */
@NullMarked
package de.mhus.vance.api.oauth;

import org.jspecify.annotations.NullMarked;
