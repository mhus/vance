package de.mhus.vance.shared.permission;

import org.jspecify.annotations.Nullable;

/**
 * SPI for <em>asking</em> for rights, implemented by whichever permission
 * provider stores its grants inside Vance (the bundled simple-auth
 * provider). Counterpart of {@link PermissionBootstrap}, which seeds
 * rights from trusted code: this one is for callers who explicitly must
 * <b>not</b> be able to grant anything themselves.
 *
 * <p>Lives in {@code vance-shared} for the same reason as
 * {@link PermissionBootstrap}: consumers sit in {@code vance-brain} (and
 * later elsewhere) and must not depend on a provider implementation. The
 * method expresses <em>intent</em> — "this agent needs to work in that
 * project" — and the provider decides how a request is stored, who gets
 * asked, and what happens on approval.
 *
 * <p><b>Optional by design.</b> Consumers inject
 * {@code ObjectProvider<PermissionRequestPort>} and call
 * {@code ifAvailable(…)}: a governor that manages rights externally
 * simply offers no request path, and the calling tool reports that
 * asking is not possible here.
 */
public interface PermissionRequestPort {

    /**
     * Ask for write access to a project for {@code username}. Writes a
     * pending request and routes it to someone who may decide; changes
     * nothing by itself.
     *
     * <p>WRITER rather than ADMIN on purpose: an agent let into a foreign
     * project is meant to work there, not to administer it.
     *
     * @param reason      free text from the caller, treated as an
     *                    unverified claim and displayed as such
     * @param requestedBy user in whose session the request arises
     * @param processId   originating process, for the audit trail
     */
    PermissionRequestReceipt requestProjectWriter(
            String tenant, String project, String username,
            @Nullable String reason, String requestedBy, @Nullable String processId);

    /**
     * Outcome of raising a request — enough for the caller to tell the
     * user what happens next, and nothing more.
     *
     * @param requestId  id of the stored request
     * @param itemId     inbox item carrying it, {@code null} when nobody
     *                   could be asked
     * @param status     lifecycle state, normally {@code PENDING}
     * @param decider    who was asked, {@code null} when nobody was found
     * @param reused     {@code true} when an identical request was
     *                   already pending and got reused
     */
    record PermissionRequestReceipt(
            String requestId,
            @Nullable String itemId,
            String status,
            @Nullable String decider,
            boolean reused) {
    }
}
