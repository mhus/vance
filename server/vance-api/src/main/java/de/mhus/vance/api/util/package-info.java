/**
 * Small utility types that travel over the wire and have no Spring / Mongo
 * dependency. {@link de.mhus.vance.api.util.PathGlob} is shared by the brain
 * and the {@code vance-liver} backup tool ({@code planning/user-backup.md}):
 * filter rules stay identical on both ends, so a deny-list that matches on
 * the server cannot drift from what the client writes.
 */
@NullMarked
package de.mhus.vance.api.util;

import org.jspecify.annotations.NullMarked;
