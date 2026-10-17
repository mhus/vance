package de.mhus.vance.addon.brain.contacts;

import de.mhus.vance.shared.permission.SecurityContext;
import org.jspecify.annotations.Nullable;

/**
 * Who asks, and from where. Built by the inbound side (tool, REST) and
 * handed to every {@link ContactService} call — the service derives the
 * three scope projects from it and authorises against {@link #subject}.
 *
 * @param userLogin  the caller's login; {@code null} for headless runs, which
 *                   then have no {@code user} scope
 * @param projectId  the project the caller works in; {@code null} when there
 *                   is none (the address-book area opened without a project)
 * @param confinedTo set for a process confined to its own project (trust
 *                   boundary): only scopes backed by exactly that project are
 *                   reachable
 */
public record ContactCaller(
        String tenantId,
        SecurityContext subject,
        @Nullable String userLogin,
        @Nullable String projectId,
        @Nullable String confinedTo) {

    public static ContactCaller of(
            String tenantId, SecurityContext subject, @Nullable String userLogin, @Nullable String projectId) {
        return new ContactCaller(tenantId, subject, blank(userLogin), blank(projectId), null);
    }

    public ContactCaller confined(String projectName) {
        return new ContactCaller(tenantId, subject, userLogin, projectId, projectName);
    }

    private static @Nullable String blank(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
