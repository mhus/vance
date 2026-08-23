package de.mhus.vance.shared.permission;

/**
 * SPI for seeding <em>initial</em> authority, implemented by whichever
 * permission-provider addon stores its grants inside Vance (the bundled
 * simple-auth provider). It breaks the chicken-and-egg of the enforcing
 * setup: the ADMIN-gated grant surfaces (web UI, admin REST) are unusable
 * until someone already holds ADMIN, so the very first grants must be
 * planted from a trusted bootstrap path.
 *
 * <p>Lives in {@code vance-shared} — not {@code vance-brain} — because both
 * the Brain ({@code BootstrapBrainService}, {@code ProjectLifecycleService})
 * and the operator shell ({@code vance-anus} setup wizard) need to seed
 * rights, and anus boots its own context that only depends on shared.
 *
 * <p><b>Optional by design.</b> Consumers inject
 * {@code ObjectProvider<PermissionBootstrap>} and call {@code ifAvailable(…)}:
 * when a provider that owns grant storage is loaded (simple-auth), the seed
 * runs; otherwise (allow-all provider, or an enterprise governor that manages
 * rights externally) it is a no-op. No consumer references a role model —
 * these methods express <em>intent</em> ("make this user the admin of that
 * project"); the implementation maps intent to its own role/grant model.
 *
 * <p>All methods must be idempotent — re-running the bootstrap on an existing
 * database must not fail or duplicate grants. See
 * {@code planning/permission-system-concept.md} §7.0.
 */
public interface PermissionBootstrap {

    /**
     * Grant {@code username} full administrative authority over the whole
     * {@code tenant} (create projects, manage settings, vergeben grants).
     */
    void grantTenantAdmin(String tenant, String username);

    /**
     * Grant {@code username} administrative authority over a single
     * {@code project} — typically the project's creator/owner.
     */
    void grantProjectAdmin(String tenant, String project, String username);

    /**
     * Grant a {@code team} write access to a single {@code project} — the
     * seed that turns the organisational "this team works on this project"
     * association into an actual permission.
     */
    void grantProjectTeamWriter(String tenant, String project, String team);

    /**
     * Drop every grant held by {@code username} in {@code tenant}, at any
     * scope. The counterpart of the grant methods: whoever seeds rights for a
     * short-lived account has to be able to take them back when the account
     * goes away.
     *
     * <p>A grant is keyed on the user <em>name</em>, not on the Mongo id, so it
     * survives the document unless somebody removes it — and a name can come
     * back (service accounts follow a scheme, human logins get reused), at which
     * point the new account silently inherits authority nobody granted it.
     * {@code UserService.delete} therefore calls this for every deletion; the
     * Trillian lifecycle hook calls it as well, ahead of the account delete, so
     * grants go even when the account itself is already gone.
     *
     * <p>Idempotent: removing grants of an unknown or already-cleaned user is
     * a no-op. Both callers rely on that.
     */
    void revokeAll(String tenant, String username);
}
