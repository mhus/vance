package de.mhus.vance.shared.permission;

import java.util.List;

/**
 * The one piece of team data the permission layer needs: which teams a user
 * belongs to. Implemented by {@code TeamService}; kept as a narrow interface so
 * {@link PermissionService} depends on exactly this question and tests can
 * answer it with a lambda.
 */
@FunctionalInterface
public interface TeamMembership {

    /** Names of the teams {@code username} is a member of inside {@code tenantId}. */
    List<String> teamNamesOf(String tenantId, String username);
}
