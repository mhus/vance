/**
 * Ephemeral live state of projects and runners, kept in Redis with a TTL. It is
 * information for the user, not a source of truth: an entry that is missing or
 * expired reads as {@code UNKNOWN}. See {@code planning/project-runner-status.md}.
 */
@NullMarked
package de.mhus.vance.shared.livestate;

import org.jspecify.annotations.NullMarked;
