package de.mhus.vance.brain.webdav;

import java.util.List;

/**
 * The authenticated WebDAV principal, produced by
 * {@link VanceWebDavSecurityManager#authenticate(String, String)} and stashed
 * on the milton {@code Auth} tag. Carries exactly what building a
 * {@link de.mhus.vance.shared.permission.SecurityContext} needs — tenant is
 * bound from the request URL at authentication time, so a user only ever
 * authenticates for the tenant in the path.
 */
public record DavPrincipal(String tenantId, String username, List<String> teams) {

    public DavPrincipal {
        teams = List.copyOf(teams);
    }
}
