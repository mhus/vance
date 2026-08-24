/**
 * Wire-contract DTOs for the application-listing REST endpoints — "which apps
 * are there" and "what places does one of them have".
 *
 * <p>Consumed by every link picker: an inter-app link needs an app to point at
 * and, optionally, a place inside it (planning/inter-links.md §7.4).
 */
@NullMarked
package de.mhus.vance.api.applications;

import org.jspecify.annotations.NullMarked;
