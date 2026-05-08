/**
 * Access control for the Anus shell. A single password (BCrypt-hashed in the
 * environment) gates every command annotated with {@link
 * de.mhus.vance.anus.access.RequiresAuth}. The {@link
 * de.mhus.vance.anus.access.AccessService} holds the in-memory authorisation
 * state with a sliding-window timeout.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.anus.access;
