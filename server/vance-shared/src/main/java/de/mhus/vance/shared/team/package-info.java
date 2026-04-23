/**
 * Team domain — a group of users inside a tenant.
 *
 * <p>A team references its {@code UserDocument}s by {@code name} (usernames).
 * Projects link to teams via {@link de.mhus.vance.shared.project.ProjectDocument#getTeamIds()};
 * the team itself stays unaware of which projects reference it.
 *
 * <p>Colocated: document + package-private repository + service.
 */
@NullMarked
package de.mhus.vance.shared.team;

import org.jspecify.annotations.NullMarked;
